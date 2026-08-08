/*
 * Copyright 2025 Hippo
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "config.h"
#ifdef IMAGE_SUPPORT_WEBP

#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "../log.h"
#include "image_utils.h"
#include "image_webp.h"
#include "patch_head_input_stream.h"
#include "webp/decode.h"
#include "webp/demux.h"

#define WEBP_DEFAULT_FRAME_DELAY 100
#define WEBP_DIRTY_FULL_UPLOAD_PERCENT 60u

static bool checked_rgba_size(unsigned int width, unsigned int height,
                              size_t* size) {
  const size_t pixels = (size_t) width * (size_t) height;
  if (width == 0 || height == 0 || pixels / width != height
      || pixels > SIZE_MAX / 4u) {
    return false;
  }
  *size = pixels * 4u;
  return true;
}

static unsigned int scaled_edge(unsigned int value, unsigned int sample_size) {
  return (value + sample_size - 1u) / sample_size;
}

static void clear_rect(unsigned char* canvas, unsigned int canvas_width,
                       const WEBP_FRAME_INFO* frame) {
  const size_t stride = (size_t) canvas_width * 4u;
  unsigned char* row = canvas + (size_t) frame->y_offset * stride
      + (size_t) frame->x_offset * 4u;
  for (unsigned int y = 0; y < frame->height; ++y) {
    memset(row, 0, (size_t) frame->width * 4u);
    row += stride;
  }
}

static void add_dirty_rect(WEBP* webp, unsigned int left, unsigned int top,
                           unsigned int width, unsigned int height) {
  if (width == 0 || height == 0 || left >= webp->width || top >= webp->height) {
    return;
  }
  unsigned int right = left + width;
  unsigned int bottom = top + height;
  if (right < left || right > webp->width) right = webp->width;
  if (bottom < top || bottom > webp->height) bottom = webp->height;
  if (!webp->dirty_pending) {
    webp->dirty_left = left;
    webp->dirty_top = top;
    webp->dirty_right = right;
    webp->dirty_bottom = bottom;
    webp->dirty_pending = true;
  } else {
    if (left < webp->dirty_left) webp->dirty_left = left;
    if (top < webp->dirty_top) webp->dirty_top = top;
    if (right > webp->dirty_right) webp->dirty_right = right;
    if (bottom > webp->dirty_bottom) webp->dirty_bottom = bottom;
  }
}

static void add_full_dirty_rect(WEBP* webp) {
  webp->dirty_left = 0;
  webp->dirty_top = 0;
  webp->dirty_right = webp->width;
  webp->dirty_bottom = webp->height;
  webp->dirty_pending = true;
}

// A transition changes the new ANMF rectangle and, when requested, the
// previous frame rectangle cleared by DISPOSE_BACKGROUND. A new loop resets
// the WebP canvas, so frame zero conservatively dirties the full texture.
static void add_transition_dirty_rect(WEBP* webp,
                                      unsigned int next_frame) {
  if (next_frame == 0 || webp->current_frame >= webp->frame_count) {
    add_full_dirty_rect(webp);
    return;
  }
  const WEBP_FRAME_INFO* next = &webp->frames[next_frame];
  add_dirty_rect(webp, next->x_offset, next->y_offset,
                 next->width, next->height);
  const WEBP_FRAME_INFO* previous = &webp->frames[webp->current_frame];
  if (previous->dispose_method == WEBP_MUX_DISPOSE_BACKGROUND) {
    add_dirty_rect(webp, previous->x_offset, previous->y_offset,
                   previous->width, previous->height);
  }
}

static bool decode_fragment(const WebPIterator* iter, int output_width,
                            int output_height, unsigned char* output,
                            int output_stride, size_t output_size) {
  WebPDecoderConfig config;
  if (!WebPInitDecoderConfig(&config)) return false;
  config.output.colorspace = MODE_RGBA;
  config.output.is_external_memory = 1;
  config.output.u.RGBA.rgba = output;
  config.output.u.RGBA.stride = output_stride;
  config.output.u.RGBA.size = output_size;
  config.options.use_threads = 1;
  if (output_width != iter->width || output_height != iter->height) {
    config.options.use_scaling = 1;
    config.options.scaled_width = output_width;
    config.options.scaled_height = output_height;
  }
  const VP8StatusCode status = WebPDecode(
      iter->fragment.bytes, iter->fragment.size, &config);
  WebPFreeDecBuffer(&config.output);
  return status == VP8_STATUS_OK;
}

static bool rect_is_opaque(const unsigned char* pixels, unsigned int width,
                           unsigned int height, size_t stride) {
  for (unsigned int y = 0; y < height; ++y) {
    const unsigned char* pixel = pixels + y * stride + 3u;
    for (unsigned int x = 0; x < width; ++x, pixel += 4u) {
      if (*pixel != 255u) return false;
    }
  }
  return true;
}

static void blend_rect(unsigned char* dst, size_t dst_stride,
                       const unsigned char* src, size_t src_stride,
                       unsigned int width, unsigned int height) {
  for (unsigned int y = 0; y < height; ++y) {
    unsigned char* dst_pixel = dst + y * dst_stride;
    const unsigned char* src_pixel = src + y * src_stride;
    for (unsigned int x = 0; x < width;
         ++x, dst_pixel += 4u, src_pixel += 4u) {
      const unsigned int src_a = src_pixel[3];
      if (src_a == 255u) {
        memcpy(dst_pixel, src_pixel, 4u);
      } else if (src_a != 0u) {
        const unsigned int dst_factor_a =
            (dst_pixel[3] * (256u - src_a)) >> 8u;
        const unsigned int blend_a = src_a + dst_factor_a;
        const uint64_t scale = (1ull << 24u) / blend_a;
        for (unsigned int channel = 0; channel < 3u; ++channel) {
          const uint64_t unscaled = src_pixel[channel] * src_a
              + dst_pixel[channel] * dst_factor_a;
          dst_pixel[channel] = (unsigned char) ((unscaled * scale) >> 24u);
        }
        dst_pixel[3] = (unsigned char) blend_a;
      }
    }
  }
}

static bool ensure_alpha_temp(WEBP* webp, size_t size) {
  if (webp->alpha_temp_buffer_size >= size) return true;
  unsigned char* replacement =
      (unsigned char*) realloc(webp->alpha_temp_buffer, size);
  if (replacement == NULL) {
    WTF_OM;
    return false;
  }
  webp->alpha_temp_buffer = replacement;
  webp->alpha_temp_buffer_size = size;
  return true;
}

static void prepare_previous_canvas(WEBP* webp, unsigned int frame_index) {
  if (frame_index == 0 || webp->current_frame >= webp->frame_count) {
    memset(webp->next_frame_buffer, 0, webp->frame_buffer_size);
    return;
  }
  memcpy(webp->next_frame_buffer, webp->current_frame_buffer,
         webp->frame_buffer_size);
  const WEBP_FRAME_INFO* previous = &webp->frames[webp->current_frame];
  if (previous->dispose_method == WEBP_MUX_DISPOSE_BACKGROUND) {
    clear_rect(webp->next_frame_buffer, webp->width, previous);
  }
}

// Decodes one ANMF fragment into the back canvas and atomically publishes it.
// Unlike WebPAnimDecoder this keeps no disposed full-canvas copy. The common
// full-frame/opaque case therefore performs no canvas memcpy or alpha blend.
static bool decode_frame(WEBP* webp, unsigned int frame_index,
                         bool pixels_locked, bool publish) {
  if (frame_index >= webp->frame_count) return false;
  WebPIterator iter;
  if (!WebPDemuxGetFrame(webp->demux, (int) frame_index + 1, &iter)) {
    return false;
  }

  WEBP_FRAME_INFO* frame = &webp->frames[frame_index];
  const size_t canvas_stride = (size_t) webp->width * 4u;
  const size_t rect_stride = (size_t) frame->width * 4u;
  size_t rect_size;
  if (!checked_rgba_size(frame->width, frame->height, &rect_size)) {
    WebPDemuxReleaseIterator(&iter);
    return false;
  }
  const bool full_frame = frame->x_offset == 0 && frame->y_offset == 0
      && frame->width == webp->width && frame->height == webp->height;
  const bool blend_alpha = frame_index > 0
      && frame->blend_method == WEBP_MUX_BLEND && frame->has_alpha;
  bool decoded = false;

  // For a full alpha frame, decode first and inspect its real alpha values.
  // Many GIF-derived WebPs carry an ALPH chunk whose decoded pixels are all
  // opaque. Caching this result avoids all full-canvas copies on later loops.
  if (full_frame && blend_alpha && frame->decoded_opaque != 0) {
    decoded = decode_fragment(&iter, (int) frame->width, (int) frame->height,
                              webp->next_frame_buffer, (int) canvas_stride,
                              webp->frame_buffer_size);
    if (decoded && frame->decoded_opaque < 0) {
      frame->decoded_opaque = rect_is_opaque(
          webp->next_frame_buffer, frame->width, frame->height,
          canvas_stride) ? 1 : 0;
    }
    if (decoded && frame->decoded_opaque == 0) {
      if (!ensure_alpha_temp(webp, rect_size)) {
        decoded = false;
      } else {
        memcpy(webp->alpha_temp_buffer, webp->next_frame_buffer, rect_size);
        prepare_previous_canvas(webp, frame_index);
        blend_rect(webp->next_frame_buffer, canvas_stride,
                   webp->alpha_temp_buffer, rect_stride,
                   frame->width, frame->height);
      }
    }
  } else {
    const bool needs_previous_canvas = !full_frame
        || (blend_alpha && frame->decoded_opaque != 1);
    if (needs_previous_canvas) {
      prepare_previous_canvas(webp, frame_index);
    }
    unsigned char* destination = webp->next_frame_buffer
        + (size_t) frame->y_offset * canvas_stride
        + (size_t) frame->x_offset * 4u;
    if (blend_alpha && frame->decoded_opaque != 1) {
      if (ensure_alpha_temp(webp, rect_size)) {
        decoded = decode_fragment(&iter, (int) frame->width,
                                  (int) frame->height,
                                  webp->alpha_temp_buffer, (int) rect_stride,
                                  rect_size);
        if (decoded) {
          if (frame->decoded_opaque < 0) {
            frame->decoded_opaque = rect_is_opaque(
                webp->alpha_temp_buffer, frame->width, frame->height,
                rect_stride) ? 1 : 0;
          }
          if (frame->decoded_opaque == 1) {
            for (unsigned int y = 0; y < frame->height; ++y) {
              memcpy(destination + y * canvas_stride,
                     webp->alpha_temp_buffer + y * rect_stride, rect_stride);
            }
          } else {
            blend_rect(destination, canvas_stride, webp->alpha_temp_buffer,
                       rect_stride, frame->width, frame->height);
          }
        }
      }
    } else {
      decoded = decode_fragment(&iter, (int) frame->width,
                                (int) frame->height, destination,
                                (int) canvas_stride,
                                (size_t) frame->height * canvas_stride);
    }
  }
  WebPDemuxReleaseIterator(&iter);
  if (!decoded) return false;

  if (publish) {
    if (!pixels_locked) WEBP_lock_pixels(webp);
    add_transition_dirty_rect(webp, frame_index);
    unsigned char* swap = webp->current_frame_buffer;
    webp->current_frame_buffer = webp->next_frame_buffer;
    webp->next_frame_buffer = swap;
    webp->current_frame = frame_index;
    webp->frame_prepared = false;
    if (!pixels_locked) WEBP_unlock_pixels(webp);
  } else {
    webp->prepared_frame = frame_index;
    webp->frame_prepared = true;
  }
  return true;
}

static bool decode_static_image(WEBP* webp, const uint8_t* data, size_t length,
                                const WebPBitstreamFeatures* features) {
  size_t buffer_size;
  if (features->width <= 0 || features->height <= 0
      || !checked_rgba_size((unsigned int) features->width,
                            (unsigned int) features->height, &buffer_size)) {
    LOGE(MSG("Invalid static WebP dimensions"));
    return false;
  }
  webp->buffer = (unsigned char*) malloc(buffer_size);
  if (webp->buffer == NULL) {
    WTF_OM;
    return false;
  }
  if (WebPDecodeRGBAInto(data, length, webp->buffer, buffer_size,
                        features->width * 4) == NULL) {
    free(webp->buffer);
    webp->buffer = NULL;
    return false;
  }
  webp->width = (unsigned int) features->width;
  webp->height = (unsigned int) features->height;
  webp->frame_count = 1;
  return true;
}

static bool decode_animation(WEBP* webp, const uint8_t* data, size_t length,
                             int requested_sample_size) {
  WebPData webp_data = { data, length };
  webp->demux = WebPDemux(&webp_data);
  if (webp->demux == NULL) return false;
  const unsigned int source_width =
      WebPDemuxGetI(webp->demux, WEBP_FF_CANVAS_WIDTH);
  const unsigned int source_height =
      WebPDemuxGetI(webp->demux, WEBP_FF_CANVAS_HEIGHT);
  webp->frame_count = WebPDemuxGetI(webp->demux, WEBP_FF_FRAME_COUNT);
  if (source_width == 0 || source_height == 0 || webp->frame_count < 2) {
    return false;
  }
  const unsigned int sample_size = requested_sample_size == 2 ? 2u : 1u;
  webp->width = scaled_edge(source_width, sample_size);
  webp->height = scaled_edge(source_height, sample_size);
  if (!checked_rgba_size(webp->width, webp->height,
                         &webp->frame_buffer_size)) {
    return false;
  }

  webp->frames = (WEBP_FRAME_INFO*) calloc(
      webp->frame_count, sizeof(WEBP_FRAME_INFO));
  webp->current_frame_buffer =
      (unsigned char*) calloc(1, webp->frame_buffer_size);
  webp->next_frame_buffer =
      (unsigned char*) calloc(1, webp->frame_buffer_size);
  if (webp->frames == NULL || webp->current_frame_buffer == NULL
      || webp->next_frame_buffer == NULL) {
    WTF_OM;
    return false;
  }
  if (pthread_mutex_init(&webp->frame_buffer_mutex, NULL) != 0) return false;
  webp->frame_buffer_mutex_initialized = true;

  WebPIterator iter;
  if (!WebPDemuxGetFrame(webp->demux, 1, &iter)) return false;
  do {
    const unsigned int index = (unsigned int) iter.frame_num - 1u;
    if (index >= webp->frame_count) continue;
    WEBP_FRAME_INFO* frame = &webp->frames[index];
    frame->x_offset = iter.x_offset / sample_size;
    frame->y_offset = iter.y_offset / sample_size;
    const unsigned int right = scaled_edge(
        (unsigned int) iter.x_offset + (unsigned int) iter.width, sample_size);
    const unsigned int bottom = scaled_edge(
        (unsigned int) iter.y_offset + (unsigned int) iter.height, sample_size);
    frame->width = right - frame->x_offset;
    frame->height = bottom - frame->y_offset;
    frame->duration = iter.duration <= 10
        ? WEBP_DEFAULT_FRAME_DELAY : iter.duration;
    frame->dispose_method = iter.dispose_method;
    frame->blend_method = iter.blend_method;
    frame->has_alpha = iter.has_alpha != 0;
    frame->decoded_opaque = frame->has_alpha ? -1 : 1;
    if (webp->total_duration <= INT_MAX - frame->duration) {
      webp->total_duration += frame->duration;
    } else {
      webp->total_duration = INT_MAX;
    }
  } while (WebPDemuxNextFrame(&iter));
  WebPDemuxReleaseIterator(&iter);

  webp->current_frame = UINT_MAX;
  if (!decode_frame(webp, 0, false, true)) return false;
  webp->animated = true;
  return true;
}

void* WEBP_decode(JNIEnv* env, PatchHeadInputStream* stream, bool partially,
                  int sample_size) {
  size_t length = 0;
  unsigned char* data =
      (unsigned char*) read_patch_head_input_stream_all(env, stream, &length);
  close_patch_head_input_stream(env, stream);
  destroy_patch_head_input_stream(env, &stream);
  (void) partially;
  if (data == NULL) return NULL;

  WebPBitstreamFeatures features;
  if (WebPGetFeatures(data, length, &features) != VP8_STATUS_OK) {
    free(data);
    return NULL;
  }
  WEBP* webp = (WEBP*) calloc(1, sizeof(WEBP));
  if (webp == NULL) {
    free(data);
    return NULL;
  }
  webp->is_opaque = !features.has_alpha;
  if (features.has_animation) {
    webp->encoded_data = data;
    webp->encoded_length = length;
  }
  const bool ok = features.has_animation
      ? decode_animation(webp, data, length, sample_size)
      : decode_static_image(webp, data, length, &features);
  if (!ok) {
    WEBP_recycle(env, webp);
    if (!features.has_animation) free(data);
    return NULL;
  }
  if (!features.has_animation) free(data);
  return webp;
}

bool WEBP_complete(JNIEnv* env, WEBP* webp) {
  (void) env;
  (void) webp;
  return true;
}

bool WEBP_is_completed(WEBP* webp) {
  (void) webp;
  return true;
}

void* WEBP_get_pixels(WEBP* webp) {
  return webp == NULL ? NULL
      : (webp->animated ? webp->current_frame_buffer : webp->buffer);
}

void* WEBP_get_upload_pixels(WEBP* webp, bool init, int* x, int* y,
                             int* width, int* height) {
  if (webp == NULL || !webp->animated || webp->current_frame_buffer == NULL) {
    return NULL;
  }
  if (init) add_full_dirty_rect(webp);
  if (!webp->dirty_pending) return NULL;

  unsigned int left = webp->dirty_left;
  unsigned int top = webp->dirty_top;
  unsigned int dirty_width = webp->dirty_right - left;
  unsigned int dirty_height = webp->dirty_bottom - top;
  const uint64_t dirty_area = (uint64_t) dirty_width * dirty_height;
  const uint64_t canvas_area = (uint64_t) webp->width * webp->height;

  // Avoid an almost full-frame staging copy. Full-width rows are already
  // contiguous, and large narrow rectangles are cheaper to upload directly as
  // the complete canvas.
  if (dirty_width != webp->width &&
      dirty_area * 100u >= canvas_area * WEBP_DIRTY_FULL_UPLOAD_PERCENT) {
    left = 0;
    top = 0;
    dirty_width = webp->width;
    dirty_height = webp->height;
  }

  unsigned char* pixels;
  const size_t canvas_stride = (size_t) webp->width * 4u;
  if (left == 0 && dirty_width == webp->width) {
    pixels = webp->current_frame_buffer + (size_t) top * canvas_stride;
  } else {
    size_t upload_size;
    if (!checked_rgba_size(dirty_width, dirty_height, &upload_size)) {
      return NULL;
    }
    if (webp->upload_buffer_size < upload_size) {
      unsigned char* replacement =
          (unsigned char*) realloc(webp->upload_buffer, upload_size);
      if (replacement == NULL) {
        // Keep the GPU image correct under memory pressure. The full canvas is
        // contiguous and requires no staging allocation.
        left = 0;
        top = 0;
        dirty_width = webp->width;
        dirty_height = webp->height;
        pixels = webp->current_frame_buffer;
        goto UploadReady;
      }
      webp->upload_buffer = replacement;
      webp->upload_buffer_size = upload_size;
    }
    const size_t row_size = (size_t) dirty_width * 4u;
    const unsigned char* source = webp->current_frame_buffer
        + (size_t) top * canvas_stride + (size_t) left * 4u;
    unsigned char* destination = webp->upload_buffer;
    for (unsigned int row = 0; row < dirty_height; ++row) {
      memcpy(destination, source, row_size);
      destination += row_size;
      source += canvas_stride;
    }
    pixels = webp->upload_buffer;
  }

UploadReady:
  *x = (int) left;
  *y = (int) top;
  *width = (int) dirty_width;
  *height = (int) dirty_height;
  webp->dirty_pending = false;
  return pixels;
}

void WEBP_lock_pixels(WEBP* webp) {
  if (webp != NULL && webp->frame_buffer_mutex_initialized) {
    pthread_mutex_lock(&webp->frame_buffer_mutex);
  }
}

void WEBP_unlock_pixels(WEBP* webp) {
  if (webp != NULL && webp->frame_buffer_mutex_initialized) {
    pthread_mutex_unlock(&webp->frame_buffer_mutex);
  }
}

int WEBP_get_width(WEBP* webp) { return (int) webp->width; }
int WEBP_get_height(WEBP* webp) { return (int) webp->height; }

int WEBP_get_byte_count(WEBP* webp) {
  size_t size = sizeof(WEBP) + webp->encoded_length;
  if (webp->animated) size += webp->frame_buffer_size * 2u;
  if (webp->buffer != NULL) size += (size_t) webp->width * webp->height * 4u;
  if (webp->frames != NULL) {
    size += (size_t) webp->frame_count * sizeof(WEBP_FRAME_INFO);
  }
  size += webp->alpha_temp_buffer_size;
  size += webp->upload_buffer_size;
  return size > INT_MAX ? INT_MAX : (int) size;
}

void WEBP_render(WEBP* webp, int src_x, int src_y,
                 void* dst, int dst_w, int dst_h, int dst_x, int dst_y,
                 int width, int height, bool fill_blank, int default_color) {
  if (webp == NULL) return;
  if (webp->animated) WEBP_lock_pixels(webp);
  unsigned char* source = webp->animated
      ? webp->current_frame_buffer : webp->buffer;
  if (source != NULL) {
    copy_pixels(source, webp->width, webp->height, src_x, src_y,
                dst, dst_w, dst_h, dst_x, dst_y,
                width, height, fill_blank, default_color);
  }
  if (webp->animated) WEBP_unlock_pixels(webp);
}

void WEBP_advance(WEBP* webp) { WEBP_advance_and_get_looped(webp); }

bool WEBP_advance_and_get_looped(WEBP* webp) {
  if (!WEBP_prepare_next_frame(webp)) return false;
  return WEBP_present_prepared_frame(webp);
}

bool WEBP_prepare_next_frame(WEBP* webp) {
  if (webp == NULL || !webp->animated || webp->demux == NULL) return false;
  if (webp->frame_prepared) return true;
  const unsigned int next_frame = webp->current_frame + 1u >= webp->frame_count
      ? 0u : webp->current_frame + 1u;
  webp->prepared_looped = next_frame == 0u;
  return decode_frame(webp, next_frame, false, false);
}

bool WEBP_present_prepared_frame(WEBP* webp) {
  if (webp == NULL || !webp->frame_prepared) return false;
  WEBP_lock_pixels(webp);
  add_transition_dirty_rect(webp, webp->prepared_frame);
  unsigned char* swap = webp->current_frame_buffer;
  webp->current_frame_buffer = webp->next_frame_buffer;
  webp->next_frame_buffer = swap;
  webp->current_frame = webp->prepared_frame;
  const bool looped = webp->prepared_looped;
  webp->frame_prepared = false;
  WEBP_unlock_pixels(webp);
  return looped;
}

int WEBP_seek_to(WEBP* webp, int position_ms) {
  if (webp == NULL || !webp->animated || webp->demux == NULL) return 0;
  if (position_ms < 0) position_ms = 0;
  if (position_ms >= webp->total_duration) position_ms = webp->total_duration - 1;
  int frame_start = 0;
  unsigned int target = 0;
  for (unsigned int i = 0; i < webp->frame_count; ++i) {
    target = i;
    if (position_ms < frame_start + webp->frames[i].duration) break;
    frame_start += webp->frames[i].duration;
  }
  if (target == webp->current_frame) return frame_start;

  WEBP_lock_pixels(webp);
  webp->frame_prepared = false;
  unsigned int first_frame;
  if (target < webp->current_frame) {
    webp->current_frame = UINT_MAX;
    memset(webp->current_frame_buffer, 0, webp->frame_buffer_size);
    memset(webp->next_frame_buffer, 0, webp->frame_buffer_size);
    first_frame = 0;
  } else {
    first_frame = webp->current_frame + 1u;
  }
  for (unsigned int i = first_frame; i <= target; ++i) {
    if (!decode_frame(webp, i, true, true)) {
      WEBP_unlock_pixels(webp);
      return WEBP_get_current_position(webp);
    }
  }
  // The GPU may still contain the image from before an arbitrary multi-frame
  // seek. Synchronize it with one safe full upload.
  add_full_dirty_rect(webp);
  WEBP_unlock_pixels(webp);
  return frame_start;
}

int WEBP_get_current_position(WEBP* webp) {
  int position = 0;
  if (webp == NULL || webp->frames == NULL
      || webp->current_frame >= webp->frame_count) return 0;
  for (unsigned int i = 0; i < webp->current_frame; ++i) {
    if (position > INT_MAX - webp->frames[i].duration) return INT_MAX;
    position += webp->frames[i].duration;
  }
  return position;
}

int WEBP_get_total_duration(WEBP* webp) {
  return webp != NULL && webp->animated ? webp->total_duration : 0;
}

int WEBP_get_delay(WEBP* webp) {
  return webp == NULL || !webp->animated || webp->frames == NULL
      || webp->current_frame >= webp->frame_count ? 0
      : webp->frames[webp->current_frame].duration;
}

int WEBP_get_frame_count(WEBP* webp) {
  return webp != NULL && webp->animated ? (int) webp->frame_count : 1;
}

bool WEBP_is_opaque(WEBP* webp) { return webp != NULL && webp->is_opaque; }

void WEBP_recycle(JNIEnv* env, WEBP* webp) {
  (void) env;
  if (webp == NULL) return;
  if (webp->demux != NULL) WebPDemuxDelete(webp->demux);
  free(webp->buffer);
  free(webp->current_frame_buffer);
  free(webp->next_frame_buffer);
  free(webp->alpha_temp_buffer);
  free(webp->upload_buffer);
  free(webp->frames);
  free(webp->encoded_data);
  if (webp->frame_buffer_mutex_initialized) {
    pthread_mutex_destroy(&webp->frame_buffer_mutex);
  }
  free(webp);
}

#endif // IMAGE_SUPPORT_WEBP

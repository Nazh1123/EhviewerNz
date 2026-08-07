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

static bool decode_animation(WEBP* webp, const uint8_t* data, size_t length) {
  WebPAnimDecoderOptions options;
  WebPAnimInfo info;
  WebPData webp_data;
  uint8_t* frame = NULL;
  int timestamp = 0;
  if (!WebPAnimDecoderOptionsInit(&options)) return false;
  options.color_mode = MODE_RGBA;
  options.use_threads = 1;
  webp_data.bytes = data;
  webp_data.size = length;
  webp->decoder = WebPAnimDecoderNew(&webp_data, &options);
  if (webp->decoder == NULL || !WebPAnimDecoderGetInfo(webp->decoder, &info)
      || info.canvas_width == 0 || info.canvas_height == 0
      || info.frame_count < 2
      || !checked_rgba_size(info.canvas_width, info.canvas_height,
                            &webp->frame_buffer_size)) {
    return false;
  }
  webp->delays = (int*) calloc(info.frame_count, sizeof(int));
  if (webp->delays == NULL) {
    WTF_OM;
    return false;
  }
  if (pthread_mutex_init(&webp->frame_buffer_mutex, NULL) != 0) return false;
  webp->frame_buffer_mutex_initialized = true;

  const WebPDemuxer* demux = WebPAnimDecoderGetDemuxer(webp->decoder);
  WebPIterator iter;
  if (demux != NULL && WebPDemuxGetFrame(demux, 1, &iter)) {
    do {
      const int index = iter.frame_num - 1;
      if (index >= 0 && (unsigned int) index < info.frame_count) {
        webp->delays[index] = iter.duration;
      }
    } while (WebPDemuxNextFrame(&iter));
    WebPDemuxReleaseIterator(&iter);
  }
  webp->total_duration = 0;
  for (unsigned int i = 0; i < info.frame_count; i++) {
    if (webp->delays[i] <= 10) webp->delays[i] = WEBP_DEFAULT_FRAME_DELAY;
    if (webp->total_duration <= INT_MAX - webp->delays[i]) {
      webp->total_duration += webp->delays[i];
    } else {
      webp->total_duration = INT_MAX;
    }
  }
  if (!WebPAnimDecoderGetNext(webp->decoder, &frame, &timestamp)) return false;
  webp->current_frame_buffer = (unsigned char*) malloc(webp->frame_buffer_size);
  if (webp->current_frame_buffer == NULL) {
    WTF_OM;
    return false;
  }
  memcpy(webp->current_frame_buffer, frame, webp->frame_buffer_size);
  webp->width = info.canvas_width;
  webp->height = info.canvas_height;
  webp->animated = true;
  webp->frame_count = info.frame_count;
  webp->encoded_data = (unsigned char*) data;
  webp->encoded_length = length;
  return true;
}

void* WEBP_decode(JNIEnv* env, PatchHeadInputStream* stream, bool partially) {
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
  const bool ok = features.has_animation
      ? decode_animation(webp, data, length)
      : decode_static_image(webp, data, length, &features);
  if (!ok) {
    WEBP_recycle(env, webp);
    free(data);
    return NULL;
  }
  if (features.has_animation) data = NULL; // decoder borrows the bytes
  free(data);
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
  size_t size = sizeof(WEBP) + webp->encoded_length + webp->frame_buffer_size;
  if (webp->buffer != NULL) size += (size_t) webp->width * webp->height * 4u;
  if (webp->delays != NULL) size += (size_t) webp->frame_count * sizeof(int);
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
  if (webp == NULL || !webp->animated || webp->decoder == NULL) return false;
  bool looped = false;
  unsigned int next_frame;
  if (!WebPAnimDecoderHasMoreFrames(webp->decoder)) {
    WebPAnimDecoderReset(webp->decoder);
    next_frame = 0;
    looped = true;
  } else {
    next_frame = webp->current_frame + 1;
  }
  uint8_t* frame = NULL;
  int timestamp = 0;
  if (!WebPAnimDecoderGetNext(webp->decoder, &frame, &timestamp)) {
    return false;
  }
  WEBP_lock_pixels(webp);
  memcpy(webp->current_frame_buffer, frame, webp->frame_buffer_size);
  webp->current_frame = next_frame;
  WEBP_unlock_pixels(webp);
  return looped;
}

int WEBP_seek_to(WEBP* webp, int position_ms) {
  if (webp == NULL || !webp->animated || webp->decoder == NULL) return 0;
  if (position_ms < 0) position_ms = 0;
  if (position_ms >= webp->total_duration) position_ms = webp->total_duration - 1;
  int frame_start = 0;
  unsigned int target = 0;
  for (unsigned int i = 0; i < webp->frame_count; i++) {
    target = i;
    if (position_ms < frame_start + webp->delays[i]) break;
    frame_start += webp->delays[i];
  }
  unsigned int first_frame;
  if (target < webp->current_frame) {
    WebPAnimDecoderReset(webp->decoder);
    first_frame = 0;
  } else if (target > webp->current_frame) {
    // The decoder is already positioned immediately after current_frame.
    // Continue from there so forward scrubbing does not repeatedly decode the
    // complete animation prefix.
    first_frame = webp->current_frame + 1;
  } else {
    return frame_start;
  }
  uint8_t* frame = NULL;
  int timestamp = 0;
  for (unsigned int i = first_frame; i <= target; i++) {
    if (!WebPAnimDecoderGetNext(webp->decoder, &frame, &timestamp)) {
      return WEBP_get_current_position(webp);
    }
  }
  WEBP_lock_pixels(webp);
  memcpy(webp->current_frame_buffer, frame, webp->frame_buffer_size);
  webp->current_frame = target;
  WEBP_unlock_pixels(webp);
  return frame_start;
}

int WEBP_get_current_position(WEBP* webp) {
  int position = 0;
  if (webp == NULL || webp->delays == NULL) return 0;
  for (unsigned int i = 0; i < webp->current_frame; i++) {
    if (position > INT_MAX - webp->delays[i]) return INT_MAX;
    position += webp->delays[i];
  }
  return position;
}

int WEBP_get_total_duration(WEBP* webp) {
  return webp != NULL && webp->animated ? webp->total_duration : 0;
}

int WEBP_get_delay(WEBP* webp) {
  return webp == NULL || !webp->animated || webp->delays == NULL ? 0
      : webp->delays[webp->current_frame % webp->frame_count];
}

int WEBP_get_frame_count(WEBP* webp) {
  return webp != NULL && webp->animated ? (int) webp->frame_count : 1;
}

bool WEBP_is_opaque(WEBP* webp) { return webp != NULL && webp->is_opaque; }

void WEBP_recycle(JNIEnv* env, WEBP* webp) {
  (void) env;
  if (webp == NULL) return;
  if (webp->decoder != NULL) WebPAnimDecoderDelete(webp->decoder);
  free(webp->buffer);
  free(webp->current_frame_buffer);
  free(webp->encoded_data);
  free(webp->delays);
  if (webp->frame_buffer_mutex_initialized) {
    pthread_mutex_destroy(&webp->frame_buffer_mutex);
  }
  free(webp);
}

#endif // IMAGE_SUPPORT_WEBP

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

#ifndef IMAGE_IMAGE_WEBP_H
#define IMAGE_IMAGE_WEBP_H

#include "config.h"
#ifdef IMAGE_SUPPORT_WEBP

#include <pthread.h>
#include <stdbool.h>
#include <stddef.h>

#include "patch_head_input_stream.h"
#include "../utils.h"
#include "webp/decode.h"
#include "webp/demux.h"

#define IMAGE_WEBP_DECODER_DESCRIPTION \
  ("libwebp " MAKESTRING(STRINGIZE, WEBP_DECODER_ABI_VERSION))

#define IMAGE_WEBP_MAGIC_NUMBER_0 0x52
#define IMAGE_WEBP_MAGIC_NUMBER_1 0x49
#define IMAGE_WEBP_MAGIC_NUMBER_00 0x57
#define IMAGE_WEBP_MAGIC_NUMBER_11 0x45

typedef struct {
  unsigned int x_offset;
  unsigned int y_offset;
  unsigned int width;
  unsigned int height;
  int duration;
  WebPMuxAnimDispose dispose_method;
  WebPMuxAnimBlend blend_method;
  bool has_alpha;
  // -1 unknown, 0 contains transparency, 1 fully opaque after decoding.
  signed char decoded_opaque;
} WEBP_FRAME_INFO;

typedef struct {
  unsigned int width;
  unsigned int height;
  bool animated;
  bool is_opaque;
  unsigned int frame_count;
  unsigned int current_frame;
  unsigned char* buffer;
  unsigned char* encoded_data;
  size_t encoded_length;
  WebPDemuxer* demux;
  // Double-buffered RGBA canvases. libwebp decodes into the back canvas while
  // the GL thread keeps reading the stable current canvas.
  unsigned char* current_frame_buffer;
  unsigned char* next_frame_buffer;
  unsigned char* alpha_temp_buffer;
  size_t alpha_temp_buffer_size;
  size_t frame_buffer_size;
  pthread_mutex_t frame_buffer_mutex;
  bool frame_buffer_mutex_initialized;
  WEBP_FRAME_INFO* frames;
  int total_duration;
  unsigned int prepared_frame;
  bool frame_prepared;
  bool prepared_looped;
  unsigned int dirty_left;
  unsigned int dirty_top;
  unsigned int dirty_right;
  unsigned int dirty_bottom;
  bool dirty_pending;
  unsigned char* upload_buffer;
  size_t upload_buffer_size;
} WEBP;

void* WEBP_decode(JNIEnv* env, PatchHeadInputStream* patch_head_input_stream,
                  bool partially, int sample_size);
bool WEBP_complete(JNIEnv* env, WEBP* webp);
bool WEBP_is_completed(WEBP* webp);
void* WEBP_get_pixels(WEBP* webp);
// Returns tightly packed pixels for the accumulated metadata dirty rectangle.
// The caller must hold the pixel mutex until the GL upload has completed.
void* WEBP_get_upload_pixels(WEBP* webp, bool init, int* x, int* y,
                             int* width, int* height);
void WEBP_lock_pixels(WEBP* webp);
void WEBP_unlock_pixels(WEBP* webp);
int WEBP_get_width(WEBP* webp);
int WEBP_get_height(WEBP* webp);
int WEBP_get_byte_count(WEBP* webp);
void WEBP_render(WEBP* webp, int src_x, int src_y,
                 void* dst, int dst_w, int dst_h, int dst_x, int dst_y,
                 int width, int height, bool fill_blank, int default_color);
void WEBP_advance(WEBP* webp);
bool WEBP_advance_and_get_looped(WEBP* webp);
bool WEBP_prepare_next_frame(WEBP* webp);
bool WEBP_present_prepared_frame(WEBP* webp);
int WEBP_seek_to(WEBP* webp, int position_ms);
int WEBP_get_current_position(WEBP* webp);
int WEBP_get_total_duration(WEBP* webp);
int WEBP_get_delay(WEBP* webp);
int WEBP_get_frame_count(WEBP* webp);
bool WEBP_is_opaque(WEBP* webp);
void WEBP_recycle(JNIEnv* env, WEBP* webp);

#endif // IMAGE_SUPPORT_WEBP

#endif // IMAGE_IMAGE_WEBP_H

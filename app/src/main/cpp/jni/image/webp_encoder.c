#include <jni.h>
#include <android/bitmap.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "webp/encode.h"

typedef struct { WebPPicture picture; int next_y; } Frame;
typedef struct {
  JNIEnv* env; jobject output; jmethodID write; jbyteArray buffer;
  jobject progress; jmethodID run;
} Sink;

static int write_output(const uint8_t* bytes, size_t size, const WebPPicture* picture) {
  Sink* sink = picture->custom_ptr;
  JNIEnv* env = sink->env;
  while (size > 0 && !(*env)->ExceptionCheck(env)) {
    const jint count = (jint)(size > 65536 ? 65536 : size);
    (*env)->SetByteArrayRegion(env, sink->buffer, 0, count, (const jbyte*)bytes);
    if ((*env)->ExceptionCheck(env)) return 0;
    (*env)->CallVoidMethod(env, sink->output, sink->write, sink->buffer, 0, count);
    bytes += count; size -= count;
  }
  return !(*env)->ExceptionCheck(env);
}

static int check_progress(int percent, const WebPPicture* picture) {
  (void)percent;
  Sink* sink = picture->custom_ptr;
  if (sink->progress) (*sink->env)->CallVoidMethod(sink->env, sink->progress, sink->run);
  return !(*sink->env)->ExceptionCheck(sink->env);
}

static int encode(JNIEnv* env, WebPPicture* picture, jobject output, jobject progress, int lossless) {
  WebPConfig config;
  if (!WebPConfigPreset(&config, WEBP_PRESET_DRAWING, lossless ? 75.f : 95.f)) return 0;
  config.lossless = lossless;
  config.method = lossless ? 3 : 4;
  config.low_memory = 1;
  config.thread_level = 0;
  config.alpha_quality = 100;
  config.exact = 1;
  Sink sink = { .env = env, .output = output, .progress = progress };
  jclass output_class = (*env)->GetObjectClass(env, output);
  sink.write = (*env)->GetMethodID(env, output_class, "write", "([BII)V");
  (*env)->DeleteLocalRef(env, output_class);
  if (progress) {
    jclass cls = (*env)->GetObjectClass(env, progress);
    sink.run = (*env)->GetMethodID(env, cls, "run", "()V");
    (*env)->DeleteLocalRef(env, cls);
  }
  if ((*env)->ExceptionCheck(env)) return 0;
  sink.buffer = (*env)->NewByteArray(env, 65536);
  if (!sink.buffer) return 0;
  picture->writer = write_output;
  picture->custom_ptr = &sink;
  picture->progress_hook = check_progress;
  const int success = WebPEncode(&config, picture);
  (*env)->DeleteLocalRef(env, sink.buffer);
  return success;
}

JNIEXPORT jlong JNICALL Java_com_hippo_ehviewer_image_WebpEncoder_create(
    JNIEnv* env, jobject self, jint width, jint height) {
  (void)env; (void)self;
  if (width <= 0 || height <= 0 || width > WEBP_MAX_DIMENSION || height > WEBP_MAX_DIMENSION) return 0;
  Frame* frame = calloc(1, sizeof(*frame));
  if (!frame) return 0;
  if (!WebPPictureInit(&frame->picture)) { free(frame); return 0; }
  frame->picture.width = width; frame->picture.height = height;
  frame->picture.colorspace = WEBP_YUV420A;
  if (!WebPPictureAlloc(&frame->picture)) { WebPPictureFree(&frame->picture); free(frame); return 0; }
  return (jlong)(intptr_t)frame;
}

JNIEXPORT jboolean JNICALL Java_com_hippo_ehviewer_image_WebpEncoder_append(
    JNIEnv* env, jobject self, jlong handle, jintArray pixels, jint rows) {
  (void)self;
  Frame* frame = (Frame*)(intptr_t)handle;
  if (!frame || rows <= 0 || frame->next_y + rows > frame->picture.height ||
      (rows % 2 && frame->next_y + rows != frame->picture.height)) return JNI_FALSE;
  const int width = frame->picture.width;
  if ((*env)->GetArrayLength(env, pixels) < (jlong)width * rows) return JNI_FALSE;
  uint8_t* rgba = malloc((size_t)width * rows * 4);
  if (!rgba) return JNI_FALSE;
  // getPixels returns straight ARGB, so no premultiplied-alpha colour leaks into the encoder.
  (*env)->GetIntArrayRegion(env, pixels, 0, width * rows, (jint*)rgba);
  if ((*env)->ExceptionCheck(env)) { free(rgba); return JNI_FALSE; }
  for (size_t i = 0; i < (size_t)width * rows; ++i) {
    uint32_t argb; memcpy(&argb, rgba + i * 4, 4);
    rgba[i*4] = (uint8_t)(argb >> 16); rgba[i*4+1] = (uint8_t)(argb >> 8);
    rgba[i*4+2] = (uint8_t)argb; rgba[i*4+3] = (uint8_t)(argb >> 24);
  }
  WebPPicture band;
  if (!WebPPictureInit(&band)) { free(rgba); return JNI_FALSE; }
  band.width = width; band.height = rows; band.colorspace = WEBP_YUV420A;
  int success = WebPPictureImportRGBA(&band, rgba, width * 4);
  free(rgba);
  if (success) {
    WebPPicture* dst = &frame->picture;
    for (int y = 0; y < rows; ++y) {
      memcpy(dst->y + (frame->next_y+y)*dst->y_stride, band.y+y*band.y_stride, width);
      if (band.a) memcpy(dst->a + (frame->next_y+y)*dst->a_stride, band.a+y*band.a_stride, width);
      else memset(dst->a + (frame->next_y+y)*dst->a_stride, 255, width);
    }
    for (int y = 0; y < (rows+1)/2; ++y) {
      memcpy(dst->u + (frame->next_y/2+y)*dst->uv_stride, band.u+y*band.uv_stride, (width+1)/2);
      memcpy(dst->v + (frame->next_y/2+y)*dst->uv_stride, band.v+y*band.uv_stride, (width+1)/2);
    }
    frame->next_y += rows;
  }
  WebPPictureFree(&band);
  return success;
}

JNIEXPORT jboolean JNICALL Java_com_hippo_ehviewer_image_WebpEncoder_finish(
    JNIEnv* env, jobject self, jlong handle, jobject output, jobject progress) {
  (void)self;
  Frame* frame = (Frame*)(intptr_t)handle;
  return frame && frame->next_y == frame->picture.height && encode(env, &frame->picture, output, progress, 0);
}

JNIEXPORT void JNICALL Java_com_hippo_ehviewer_image_WebpEncoder_release(
    JNIEnv* env, jobject self, jlong handle) {
  (void)env; (void)self;
  Frame* frame = (Frame*)(intptr_t)handle;
  if (frame) { WebPPictureFree(&frame->picture); free(frame); }
}

JNIEXPORT jboolean JNICALL Java_com_hippo_ehviewer_image_WebpEncoder_nativeWriteLossless(
    JNIEnv* env, jobject self, jobject bitmap, jobject output) {
  (void)self;
  AndroidBitmapInfo info; void* pixels = NULL;
  if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
      info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      info.width > WEBP_MAX_DIMENSION || info.height > WEBP_MAX_DIMENSION) return JNI_FALSE;
  // AndroidBitmapInfo.flags only describes alpha on API 30+. Query the Bitmap on older OSes too.
  jclass bitmap_class = (*env)->GetObjectClass(env, bitmap);
  jmethodID is_premultiplied = (*env)->GetMethodID(env, bitmap_class, "isPremultiplied", "()Z");
  (*env)->DeleteLocalRef(env, bitmap_class);
  if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
  const int premultiplied = (*env)->CallBooleanMethod(env, bitmap, is_premultiplied);
  if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
  WebPPicture picture;
  if (!WebPPictureInit(&picture)) return JNI_FALSE;
  picture.width = info.width; picture.height = info.height; picture.use_argb = 1;
  if (!WebPPictureAlloc(&picture)) { WebPPictureFree(&picture); return JNI_FALSE; }
  if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    WebPPictureFree(&picture); return JNI_FALSE;
  }
  for (uint32_t y = 0; y < info.height; ++y) {
    const uint8_t* row = (const uint8_t*)pixels + y * info.stride;
    for (uint32_t x = 0; x < info.width; ++x) {
      unsigned r = row[4*x], g = row[4*x+1], b = row[4*x+2], a = row[4*x+3];
      if (premultiplied && a && a < 255) {
        // Android's WebP decoder premultiplies with floor, unlike PNG's rounding.
        // Choose the inverse that reconstructs the original stored premultiplied pixel.
        r = (r*255+a-1)/a; g = (g*255+a-1)/a; b = (b*255+a-1)/a;
        if (r > 255) r = 255; if (g > 255) g = 255; if (b > 255) b = 255;
      }
      picture.argb[y*picture.argb_stride+x] = (a<<24) | (r<<16) | (g<<8) | b;
    }
  }
  AndroidBitmap_unlockPixels(env, bitmap);
  const int success = encode(env, &picture, output, NULL, 1);
  WebPPictureFree(&picture);
  return success;
}

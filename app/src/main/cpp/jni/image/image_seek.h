#ifndef IMAGE_IMAGE_SEEK_H
#define IMAGE_IMAGE_SEEK_H

#include <stdbool.h>
#include <stddef.h>
#include <limits.h>

// Cancellation is checked between frames, never while holding the pixel mutex.
typedef bool (*ImageSeekCancelled)(void* context);
#define IMAGE_SEEK_FAILED (-1)
#define IMAGE_SEEK_CANCELLED (-2)

typedef struct ImageSeekCacheEntry ImageSeekCacheEntry;

// Zero-initialized per decoder; storage is shared under a process-wide budget.
typedef struct {
  ImageSeekCacheEntry* head;
} ImageSeekCache;

#define IMAGE_SEEK_CACHE_MISS UINT_MAX

void image_seek_cache_store(ImageSeekCache* cache, unsigned int frame,
                            const void* pixels, const void* backup, size_t canvas_size);
// Copies the nearest complete state in [first, target]. A non-NULL backup
// argument also restores (or clears) GIF's DISPOSE_PREVIOUS state.
unsigned int image_seek_cache_restore(ImageSeekCache* cache,
                                      unsigned int first, unsigned int target,
                                      void* pixels, void** backup, size_t canvas_size);
size_t image_seek_cache_size(ImageSeekCache* cache);
void image_seek_cache_clear(ImageSeekCache* cache);

#endif

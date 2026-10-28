#include "image_seek.h"

#include <pthread.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// Resource limits apply across all decoders, including neighbouring pages.
#define SEEK_CACHE_BYTES (32u * 1024u * 1024u)
#define SEEK_CACHE_ENTRIES 128u

struct ImageSeekCacheEntry {
  ImageSeekCache* owner;
  ImageSeekCacheEntry* owner_previous;
  ImageSeekCacheEntry* owner_next;
  ImageSeekCacheEntry* previous;
  ImageSeekCacheEntry* next;
  unsigned int frame;
  size_t canvas_size;
  size_t allocation_size;
  bool has_backup;
  // The allocation ends with one RGBA canvas and, optionally, a GIF backup.
};

static pthread_mutex_t cache_mutex = PTHREAD_MUTEX_INITIALIZER;
static ImageSeekCacheEntry* oldest;
static ImageSeekCacheEntry* newest;
static size_t cache_bytes;
static unsigned int cache_entries;

static void detach_lru(ImageSeekCacheEntry* entry) {
  if (entry->previous != NULL) entry->previous->next = entry->next;
  else oldest = entry->next;
  if (entry->next != NULL) entry->next->previous = entry->previous;
  else newest = entry->previous;
}

static void make_newest(ImageSeekCacheEntry* entry) {
  entry->previous = newest;
  entry->next = NULL;
  if (newest != NULL) newest->next = entry;
  else oldest = entry;
  newest = entry;
}

static void remove_entry(ImageSeekCacheEntry* entry) {
  detach_lru(entry);
  if (entry->owner_previous != NULL) entry->owner_previous->owner_next = entry->owner_next;
  else entry->owner->head = entry->owner_next;
  if (entry->owner_next != NULL) entry->owner_next->owner_previous = entry->owner_previous;
  cache_bytes -= entry->allocation_size;
  --cache_entries;
  free(entry);
}

void image_seek_cache_store(ImageSeekCache* cache, unsigned int frame,
                            const void* pixels, const void* backup, size_t canvas_size) {
  const size_t copies = backup != NULL ? 2u : 1u;
  if (pixels == NULL || canvas_size == 0
      || canvas_size > (SIZE_MAX - sizeof(ImageSeekCacheEntry)) / copies) return;
  const size_t size = sizeof(ImageSeekCacheEntry) + canvas_size * copies;
  if (size > SEEK_CACHE_BYTES) return;
  pthread_mutex_lock(&cache_mutex);
  for (ImageSeekCacheEntry* entry = cache->head; entry != NULL; entry = entry->owner_next) {
    if (entry->frame == frame) {
      // A complete composition at a given frame is deterministic. Avoid
      // copying the same multi-megabyte canvas at consecutive seek steps.
      detach_lru(entry);
      make_newest(entry);
      pthread_mutex_unlock(&cache_mutex);
      return;
    }
  }
  while (oldest != NULL && (cache_bytes + size > SEEK_CACHE_BYTES
                            || cache_entries >= SEEK_CACHE_ENTRIES)) remove_entry(oldest);
  ImageSeekCacheEntry* entry = (ImageSeekCacheEntry*) malloc(size);
  if (entry != NULL) {
    entry->owner = cache;
    entry->owner_previous = NULL;
    entry->owner_next = cache->head;
    if (cache->head != NULL) cache->head->owner_previous = entry;
    cache->head = entry;
    entry->frame = frame;
    entry->canvas_size = canvas_size;
    entry->allocation_size = size;
    entry->has_backup = backup != NULL;
    unsigned char* data = (unsigned char*) (entry + 1);
    memcpy(data, pixels, canvas_size);
    if (backup != NULL) memcpy(data + canvas_size, backup, canvas_size);
    make_newest(entry);
    cache_bytes += size;
    ++cache_entries;
  }
  pthread_mutex_unlock(&cache_mutex);
}

unsigned int image_seek_cache_restore(ImageSeekCache* cache,
                                      unsigned int first, unsigned int target,
                                      void* pixels, void** backup, size_t canvas_size) {
  if (first > target || pixels == NULL) return IMAGE_SEEK_CACHE_MISS;
  pthread_mutex_lock(&cache_mutex);
  ImageSeekCacheEntry* best = NULL;
  for (ImageSeekCacheEntry* entry = cache->head; entry != NULL; entry = entry->owner_next) {
    if (entry->canvas_size == canvas_size && entry->frame >= first && entry->frame <= target
        && (best == NULL || entry->frame > best->frame)) best = entry;
  }
  if (best == NULL) {
    pthread_mutex_unlock(&cache_mutex);
    return IMAGE_SEEK_CACHE_MISS;
  }
  if (backup != NULL && best->has_backup && *backup == NULL) {
    *backup = malloc(canvas_size);
    if (*backup == NULL) {
      pthread_mutex_unlock(&cache_mutex);
      return IMAGE_SEEK_CACHE_MISS;
    }
  }
  const unsigned char* data = (const unsigned char*) (best + 1);
  memcpy(pixels, data, canvas_size);
  if (backup != NULL) {
    if (best->has_backup) memcpy(*backup, data + canvas_size, canvas_size);
    else {
      free(*backup);
      *backup = NULL;
    }
  }
  const unsigned int frame = best->frame;
  detach_lru(best);
  make_newest(best);
  pthread_mutex_unlock(&cache_mutex);
  return frame;
}

size_t image_seek_cache_size(ImageSeekCache* cache) {
  size_t bytes = 0;
  pthread_mutex_lock(&cache_mutex);
  for (ImageSeekCacheEntry* entry = cache->head; entry != NULL; entry = entry->owner_next) {
    bytes += entry->allocation_size;
  }
  pthread_mutex_unlock(&cache_mutex);
  return bytes;
}

void image_seek_cache_clear(ImageSeekCache* cache) {
  pthread_mutex_lock(&cache_mutex);
  while (cache->head != NULL) remove_entry(cache->head);
  pthread_mutex_unlock(&cache_mutex);
}

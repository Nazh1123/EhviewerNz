#ifndef IMAGE_IMAGE_SEEK_H
#define IMAGE_IMAGE_SEEK_H

#include <stdbool.h>

// Cancellation is checked between frames, never while holding the pixel mutex.
typedef bool (*ImageSeekCancelled)(void* context);
#define IMAGE_SEEK_FAILED (-1)
#define IMAGE_SEEK_CANCELLED (-2)

#endif

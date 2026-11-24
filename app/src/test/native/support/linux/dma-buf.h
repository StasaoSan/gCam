#pragma once
#include <stdint.h>
struct dma_buf_sync { uint64_t flags; };
#define DMA_BUF_SYNC_READ 1
#define DMA_BUF_SYNC_START 0
#define DMA_BUF_SYNC_END 4
#define DMA_BUF_IOCTL_SYNC 0xc0086200

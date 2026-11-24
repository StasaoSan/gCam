#include "native_sample_pool.h"

#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>

#define SAMPLE_ALIGNMENT ((size_t)64)
#define SAMPLE_SLAB_BYTES ((size_t)4 * 1024 * 1024)
#define SAMPLE_WARM_BYTES ((size_t)8 * 1024 * 1024)

typedef struct SampleRange {
    size_t offset;
    size_t capacity;
    size_t payload;
    int64_t lease;
    struct SampleRange *next;
} SampleRange;

typedef struct SampleSlab {
    unsigned char *data;
    size_t capacity;
    size_t live_leases;
    SampleRange *ranges;
    struct SampleSlab *next;
} SampleSlab;

typedef struct SamplePool {
    int64_t id;
    size_t budget;
    size_t allocated;
    size_t leased_capacity;
    size_t leased_payload;
    size_t live_leases;
    pthread_mutex_t mutex;
    SampleSlab *slabs;
    struct SamplePool *next;
} SamplePool;

/* Registry locking precedes pool locking, including destruction. Handles are
 * monotonically issued IDs, so stale Java longs never become native pointers. */
static pthread_mutex_t registry_mutex = PTHREAD_MUTEX_INITIALIZER;
static SamplePool *registry;
static atomic_uint_fast64_t next_pool = ATOMIC_VAR_INIT(1);
static atomic_uint_fast64_t next_lease = ATOMIC_VAR_INIT(1);

static int64_t issue_id(atomic_uint_fast64_t *source) {
    uint_fast64_t value = atomic_load_explicit(source, memory_order_relaxed);
    while (value <= INT64_MAX) {
        if (atomic_compare_exchange_weak_explicit(source, &value, value + 1,
                memory_order_relaxed, memory_order_relaxed)) return (int64_t)value;
    }
    return 0;
}

static bool valid_bytes(int64_t bytes) {
    return bytes >= 0 && (uint64_t)bytes <= SIZE_MAX;
}

static SamplePool *lock_pool(int64_t id) {
    if (id <= 0) return NULL;
    pthread_mutex_lock(&registry_mutex);
    SamplePool *pool = registry;
    while (pool && pool->id != id) pool = pool->next;
    if (pool) pthread_mutex_lock(&pool->mutex);
    pthread_mutex_unlock(&registry_mutex);
    return pool;
}

static void free_slab(SampleSlab *slab) {
    SampleRange *range = slab->ranges;
    while (range) {
        SampleRange *next = range->next;
        free(range);
        range = next;
    }
    free(slab->data);
    free(slab);
}

/* keep_empty is a warm-cache ceiling. Shrinking additionally retires empty
 * slabs until the new budget is met; live slabs are never moved or freed. */
static void trim_empty(SamplePool *pool, size_t keep_empty) {
    size_t empty = 0;
    for (SampleSlab *s = pool->slabs; s; s = s->next) {
        if (!s->live_leases) empty += s->capacity;
    }
    SampleSlab **link = &pool->slabs;
    while (*link) {
        SampleSlab *slab = *link;
        if (!slab->live_leases && (empty > keep_empty || pool->allocated > pool->budget)) {
            *link = slab->next;
            empty -= slab->capacity;
            pool->allocated -= slab->capacity;
            free_slab(slab);
        } else {
            link = &slab->next;
        }
    }
}

int64_t gcam_sample_pool_create(int64_t max_bytes) {
    if (!valid_bytes(max_bytes)) return 0;
    SamplePool *pool = calloc(1, sizeof(*pool));
    if (!pool) return 0;
    pool->id = issue_id(&next_pool);
    if (!pool->id || pthread_mutex_init(&pool->mutex, NULL) != 0) {
        free(pool);
        return 0;
    }
    pool->budget = (size_t)max_bytes;
    pthread_mutex_lock(&registry_mutex);
    pool->next = registry;
    registry = pool;
    pthread_mutex_unlock(&registry_mutex);
    return pool->id;
}

static SampleSlab *allocate_slab(size_t capacity) {
    SampleSlab *slab = calloc(1, sizeof(*slab));
    SampleRange *range = calloc(1, sizeof(*range));
    void *data = NULL;
    if (!slab || !range || posix_memalign(&data, SAMPLE_ALIGNMENT, capacity) != 0) {
        free(slab);
        free(range);
        return NULL;
    }
    slab->data = data;
    slab->capacity = capacity;
    slab->ranges = range;
    range->capacity = capacity;
    return slab;
}

int64_t gcam_sample_pool_acquire(int64_t id, int channel, int64_t size) {
    /* JNI consumers use ByteBuffer's signed-int capacity. Reject overflow
     * before alignment or any allocator call, including on 32-bit builds. */
    if (channel < 0 || channel > 3 || size <= 0 || size > INT32_MAX ||
            !valid_bytes(size) || (uint64_t)size > SIZE_MAX - (SAMPLE_ALIGNMENT - 1)) return 0;
    size_t reserved = ((size_t)size + SAMPLE_ALIGNMENT - 1) & ~(SAMPLE_ALIGNMENT - 1);
    SamplePool *pool = lock_pool(id);
    if (!pool) return 0;
    int64_t lease = 0;
    if (pool->allocated > pool->budget || reserved > pool->budget) goto done;
    SampleSlab *best_slab = NULL;
    SampleRange *best = NULL;
    for (SampleSlab *slab = pool->slabs; slab; slab = slab->next) {
        for (SampleRange *range = slab->ranges; range; range = range->next) {
            if (!range->lease && range->capacity >= reserved &&
                    (!best || range->capacity < best->capacity)) {
                best = range;
                best_slab = slab;
            }
        }
    }
    if (!best) {
        /* Empty cached slabs may prevent a larger contiguous allocation. */
        size_t remaining = pool->budget - pool->allocated;
        if (remaining < reserved) {
            trim_empty(pool, 0);
            remaining = pool->budget - pool->allocated;
        }
        if (remaining < reserved) goto done;
        size_t capacity = reserved > SAMPLE_SLAB_BYTES ? reserved : SAMPLE_SLAB_BYTES;
        if (capacity > remaining) capacity = remaining & ~(SAMPLE_ALIGNMENT - 1);
        best_slab = allocate_slab(capacity);
        if (!best_slab) goto done;
        best_slab->next = pool->slabs;
        pool->slabs = best_slab;
        pool->allocated += capacity;
        best = best_slab->ranges;
    }
    SampleRange *tail = NULL;
    if (best->capacity > reserved) {
        tail = calloc(1, sizeof(*tail));
        if (!tail) goto done;
        tail->offset = best->offset + reserved;
        tail->capacity = best->capacity - reserved;
        tail->next = best->next;
    }
    lease = issue_id(&next_lease);
    if (!lease) { free(tail); goto done; }
    if (tail) { best->next = tail; best->capacity = reserved; }
    best->lease = lease;
    best->payload = (size_t)size;
    best_slab->live_leases++;
    pool->live_leases++;
    pool->leased_capacity += reserved;
    pool->leased_payload += (size_t)size;
done:
    pthread_mutex_unlock(&pool->mutex);
    return lease;
}

void *gcam_sample_pool_buffer(int64_t id, int64_t lease, size_t *size) {
    if (size) *size = 0;
    if (lease <= 0) return NULL;
    SamplePool *pool = lock_pool(id);
    if (!pool) return NULL;
    void *data = NULL;
    for (SampleSlab *slab = pool->slabs; slab && !data; slab = slab->next) {
        for (SampleRange *range = slab->ranges; range; range = range->next) {
            if (range->lease == lease) {
                data = slab->data + range->offset;
                if (size) *size = range->payload;
                break;
            }
        }
    }
    pthread_mutex_unlock(&pool->mutex);
    return data;
}

bool gcam_sample_pool_release(int64_t id, int64_t lease) {
    if (lease <= 0) return false;
    SamplePool *pool = lock_pool(id);
    if (!pool) return false;
    bool released = false;
    for (SampleSlab *slab = pool->slabs; slab && !released; slab = slab->next) {
        SampleRange *previous = NULL;
        for (SampleRange *range = slab->ranges; range; previous = range, range = range->next) {
            if (range->lease != lease) continue;
            pool->leased_capacity -= range->capacity;
            pool->leased_payload -= range->payload;
            pool->live_leases--;
            slab->live_leases--;
            range->lease = 0;
            range->payload = 0;
            if (range->next && !range->next->lease) {
                SampleRange *next = range->next;
                range->capacity += next->capacity;
                range->next = next->next;
                free(next);
            }
            if (previous && !previous->lease) {
                previous->capacity += range->capacity;
                previous->next = range->next;
                free(range);
            }
            released = true;
            break;
        }
    }
    if (released) trim_empty(pool, SAMPLE_WARM_BYTES);
    pthread_mutex_unlock(&pool->mutex);
    return released;
}

void gcam_sample_pool_set_budget(int64_t id, int64_t max_bytes) {
    if (!valid_bytes(max_bytes)) return;
    SamplePool *pool = lock_pool(id);
    if (!pool) return;
    pool->budget = (size_t)max_bytes;
    trim_empty(pool, SAMPLE_WARM_BYTES);
    pthread_mutex_unlock(&pool->mutex);
}

void gcam_sample_pool_trim(int64_t id, int64_t keep_free_bytes) {
    if (!valid_bytes(keep_free_bytes)) return;
    SamplePool *pool = lock_pool(id);
    if (!pool) return;
    trim_empty(pool, (size_t)keep_free_bytes);
    pthread_mutex_unlock(&pool->mutex);
}

bool gcam_sample_pool_snapshot(int64_t id, int64_t values[5]) {
    if (!values) return false;
    SamplePool *pool = lock_pool(id);
    if (!pool) return false;
    values[0] = (int64_t)pool->budget;
    values[1] = (int64_t)pool->allocated;
    values[2] = (int64_t)pool->leased_payload;
    values[3] = (int64_t)pool->live_leases;
    values[4] = (int64_t)(pool->allocated - pool->leased_capacity);
    pthread_mutex_unlock(&pool->mutex);
    return true;
}

bool gcam_sample_pool_destroy(int64_t id) {
    if (id <= 0) return false;
    pthread_mutex_lock(&registry_mutex);
    SamplePool **link = &registry;
    while (*link && (*link)->id != id) link = &(*link)->next;
    SamplePool *pool = *link;
    if (!pool) { pthread_mutex_unlock(&registry_mutex); return false; }
    pthread_mutex_lock(&pool->mutex);
    if (pool->live_leases) {
        pthread_mutex_unlock(&pool->mutex);
        pthread_mutex_unlock(&registry_mutex);
        return false;
    }
    *link = pool->next;
    pthread_mutex_unlock(&registry_mutex);
    SampleSlab *slab = pool->slabs;
    while (slab) {
        SampleSlab *next = slab->next;
        free_slab(slab);
        slab = next;
    }
    pthread_mutex_unlock(&pool->mutex);
    pthread_mutex_destroy(&pool->mutex);
    free(pool);
    return true;
}

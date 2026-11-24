#ifndef GCAM_NATIVE_SAMPLE_POOL_H
#define GCAM_NATIVE_SAMPLE_POOL_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Opaque positive IDs, never raw addresses. All calls are thread safe. */
int64_t gcam_sample_pool_create(int64_t max_bytes);
int64_t gcam_sample_pool_acquire(int64_t pool, int channel, int64_t size);
/* The returned memory belongs to the lease until its owner releases it. */
void *gcam_sample_pool_buffer(int64_t pool, int64_t lease, size_t *size);
bool gcam_sample_pool_release(int64_t pool, int64_t lease);
void gcam_sample_pool_set_budget(int64_t pool, int64_t max_bytes);
/* Only completely empty slabs can be trimmed; active views stay valid. */
void gcam_sample_pool_trim(int64_t pool, int64_t keep_free_bytes);
/* budget, allocated capacity, leased payload, live leases, free capacity */
bool gcam_sample_pool_snapshot(int64_t pool, int64_t values[5]);
/* A busy pool remains registered and valid when destruction returns false. */
bool gcam_sample_pool_destroy(int64_t pool);

#ifdef __cplusplus
}
#endif
#endif

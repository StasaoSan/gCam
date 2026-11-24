#include "../../main/cpp/native_sample_pool.h"

#include <inttypes.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>

#define MIB INT64_C(1048576)
#define CHECK(expr) do { if (!(expr)) { \
    fprintf(stderr, "%s:%d: %s\n", __func__, __LINE__, #expr); return false; \
} } while (0)

/* Each assertion protects allocator behavior, not allocator implementation. */
static bool lazy_and_accounted(void) {
    int64_t pool = gcam_sample_pool_create(8 * MIB), s[5];
    CHECK(pool > 0);
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[0] == 8 * MIB && s[1] == 0 && s[2] == 0 && s[3] == 0 && s[4] == 0);
    int64_t lease = gcam_sample_pool_acquire(pool, 0, 65);
    CHECK(lease > 0);
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[1] == 4 * MIB && s[2] == 65 && s[3] == 1 && s[4] == 4 * MIB - 128);
    CHECK(gcam_sample_pool_release(pool, lease));
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool budget_counts_free_and_busy_capacity(void) {
    int64_t pool = gcam_sample_pool_create(8 * MIB), s[5];
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 4 * MIB);
    int64_t b = gcam_sample_pool_acquire(pool, 1, 4 * MIB);
    CHECK(a > 0 && b > 0);
    CHECK(gcam_sample_pool_acquire(pool, 2, 1) == 0);
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[1] == 8 * MIB && s[2] == 8 * MIB && s[3] == 2 && s[4] == 0);
    CHECK(gcam_sample_pool_release(pool, a));
    a = gcam_sample_pool_acquire(pool, 3, 4 * MIB);
    CHECK(a > 0);
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[1] == 8 * MIB);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, b));
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool disjoint_slices_and_exact_payload(void) {
    int64_t pool = gcam_sample_pool_create(4 * MIB);
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 65);
    int64_t b = gcam_sample_pool_acquire(pool, 1, 129);
    size_t na = 0, nb = 0;
    unsigned char *pa = gcam_sample_pool_buffer(pool, a, &na);
    unsigned char *pb = gcam_sample_pool_buffer(pool, b, &nb);
    CHECK(pa && pb && na == 65 && nb == 129);
    CHECK((uintptr_t)pa % 64 == 0 && (uintptr_t)pb % 64 == 0);
    memset(pa, 0x37, na);
    memset(pb, 0xc2, nb);
    CHECK(pa[0] == 0x37 && pa[64] == 0x37 && pb[0] == 0xc2 && pb[128] == 0xc2);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, b));
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool reuses_and_coalesces_free_ranges(void) {
    int64_t pool = gcam_sample_pool_create(4 * MIB), s[5];
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, MIB);
    int64_t b = gcam_sample_pool_acquire(pool, 0, MIB);
    int64_t c = gcam_sample_pool_acquire(pool, 0, 2 * MIB);
    CHECK(a && b && c);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, b));
    a = gcam_sample_pool_acquire(pool, 1, 2 * MIB);
    CHECK(a > 0 && gcam_sample_pool_snapshot(pool, s) && s[1] == 4 * MIB);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, c));
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool best_fit_preserves_large_free_range(void) {
    int64_t pool = gcam_sample_pool_create(4 * MIB);
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 2 * MIB);
    int64_t separator = gcam_sample_pool_acquire(pool, 0, MIB / 2);
    int64_t b = gcam_sample_pool_acquire(pool, 0, MIB);
    int64_t tail = gcam_sample_pool_acquire(pool, 0, MIB / 2);
    CHECK(a && separator && b && tail);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, b));
    b = gcam_sample_pool_acquire(pool, 1, MIB);
    a = gcam_sample_pool_acquire(pool, 1, 2 * MIB);
    CHECK(a && b);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_release(pool, b));
    CHECK(gcam_sample_pool_release(pool, separator) && gcam_sample_pool_release(pool, tail));
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool shrink_never_invalidates_inflight_bytes(void) {
    int64_t pool = gcam_sample_pool_create(12 * MIB), s[5];
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 4 * MIB);
    int64_t b = gcam_sample_pool_acquire(pool, 1, 4 * MIB);
    CHECK(a && b);
    size_t size;
    unsigned char *data = gcam_sample_pool_buffer(pool, a, &size);
    CHECK(data && size == 4 * MIB);
    memset(data, 0x56, size);
    CHECK(gcam_sample_pool_release(pool, b));
    gcam_sample_pool_set_budget(pool, MIB);
    gcam_sample_pool_trim(pool, 0);
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[0] == MIB && s[1] == 4 * MIB && s[2] == 4 * MIB && s[3] == 1);
    CHECK(gcam_sample_pool_acquire(pool, 1, 1) == 0);
    CHECK(data[0] == 0x56 && data[size - 1] == 0x56);
    CHECK(!gcam_sample_pool_destroy(pool));
    CHECK(gcam_sample_pool_release(pool, a));
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[1] == 0 && s[3] == 0);
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool trims_only_empty_slabs_and_retains_small_warm_pool(void) {
    int64_t pool = gcam_sample_pool_create(16 * MIB), leases[4], s[5];
    CHECK(pool > 0);
    for (int i = 0; i < 4; ++i) CHECK((leases[i] = gcam_sample_pool_acquire(pool, i, 4 * MIB)) > 0);
    for (int i = 0; i < 4; ++i) CHECK(gcam_sample_pool_release(pool, leases[i]));
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[1] <= 8 * MIB && s[1] >= 4 * MIB);
    gcam_sample_pool_trim(pool, 0);
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[1] == 0);
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool invalid_ids_arguments_and_double_returns_are_safe(void) {
    int64_t s[5], pool = gcam_sample_pool_create(MIB), other = gcam_sample_pool_create(MIB);
    CHECK(pool > 0 && other > 0 && pool != other);
    CHECK(gcam_sample_pool_create(-1) == 0);
    CHECK(gcam_sample_pool_acquire(pool, -1, 1) == 0);
    CHECK(gcam_sample_pool_acquire(pool, 4, 1) == 0);
    CHECK(gcam_sample_pool_acquire(pool, 0, 0) == 0);
    CHECK(gcam_sample_pool_acquire(pool, 0, -1) == 0);
    CHECK(gcam_sample_pool_acquire(pool, 0, INT64_MAX) == 0);
    CHECK(gcam_sample_pool_acquire(-1, 0, 1) == 0);
    gcam_sample_pool_set_budget(pool, -1);
    gcam_sample_pool_trim(pool, -1);
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[0] == MIB && s[1] == 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 1);
    CHECK(a > 0 && !gcam_sample_pool_release(other, a));
    size_t size = 99;
    CHECK(!gcam_sample_pool_buffer(other, a, &size) && size == 0);
    CHECK(gcam_sample_pool_release(pool, a));
    CHECK(!gcam_sample_pool_release(pool, a) && !gcam_sample_pool_buffer(pool, a, &size));
    int64_t replacement = gcam_sample_pool_acquire(pool, 0, 1);
    CHECK(replacement > 0 && replacement != a);
    CHECK(!gcam_sample_pool_release(pool, a));
    CHECK(gcam_sample_pool_buffer(pool, replacement, &size) && size == 1);
    CHECK(gcam_sample_pool_release(pool, replacement));
    CHECK(gcam_sample_pool_destroy(pool));
    CHECK(!gcam_sample_pool_release(pool, a) && !gcam_sample_pool_destroy(pool));
    CHECK(!gcam_sample_pool_snapshot(pool, s) && !gcam_sample_pool_buffer(pool, a, &size));
    CHECK(gcam_sample_pool_destroy(other));
    return true;
}

static bool large_frames_and_small_budget_respect_full_cap(void) {
    int64_t pool = gcam_sample_pool_create(9 * MIB), s[5];
    CHECK(pool > 0);
    int64_t a = gcam_sample_pool_acquire(pool, 0, 6 * MIB + 1);
    CHECK(a > 0 && gcam_sample_pool_snapshot(pool, s));
    CHECK(s[1] == 6 * MIB + 64 && s[2] == 6 * MIB + 1);
    CHECK(gcam_sample_pool_acquire(pool, 1, 3 * MIB) == 0);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_destroy(pool));
    pool = gcam_sample_pool_create(65);
    CHECK(pool > 0);
    CHECK(gcam_sample_pool_acquire(pool, 0, 65) == 0);
    a = gcam_sample_pool_acquire(pool, 0, 64);
    CHECK(a > 0 && gcam_sample_pool_snapshot(pool, s) && s[1] == 64);
    CHECK(gcam_sample_pool_release(pool, a) && gcam_sample_pool_destroy(pool));
    pool = gcam_sample_pool_create(0);
    CHECK(pool > 0 && gcam_sample_pool_acquire(pool, 0, 1) == 0);
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

struct thread_case { int64_t pool; int channel; bool ok; };
static void *thread_work(void *arg) {
    struct thread_case *t = arg;
    t->ok = true;
    for (int i = 0; i < 3000; ++i) {
        const int size = 701 + (i % 97);
        int64_t lease = gcam_sample_pool_acquire(t->pool, t->channel, size);
        if (!lease) { t->ok = false; break; }
        size_t actual = 0;
        unsigned char *data = gcam_sample_pool_buffer(t->pool, lease, &actual);
        if (!data || actual != (size_t)size) { t->ok = false; break; }
        memset(data, t->channel + 1, actual);
        if (data[actual - 1] != t->channel + 1 || !gcam_sample_pool_release(t->pool, lease)) {
            t->ok = false; break;
        }
    }
    return NULL;
}
static bool concurrent_real_allocations_stay_bounded(void) {
    int64_t pool = gcam_sample_pool_create(4 * MIB), s[5];
    CHECK(pool > 0);
    pthread_t threads[4]; struct thread_case cases[4];
    for (int i = 0; i < 4; ++i) {
        cases[i] = (struct thread_case){pool, i, false};
        CHECK(pthread_create(&threads[i], NULL, thread_work, &cases[i]) == 0);
    }
    for (int i = 0; i < 4; ++i) { CHECK(pthread_join(threads[i], NULL) == 0); CHECK(cases[i].ok); }
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[1] <= 4 * MIB && s[2] == 0 && s[3] == 0 && s[4] == s[1]);
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

static bool full_500_mib_budget_is_a_hard_slab_cap(void) {
    int64_t pool = gcam_sample_pool_create(500 * MIB), s[5], leases[125];
    CHECK(pool > 0);
    for (int i = 0; i < 125; ++i) {
        leases[i] = gcam_sample_pool_acquire(pool, i % 4, 4 * MIB);
        CHECK(leases[i] > 0);
        size_t size = 0;
        unsigned char *data = gcam_sample_pool_buffer(pool, leases[i], &size);
        CHECK(data && size == 4 * MIB);
        /* Touch real allocated pages, rather than only reserving addresses. */
        memset(data, i, size);
    }
    CHECK(gcam_sample_pool_snapshot(pool, s));
    CHECK(s[0] == 500 * MIB && s[1] == 500 * MIB && s[2] == 500 * MIB && s[3] == 125);
    CHECK(gcam_sample_pool_acquire(pool, 0, 1) == 0);
    for (int i = 0; i < 125; ++i) {
        size_t size = 0;
        unsigned char *data = gcam_sample_pool_buffer(pool, leases[i], &size);
        CHECK(data && data[0] == (unsigned char)i && data[size - 1] == (unsigned char)i);
        CHECK(gcam_sample_pool_release(pool, leases[i]));
    }
    CHECK(gcam_sample_pool_snapshot(pool, s) && s[1] <= 8 * MIB && s[2] == 0 && s[3] == 0);
    CHECK(gcam_sample_pool_destroy(pool));
    return true;
}

struct snapshot_case { int64_t pool; bool ok; };
static void *snapshot_until_stale(void *arg) {
    struct snapshot_case *t = arg;
    t->ok = true;
    for (int i = 0; i < 10000; ++i) {
        int64_t s[5];
        bool found = gcam_sample_pool_snapshot(t->pool, s);
        if (found && (s[0] != 4 * MIB || s[1] != 0 || s[3] != 0)) t->ok = false;
    }
    return NULL;
}
static bool destruction_racing_stale_operations_is_safe(void) {
    int64_t pool = gcam_sample_pool_create(4 * MIB);
    CHECK(pool > 0);
    struct snapshot_case t = {pool, false};
    pthread_t thread;
    CHECK(pthread_create(&thread, NULL, snapshot_until_stale, &t) == 0);
    CHECK(gcam_sample_pool_destroy(pool));
    int64_t replacement = gcam_sample_pool_create(4 * MIB);
    CHECK(replacement > 0 && replacement != pool);
    CHECK(pthread_join(thread, NULL) == 0 && t.ok);
    CHECK(gcam_sample_pool_acquire(pool, 0, 1) == 0);
    CHECK(gcam_sample_pool_destroy(replacement));
    return true;
}

int main(void) {
    int failures = 0;
#define RUN(test) do { bool ok = test(); printf("%s %s\n", ok ? "PASS" : "FAIL", #test); failures += !ok; } while (0)
    RUN(lazy_and_accounted);
    RUN(budget_counts_free_and_busy_capacity);
    RUN(disjoint_slices_and_exact_payload);
    RUN(reuses_and_coalesces_free_ranges);
    RUN(best_fit_preserves_large_free_range);
    RUN(shrink_never_invalidates_inflight_bytes);
    RUN(trims_only_empty_slabs_and_retains_small_warm_pool);
    RUN(invalid_ids_arguments_and_double_returns_are_safe);
    RUN(large_frames_and_small_budget_respect_full_cap);
    RUN(concurrent_real_allocations_stay_bounded);
    RUN(full_500_mib_budget_is_a_hard_slab_cap);
    RUN(destruction_racing_stale_operations_is_safe);
    printf("native_sample_pool: %d failure(s)\n", failures);
    return failures ? 1 : 0;
}

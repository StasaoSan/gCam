#ifndef GCAM_RECORDER_LOAD_POLICY_H
#define GCAM_RECORDER_LOAD_POLICY_H
#include <stdint.h>

/* Cadence only: never restart a codec or change the timestamps of recorded frames. */
static inline int recorder_fps_budget(int requested, int visible_outputs,
        uint64_t now, uint64_t slow_until, uint64_t severe_until) {
    int cap = visible_outputs || now < slow_until ? 8 : 25;
    if (now < severe_until) cap = 4;
    return requested < cap ? requested : cap;
}
static inline uint64_t recorder_pressure_deadline(uint64_t now, uint64_t cost,
        uint64_t threshold, uint64_t previous) {
    uint64_t next = cost >= threshold ? now + 10000000000ull : 0;
    return next > previous ? next : previous;
}
#endif

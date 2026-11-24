#ifndef GCAM_RECORDER_LOAD_POLICY_H
#define GCAM_RECORDER_LOAD_POLICY_H
#include <stdint.h>

/* Each encoder has its own pressure history. Isolated stalls and visible previews
 * do not lower the user's recording FPS. */
typedef struct {
    unsigned slow_frames, severe_frames;
    uint64_t slow_until, severe_until;
} recorder_pressure_t;

static inline void recorder_pressure_update(recorder_pressure_t *pressure,
        uint64_t now, uint64_t encoder_wait_ns) {
    if (encoder_wait_ns < 60000000ull) {
        pressure->slow_frames = 0;
        pressure->severe_frames = 0;
        return;
    }
    if (pressure->slow_frames < 3) pressure->slow_frames++;
    if (encoder_wait_ns >= 120000000ull) {
        if (pressure->severe_frames < 3) pressure->severe_frames++;
    } else {
        pressure->severe_frames = 0;
    }
    /* Three consecutive blocked submissions earn a short recovery interval.
     * Healthy frames clear the streak; the current interval expires naturally. */
    if (pressure->slow_frames == 3) pressure->slow_until = now + 1000000000ull;
    if (pressure->severe_frames == 3) pressure->severe_until = now + 1000000000ull;
}

static inline int recorder_fps_budget(int requested, uint64_t now,
        uint64_t slow_until, uint64_t severe_until) {
    int cap = now < slow_until ? 8 : 25;
    if (now < severe_until) cap = 4;
    return requested < cap ? requested : cap;
}

/* Slots 6..9 are private gCam previews; projection/cluster consumers stay capped at 20. */
static inline int hud_output_fps_limit(int slot) {
    return slot >= 6 && slot <= 9 ? 25 : 20;
}

/* Advance the output's clock, not the arrival time of the selected source
 * frame. Skip whole missed periods after a stall while preserving its phase;
 * neither rendering cost nor a late source frame may slow the next period. */
static inline uint64_t recorder_next_due_ns(uint64_t previous_due,
        uint64_t timestamp, uint64_t interval) {
    if (!interval) return timestamp;
    if (!previous_due) return timestamp + interval;
    if (timestamp < previous_due) return previous_due;
    return timestamp + interval - (timestamp - previous_due) % interval;
}
#endif

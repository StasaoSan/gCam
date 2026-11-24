#include <assert.h>
#include <stdio.h>
#include "../../main/cpp/recorder_load_policy.h"

static unsigned delivered(const uint64_t *timestamps, unsigned count, int fps) {
    uint64_t next = 0;
    unsigned frames = 0;
    for (unsigned i = 0; i < count; i++) {
        if (next && timestamps[i] < next) continue;
        frames++;
        next = recorder_next_due_ns(next, timestamps[i], 1000000000ull / (uint64_t)fps);
        assert(next > timestamps[i]);
    }
    return frames;
}

static void accepts_twenty_of_twenty_five_without_render_time_drift(void) {
    /* Nine physical frames arrive in the first 350 ms. At 20 FPS the output
     * must accept 0, 80, 120, 160, 200, 280 and 320 ms, not every other frame. */
    const uint64_t source[] = {
        1000000000ull, 1040000000ull, 1080000000ull,
        1120000000ull, 1160000000ull, 1200000000ull,
        1240000000ull, 1280000000ull, 1320000000ull
    };
    assert(delivered(source, 9, 20) == 7);
}

static void keeps_phase_after_missing_source_frames(void) {
    /* Frames at 120/160/200 ms are unavailable. The late 240-ms frame still
     * belongs to the original 50-ms clock, so 280 ms is eligible too. Resetting
     * the deadline from 240 ms would unnecessarily discard that next frame. */
    const uint64_t source[] = {
        1000000000ull, 1040000000ull, 1080000000ull,
        1240000000ull, 1280000000ull, 1320000000ull,
        1360000000ull, 1400000000ull, 1440000000ull, 1480000000ull
    };
    assert(delivered(source, 10, 20) == 8);
    assert(recorder_next_due_ns(1100000000ull, 1240000000ull, 50000000ull) == 1250000000ull);
}

static void skips_expired_deadlines_without_replaying_a_stall(void) {
    /* A long pause must not leave an already-expired deadline that would
     * accept a burst of frames until the clock catches up. */
    assert(recorder_next_due_ns(1050000000ull, 2040000000ull, 50000000ull) == 2050000000ull);
    assert(recorder_next_due_ns(1050000000ull, 2050000000ull, 50000000ull) == 2100000000ull);
    assert(recorder_next_due_ns(0, 1030000000ull, 50000000ull) == 1080000000ull);
    assert(recorder_next_due_ns(0, 1030000000ull, 0) == 1030000000ull);
}

int main(void) {
    accepts_twenty_of_twenty_five_without_render_time_drift();
    keeps_phase_after_missing_source_frames();
    skips_expired_deadlines_without_replaying_a_stall();
    puts("PASS: 25-to-20 FPS cadence, missed-frame phase and bounded stall recovery");
    return 0;
}

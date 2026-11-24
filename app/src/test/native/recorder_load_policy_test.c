#include <assert.h>
#include "../../main/cpp/recorder_load_policy.h"

static void check_source_cadence(int fps, unsigned expected) {
    uint64_t next = 0, interval = 1000000000ull / (uint64_t)fps;
    unsigned frames = 0;
    /* Physical camera at 25 Hz, fast rendering completed before the next input.
     * Deadlines follow presentation starts, without adding render cost to the period. */
    for (unsigned input = 0; input < 250; input++) {
        uint64_t timestamp = input * 40000000ull;
        if (next && timestamp < next) continue;
        frames++;
        next = recorder_next_due_ns(next, timestamp, interval);
        assert(next > timestamp);
        assert(next <= timestamp + interval);
    }
    assert(frames == expected);
}

int main(void) {
    const uint64_t second = 1000000000ull;
    recorder_pressure_t pressure = {0};
    assert(recorder_fps_budget(15, second, 0, 0) == 15);
    assert(recorder_fps_budget(20, second, 0, 0) == 20);
    assert(recorder_fps_budget(25, second, 0, 0) == 25);

    /* A single stall must not slow every camera for ten seconds. */
    recorder_pressure_update(&pressure, second, 150000000ull);
    assert(recorder_fps_budget(25, second, pressure.slow_until, pressure.severe_until) == 25);
    recorder_pressure_update(&pressure, second + 1, 1000000ull);
    recorder_pressure_update(&pressure, second + 2, 150000000ull);
    recorder_pressure_update(&pressure, second + 3, 150000000ull);
    assert(recorder_fps_budget(25, second + 3, pressure.slow_until, pressure.severe_until) == 25);

    pressure = (recorder_pressure_t){0};
    for (unsigned i = 0; i < 3; i++) recorder_pressure_update(&pressure, second + i, 70000000ull);
    assert(recorder_fps_budget(25, second + 2, pressure.slow_until, pressure.severe_until) == 8);
    for (unsigned i = 0; i < 3; i++) recorder_pressure_update(&pressure, second + 3 + i, 150000000ull);
    assert(recorder_fps_budget(25, second + 5, pressure.slow_until, pressure.severe_until) == 4);
    assert(recorder_fps_budget(2, second + 5, pressure.slow_until, pressure.severe_until) == 2);
    assert(recorder_fps_budget(25, 2 * second + 4, pressure.slow_until, pressure.severe_until) == 4);
    assert(recorder_fps_budget(25, 2 * second + 5, pressure.slow_until, pressure.severe_until) == 25);

    /* Each camera owns its pressure state; one slow encoder leaves its peers alone. */
    recorder_pressure_t other = {0};
    assert(recorder_fps_budget(25, second + 5, other.slow_until, other.severe_until) == 25);

    assert(hud_output_fps_limit(0) == 20);
    assert(hud_output_fps_limit(5) == 20);
    assert(hud_output_fps_limit(6) == 25);
    assert(hud_output_fps_limit(9) == 25);
    assert(hud_output_fps_limit(10) == 20);
    assert(hud_output_fps_limit(12) == 20);

    check_source_cadence(15, 150);
    check_source_cadence(20, 200);
    check_source_cadence(25, 250);

    uint64_t interval = second / 15;
    assert(recorder_next_due_ns(second, second, interval) == second + interval);
    assert(recorder_next_due_ns(second, second + interval, interval) == second + 2 * interval);
    assert(recorder_next_due_ns(second, 20 * second, interval) == 20066666476ull);
    assert(recorder_next_due_ns(0, second, 0) == second);
    return 0;
}

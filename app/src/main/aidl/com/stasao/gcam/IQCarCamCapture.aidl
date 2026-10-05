package com.stasao.gcam;

import android.view.Surface;

interface IQCarCamCapture {
    String start(in Surface surface, int inputId, int targetFps);
    void stop(int inputId);
    String status(int inputId);
    long[] stats(int inputId);

    /** One lightweight output reserved for the ANHUD turn-camera projection. */
    String startHud(in Surface surface, int inputId, int targetFps);
    void stopHud();
    String hudStatus();
    long[] hudStats();
}

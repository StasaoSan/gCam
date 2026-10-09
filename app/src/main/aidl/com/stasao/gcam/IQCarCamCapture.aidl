package com.stasao.gcam;

import android.view.Surface;

interface IQCarCamCapture {
    String start(in Surface surface, int inputId, int targetFps);
    void stop(int inputId);
    String status(int inputId);
    long[] stats(int inputId);

    /** One lightweight output reserved for the ANHUD turn-camera projection. */
    String startHud(int slot, in Surface surface, int inputId, int targetFps,
        float cropX, float cropY, float cropZoom, float fisheye, int shape);
    void configureHud(int slot, float cropX, float cropY, float cropZoom, float fisheye, int shape);
    void stopHud(int slot);
    String hudStatus(int slot);
    long[] hudStats(int slot);
    /** Independent source crop and clockwise quarter-turns. Added after the legacy methods. */
    String startHudV2(int slot, in Surface surface, int inputId, int targetFps,
        float cropX, float cropY, float cropWidth, float cropHeight, float fisheye, int shape, int quarterTurns);
    void configureHudV2(int slot, float cropX, float cropY, float cropWidth, float cropHeight,
        float fisheye, int shape, int quarterTurns);
}

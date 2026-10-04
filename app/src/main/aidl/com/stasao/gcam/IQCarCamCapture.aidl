package com.stasao.gcam;

import android.view.Surface;

interface IQCarCamCapture {
    String start(in Surface surface, int inputId, int targetFps);
    void stop(int inputId);
    String status(int inputId);
    long[] stats(int inputId);
}

cmake_minimum_required(VERSION 3.22.1)
project("gcam_native")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -O3 -ffast-math")

# JNI shared library for the app
add_library(gcam_native SHARED qcarcam_jni.c)
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
target_link_libraries(gcam_native ${log-lib} ${android-lib} ${egl-lib} ${gles-lib} dl m)

# Recorder compressed-frame memory has no capture, GPU, or USB dependencies.
add_library(gcam_buffer SHARED native_sample_pool.c native_sample_pool_jni.c)
target_compile_features(gcam_buffer PRIVATE c_std_11)
target_compile_definitions(gcam_buffer PRIVATE _POSIX_C_SOURCE=200809L)

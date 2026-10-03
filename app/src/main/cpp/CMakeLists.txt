cmake_minimum_required(VERSION 3.22.1)
project("gcam_native")

set(CMAKE_C_FLAGS "${CMAKE_C_FLAGS} -Wall -Wextra -O3 -ffast-math")

# JNI shared library for the app
add_library(gcam_native SHARED qcarcam_jni.c)
find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles-lib GLESv3)
target_link_libraries(gcam_native ${log-lib} ${android-lib} ${egl-lib} ${gles-lib} dl)

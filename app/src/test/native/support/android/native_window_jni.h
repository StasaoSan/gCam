#pragma once
#include <jni.h>
#include <stdatomic.h>
typedef struct ANativeWindow { int width,height,recorder; atomic_int refs,geometry_calls; } ANativeWindow;
ANativeWindow *ANativeWindow_fromSurface(JNIEnv *,jobject);
void ANativeWindow_release(ANativeWindow *);
void ANativeWindow_acquire(ANativeWindow *);
int ANativeWindow_setBuffersGeometry(ANativeWindow *,int,int,int);

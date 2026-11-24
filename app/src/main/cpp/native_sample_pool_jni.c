#include "native_sample_pool.h"
#include <jni.h>

JNIEXPORT jlong JNICALL Java_com_stasao_gcam_NativeSampleMemory_create(
        JNIEnv *env, jclass clazz, jlong max_bytes) {
    (void)env; (void)clazz;
    return (jlong)gcam_sample_pool_create((int64_t)max_bytes);
}

JNIEXPORT jlong JNICALL Java_com_stasao_gcam_NativeSampleMemory_acquire(
        JNIEnv *env, jclass clazz, jlong pool, jint channel, jint size) {
    (void)env; (void)clazz;
    return (jlong)gcam_sample_pool_acquire((int64_t)pool, channel, size);
}

JNIEXPORT jobject JNICALL Java_com_stasao_gcam_NativeSampleMemory_buffer(
        JNIEnv *env, jclass clazz, jlong pool, jlong lease) {
    (void)clazz;
    size_t size = 0;
    void *data = gcam_sample_pool_buffer((int64_t)pool, (int64_t)lease, &size);
    return data ? (*env)->NewDirectByteBuffer(env, data, (jlong)size) : NULL;
}

JNIEXPORT jboolean JNICALL Java_com_stasao_gcam_NativeSampleMemory_release(
        JNIEnv *env, jclass clazz, jlong pool, jlong lease) {
    (void)env; (void)clazz;
    return gcam_sample_pool_release((int64_t)pool, (int64_t)lease) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_stasao_gcam_NativeSampleMemory_setBudget(
        JNIEnv *env, jclass clazz, jlong pool, jlong bytes) {
    (void)env; (void)clazz;
    gcam_sample_pool_set_budget((int64_t)pool, (int64_t)bytes);
}

JNIEXPORT void JNICALL Java_com_stasao_gcam_NativeSampleMemory_trim(
        JNIEnv *env, jclass clazz, jlong pool, jlong keep_free_bytes) {
    (void)env; (void)clazz;
    gcam_sample_pool_trim((int64_t)pool, (int64_t)keep_free_bytes);
}

JNIEXPORT jlongArray JNICALL Java_com_stasao_gcam_NativeSampleMemory_snapshot(
        JNIEnv *env, jclass clazz, jlong pool) {
    (void)clazz;
    int64_t values[5];
    jlong output[5] = {0, 0, 0, 0, 0};
    if (gcam_sample_pool_snapshot((int64_t)pool, values)) {
        for (int i = 0; i < 5; ++i) output[i] = (jlong)values[i];
    }
    jlongArray result = (*env)->NewLongArray(env, 5);
    if (result) (*env)->SetLongArrayRegion(env, result, 0, 5, output);
    return result;
}

JNIEXPORT jboolean JNICALL Java_com_stasao_gcam_NativeSampleMemory_destroy(
        JNIEnv *env, jclass clazz, jlong pool) {
    (void)env; (void)clazz;
    return gcam_sample_pool_destroy((int64_t)pool) ? JNI_TRUE : JNI_FALSE;
}

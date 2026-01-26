#include <jni.h>
#include <android/log.h>

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_setSpeed(
        JNIEnv* env,
        jobject,
        jdouble speed) {

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "SetSpeed: %f", speed);
    // Stub: In real app, write to shared memory or call dlsym function in target process
    // float global_speed = (float)speed;
    return JNI_TRUE;
}

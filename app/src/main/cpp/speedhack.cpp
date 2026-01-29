#include <jni.h>
#include <android/log.h>
#include <stdio.h>

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_techted89_gameex_NativeScanner_setSpeed(
        JNIEnv* env,
        jobject,
        jdouble speed) {

    __android_log_print(ANDROID_LOG_INFO, "NativeScanner", "SetSpeed: %f", speed);

    // Attempt to write to shared memory/pipe used by the injected libhack.so
    // This assumes the library has been injected and set up an IPC channel (e.g. /dev/shm/gg_speed)
    FILE* ipc = fopen("/dev/shm/gg_speed", "w");
    if (ipc) {
        float fSpeed = (float)speed;
        fwrite(&fSpeed, sizeof(float), 1, ipc);
        fclose(ipc);
        return JNI_TRUE;
    }

    // Fallback: If IPC fails (lib not injected), we just log.
    // In production, this would return false to indicate failure.
    return JNI_FALSE;
}

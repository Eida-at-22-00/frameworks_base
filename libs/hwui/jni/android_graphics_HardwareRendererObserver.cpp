/*
 * Copyright (C) 2019 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "android_graphics_HardwareRendererObserver.h"

#include <array>

#include "FrameInfo.h"
#include "graphics_jni_helpers.h"
#include "nativehelper/jni_macros.h"

namespace android {

struct {
    jclass clazz;
    jmethodID callback;
} gHardwareRendererObserverClassInfo;

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

HardwareRendererObserver::HardwareRendererObserver(JavaVM* vm, jobject observer,
                                                   bool waitForPresentTime)
        : uirenderer::FrameMetricsObserver(waitForPresentTime), mVm(vm) {
    mObserver = getenv(mVm)->NewGlobalRef(observer);
    LOG_ALWAYS_FATAL_IF(mObserver == nullptr, "unable to create frame stats observer reference");
}

HardwareRendererObserver::~HardwareRendererObserver() {
    JNIEnv* env = getenv(mVm);
    env->DeleteGlobalRef(mObserver);
}

bool HardwareRendererObserver::getNextBuffer(JNIEnv* env, jlongArray metrics, int* dropCount) {
    jsize bufferSize = env->GetArrayLength(reinterpret_cast<jarray>(metrics));
    LOG_ALWAYS_FATAL_IF(bufferSize != HardwareRendererObserver::kBufferSize,
                        "Mismatched Java/Native FrameMetrics data format.");

    FrameMetricsNotification& elem = mRingBuffer[mNextInQueue];
    if (elem.hasData.load()) {
        env->SetLongArrayRegion(metrics, 0, kBufferSize, elem.buffer);
        *dropCount = elem.dropCount;
        mNextInQueue = (mNextInQueue + 1) % kRingSize;
        elem.hasData = false;
        return true;
    }

    return false;
}

void HardwareRendererObserver::notify(const uirenderer::FrameInfoBuffer& stats) {
    if (!mKeepListening) return;

    FrameMetricsNotification& elem = mRingBuffer[mNextFree];

    if (!elem.hasData.load()) {
        memcpy(elem.buffer, stats.data(), kBufferSize * sizeof(stats[0]));

        elem.dropCount = mDroppedReports;
        mDroppedReports = 0;
        mNextFree = (mNextFree + 1) % kRingSize;
        elem.hasData = true;

        JNIEnv* env = getenv(mVm);
        mKeepListening = env->CallStaticBooleanMethod(gHardwareRendererObserverClassInfo.clazz,
                                                      gHardwareRendererObserverClassInfo.callback,
                                                      mObserver);
    } else {
        mDroppedReports++;
    }
}

static jlong android_graphics_HardwareRendererObserver_createObserver(JNIEnv* env,
                                                                      jobject /*clazz*/,
                                                                      jobject weakRefThis,
                                                                      jboolean waitForPresentTime) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) {
        LOG_ALWAYS_FATAL("Unable to get Java VM");
        return 0;
    }

    HardwareRendererObserver* observer =
            new HardwareRendererObserver(vm, weakRefThis, waitForPresentTime);
    return reinterpret_cast<jlong>(observer);
}

static jint android_graphics_HardwareRendererObserver_getNextBuffer(JNIEnv* env, jobject,
                                                                    jlong observerPtr,
                                                                    jlongArray metrics) {
    HardwareRendererObserver* observer = reinterpret_cast<HardwareRendererObserver*>(observerPtr);
    int dropCount = 0;
    if (observer->getNextBuffer(env, metrics, &dropCount)) {
        return dropCount;
    } else {
        return -1;
    }
}

static const std::array gMethods = {
        MAKE_JNI_NATIVE_METHOD("nCreateObserver", "(Ljava/lang/ref/WeakReference;Z)J",
                               android_graphics_HardwareRendererObserver_createObserver),
        MAKE_JNI_NATIVE_METHOD("nGetNextBuffer", "(J[J)I",
                               android_graphics_HardwareRendererObserver_getNextBuffer),
};

int register_android_graphics_HardwareRendererObserver(JNIEnv* env) {

    jclass observerClass = FindClassOrDie(env, "android/graphics/HardwareRendererObserver");
    gHardwareRendererObserverClassInfo.clazz =
            reinterpret_cast<jclass>(env->NewGlobalRef(observerClass));
    gHardwareRendererObserverClassInfo.callback = GetStaticMethodIDOrDie(
            env, observerClass, "invokeDataAvailable", "(Ljava/lang/ref/WeakReference;)Z");

    return RegisterMethodsOrDie(env, "android/graphics/HardwareRendererObserver",
                                gMethods.data(), gMethods.size());

}

} // namespace android
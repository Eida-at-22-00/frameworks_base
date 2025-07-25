/*
 * Copyright (C) 2024 The Android Open Source Project
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

#ifndef _ANDROID_SERVER_GNSS_GNSSASSITANCECALLBACK_H
#define _ANDROID_SERVER_GNSS_GNSSASSITANCECALLBACK_H

#pragma once

#ifndef LOG_TAG
#error LOG_TAG must be defined before including this file.
#endif

#include <android/hardware/gnss/gnss_assistance/BnGnssAssistanceCallback.h>
#include <log/log.h>

#include "Utils.h"
#include "jni.h"

namespace android::gnss {

void GnssAssistanceCallback_class_init_once(JNIEnv* env, jclass clazz);

/*
 * GnssAssistanceCallback class implements the callback methods required by the
 * android::hardware::gnss::gnss_assistance::IGnssAssistanceCallback interface.
 */
class GnssAssistanceCallback : public hardware::gnss::gnss_assistance::BnGnssAssistanceCallback {
public:
    GnssAssistanceCallback() {}
    binder::Status injectRequestCb() override;
};

} // namespace android::gnss

#endif // _ANDROID_SERVER_GNSS_GNSSASSITANCECALLBACK_H

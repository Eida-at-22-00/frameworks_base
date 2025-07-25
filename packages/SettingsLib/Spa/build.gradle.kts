/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.build.gradle.BaseExtension
import com.android.build.gradle.api.AndroidBasePlugin

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.android) apply false
}

val androidTop: String = File(rootDir, "../../../../..").canonicalPath

allprojects {
    extra["androidTop"] = androidTop
    extra["jetpackComposeVersion"] = "1.8.0-rc01"
}

subprojects {
    layout.buildDirectory.set(file("$androidTop/out/gradle-spa/$name"))

    plugins.withType<AndroidBasePlugin> {
        configure<BaseExtension> {
            compileSdkVersion(36)

            defaultConfig {
                minSdk = 21
                targetSdk = 36
            }
        }

        configure<JavaPluginExtension> {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(libs.versions.jvm.get()))
            }
        }
    }
}

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

package com.android.compose.animation.scene.transformation

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.TestScenes
import com.android.compose.animation.scene.inScene
import com.android.compose.animation.scene.testTransition
import com.android.compose.test.assertSizeIsEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedElementTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testSharedElement() {
        rule.testTransition(
            fromSceneContent = {
                // Foo is at (10, 50) with a size of (20, 80).
                Box(
                    Modifier.offset(10.dp, 50.dp)
                        .element(TestElements.Foo)
                        .size(20.dp, 80.dp)
                        .background(Color.Red)
                )
            },
            toSceneContent = {
                // Foo is at (50, 70) with a size of (10, 40).
                Box(
                    Modifier.offset(50.dp, 70.dp)
                        .element(TestElements.Foo)
                        .size(10.dp, 40.dp)
                        .background(Color.Blue)
                )
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                // Elements should be shared by default.
            },
        ) {
            before {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(20.dp, 80.dp)
            }
            atAllFrames(4) {
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(
                        interpolate(10.dp, 50.dp),
                        interpolate(50.dp, 70.dp),
                    )
                    .assertSizeIsEqualTo(interpolate(20.dp, 10.dp), interpolate(80.dp, 40.dp))
            }
            after {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 70.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.dp, 40.dp)
            }
        }
    }

    @Test
    fun testSharedElementDisabled() {
        rule.testTransition(
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
            // The full layout is 100x100.
            layoutModifier = Modifier.size(100.dp),
            fromSceneContent = {
                Box(Modifier.fillMaxSize()) {
                    // Foo is at (10, 50).
                    Box(
                        Modifier.offset(10.dp, 50.dp)
                            .element(TestElements.Foo)
                            .size(20.dp)
                            .background(Color.Red)
                    )
                }
            },
            toSceneContent = {
                Box(Modifier.fillMaxSize()) {
                    // Foo is at (50, 60).
                    Box(
                        Modifier.offset(50.dp, 60.dp)
                            .element(TestElements.Foo)
                            .size(20.dp)
                            .background(Color.Blue)
                    )
                }
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)

                // Disable the shared element animation.
                sharedElement(TestElements.Foo, enabled = false)

                // In SceneA, Foo leaves to the left edge.
                translate(
                    TestElements.Foo.inScene(TestScenes.SceneA),
                    Edge.Left,
                    startsOutsideLayoutBounds = false,
                )

                // In SceneB, Foo comes from the bottom edge.
                translate(TestElements.Foo.inScene(TestScenes.SceneB), Edge.Bottom)
            },
        ) {
            before { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
            atAllFrames(4) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(interpolate(10.dp, 0.dp), 50.dp)
                onElement(TestElements.Foo, scene = TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(50.dp, interpolate(100.dp, 60.dp))
            }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 60.dp) }
        }
    }
}

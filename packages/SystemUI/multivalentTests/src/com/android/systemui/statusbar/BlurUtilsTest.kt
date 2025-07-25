/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar

import android.content.res.Resources
import android.view.CrossWindowBlurListeners
import android.view.SurfaceControl
import android.view.SyncRtSurfaceTransactionApplier
import android.view.ViewRootImpl
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.ui.transitions.BlurConfig
import com.google.common.truth.Truth.assertThat
import junit.framework.TestCase.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class BlurUtilsTest : SysuiTestCase() {

    val blurConfig: BlurConfig = BlurConfig(minBlurRadiusPx = 1.0f, maxBlurRadiusPx = 100.0f)
    @Mock lateinit var dumpManager: DumpManager
    @Mock lateinit var crossWindowBlurListeners: CrossWindowBlurListeners
    @Mock lateinit var resources: Resources
    @Mock lateinit var syncRTTransactionApplier: SyncRtSurfaceTransactionApplier
    @Mock lateinit var transaction: SurfaceControl.Transaction
    @Captor
    private lateinit var captor: ArgumentCaptor<SyncRtSurfaceTransactionApplier.SurfaceParams>
    private lateinit var blurUtils: TestableBlurUtils

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        blurUtils = TestableBlurUtils()
    }

    @Test
    fun testApplyBlur_noViewRoot_doesntCrash() {
        blurUtils.applyBlur(null /* viewRootImple */, 10 /* radius */, false /* opaque */)
    }

    @Test
    fun testApplyBlur_invalidSurfaceControl() {
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        blurUtils.applyBlur(viewRootImpl, 10 /* radius */, false /* opaque */)
    }

    @Test
    fun testApplyBlur_success() {
        val radius = 10
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        `when`(surfaceControl.isValid).thenReturn(true)
        blurUtils.applyBlur(viewRootImpl, radius, true /* opaque */)

        verify(syncRTTransactionApplier).scheduleApply(captor.capture())
        assertThat(captor.value.opaque).isTrue()
        assertEquals(radius, captor.value.backgroundBlurRadius)
    }

    @Test
    fun testApplyBlur_blurDisabled() {
        val radius = 10
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        `when`(surfaceControl.isValid).thenReturn(true)

        blurUtils.blursEnabled = false
        blurUtils.applyBlur(viewRootImpl, radius, true /* opaque */)

        verify(syncRTTransactionApplier).scheduleApply(captor.capture())
        assertThat(captor.value.opaque).isTrue()
        assertEquals(0 /* unset value */, captor.value.backgroundBlurRadius)
    }

    @Test
    fun testEarlyWakeUp() {
        val radius = 10
        val surfaceControl = mock(SurfaceControl::class.java)
        val viewRootImpl = mock(ViewRootImpl::class.java)
        val tmpFloatArray = FloatArray(0)
        `when`(viewRootImpl.surfaceControl).thenReturn(surfaceControl)
        `when`(surfaceControl.isValid).thenReturn(true)
        blurUtils.applyBlur(viewRootImpl, radius, true /* opaque */)

        verify(syncRTTransactionApplier).scheduleApply(captor.capture())
        assertThat(captor.value.opaque).isTrue()
        SyncRtSurfaceTransactionApplier.applyParams(transaction, captor.value, tmpFloatArray)
        verify(transaction).setEarlyWakeupStart()

        clearInvocations(syncRTTransactionApplier)
        clearInvocations(transaction)
        blurUtils.applyBlur(viewRootImpl, 0, true /* opaque */)
        verify(syncRTTransactionApplier).scheduleApply(captor.capture())
        SyncRtSurfaceTransactionApplier.applyParams(transaction, captor.value, tmpFloatArray)
        verify(transaction).setEarlyWakeupEnd()
    }

    inner class TestableBlurUtils :
        BlurUtils(resources, blurConfig, crossWindowBlurListeners, dumpManager) {
        var blursEnabled = true
        override val transactionApplier: SyncRtSurfaceTransactionApplier
            get() = syncRTTransactionApplier

        override fun supportsBlursOnWindows(): Boolean {
            return blursEnabled
        }
    }
}

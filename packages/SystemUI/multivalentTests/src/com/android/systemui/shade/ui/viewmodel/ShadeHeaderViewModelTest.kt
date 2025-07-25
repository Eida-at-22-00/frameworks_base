package com.android.systemui.shade.ui.viewmodel

import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import android.telephony.SubscriptionManager.PROFILE_CLASS_UNSET
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.OverlayKey
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.runCurrent
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.plugins.activityStarter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.disableDualShade
import com.android.systemui.shade.domain.interactor.enableDualShade
import com.android.systemui.shade.domain.interactor.enableSingleShade
import com.android.systemui.shade.domain.interactor.enableSplitShade
import com.android.systemui.shade.domain.interactor.shadeMode
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.shade.ui.viewmodel.ShadeHeaderViewModel.HeaderChipHighlight
import com.android.systemui.statusbar.pipeline.mobile.data.model.SubscriptionModel
import com.android.systemui.statusbar.pipeline.mobile.domain.interactor.fakeMobileIconsInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.argThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatcher
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class ShadeHeaderViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val testScope = kosmos.testScope
    private val mobileIconsInteractor by lazy { kosmos.fakeMobileIconsInteractor }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    private val underTest by lazy { kosmos.shadeHeaderViewModel }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        underTest.activateIn(testScope)
    }

    @Test
    fun mobileSubIds_update() =
        testScope.runTest {
            mobileIconsInteractor.filteredSubscriptions.value = listOf(SUB_1)
            runCurrent()

            assertThat(underTest.mobileSubIds).isEqualTo(listOf(1))

            mobileIconsInteractor.filteredSubscriptions.value = listOf(SUB_1, SUB_2)
            runCurrent()

            assertThat(underTest.mobileSubIds).isEqualTo(listOf(1, 2))
        }

    @Test
    fun onClockClicked_launchesClock() =
        testScope.runTest {
            val activityStarter = kosmos.activityStarter
            underTest.onClockClicked()

            verify(activityStarter)
                .postStartActivityDismissingKeyguard(
                    argThat(IntentMatcherAction(AlarmClock.ACTION_SHOW_ALARMS)),
                    anyInt(),
                )
        }

    @Test
    fun showClock_wideLayout_returnsTrue() =
        testScope.runTest {
            kosmos.enableDualShade(wideLayout = true)

            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.NotificationsShade)
            assertThat(underTest.showClock).isTrue()

            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.QuickSettingsShade)
            assertThat(underTest.showClock).isTrue()
        }

    @Test
    fun showClock_narrowLayoutOnNotificationsShade_returnsFalse() =
        testScope.runTest {
            kosmos.enableDualShade(wideLayout = false)
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.NotificationsShade)

            assertThat(underTest.showClock).isFalse()
        }

    @Test
    fun showClock_narrowLayoutOnQuickSettingsShade_returnsTrue() =
        testScope.runTest {
            kosmos.enableDualShade(wideLayout = false)
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.QuickSettingsShade)

            assertThat(underTest.showClock).isTrue()
        }

    @Test
    fun onShadeCarrierGroupClicked_launchesNetworkSettings() =
        testScope.runTest {
            val activityStarter = kosmos.activityStarter
            underTest.onShadeCarrierGroupClicked()

            verify(activityStarter)
                .postStartActivityDismissingKeyguard(
                    argThat(IntentMatcherAction(Settings.ACTION_WIRELESS_SETTINGS)),
                    anyInt(),
                )
        }

    @Test
    fun onSystemIconChipClicked_locked_collapsesShadeToLockscreen() =
        testScope.runTest {
            kosmos.disableDualShade()
            setDeviceEntered(false)
            setScene(Scenes.Shade)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
        }

    @Test
    fun onSystemIconChipClicked_lockedOnQsShade_collapsesShadeToLockscreen() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.QuickSettingsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun onSystemIconChipClicked_lockedOnNotifShade_expandsQsShade() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.NotificationsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.QuickSettingsShade)
            assertThat(currentOverlays).doesNotContain(Overlays.NotificationsShade)
        }

    @Test
    fun onSystemIconChipClicked_unlocked_collapsesShadeToGone() =
        testScope.runTest {
            kosmos.disableDualShade()
            setDeviceEntered(true)
            setScene(Scenes.Shade)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Gone)
        }

    @Test
    fun onSystemIconChipClicked_unlockedOnQsShade_collapsesShadeToGone() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.QuickSettingsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun onSystemIconChipClicked_unlockedOnNotifShade_expandsQsShade() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.NotificationsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onSystemIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).contains(Overlays.QuickSettingsShade)
            assertThat(currentOverlays).doesNotContain(Overlays.NotificationsShade)
        }

    @Test
    fun onNotificationIconChipClicked_lockedOnNotifShade_collapsesShadeToLockscreen() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.NotificationsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onNotificationIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun onNotificationIconChipClicked_lockedOnQsShade_expandsNotifShade() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.QuickSettingsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onNotificationIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(currentOverlays).contains(Overlays.NotificationsShade)
            assertThat(currentOverlays).doesNotContain(Overlays.QuickSettingsShade)
        }

    @Test
    fun onNotificationIconChipClicked_unlockedOnNotifShade_collapsesShadeToGone() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.NotificationsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onNotificationIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).isEmpty()
        }

    @Test
    fun onNotificationIconChipClicked_unlockedOnQsShade_expandsNotifShade() =
        testScope.runTest {
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.QuickSettingsShade)
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)

            underTest.onNotificationIconChipClicked()
            runCurrent()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
            assertThat(currentOverlays).contains(Overlays.NotificationsShade)
            assertThat(currentOverlays).doesNotContain(Overlays.QuickSettingsShade)
        }

    @Test
    fun highlightChips_notifsOpenInSingleShade_bothNone() =
        testScope.runTest {
            kosmos.enableSingleShade()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            setScene(Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(currentOverlays).isEmpty()

            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.None)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.None)
        }

    @Test
    fun highlightChips_notifsOpenInSplitShade_bothNone() =
        testScope.runTest {
            kosmos.enableSplitShade()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            setScene(Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            assertThat(currentOverlays).isEmpty()

            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.None)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.None)
        }

    @Test
    fun highlightChips_quickSettingsOpenInSingleShade_bothNone() =
        testScope.runTest {
            kosmos.enableSingleShade()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
            setScene(Scenes.QuickSettings)
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)
            assertThat(currentOverlays).isEmpty()

            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.None)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.None)
        }

    @Test
    fun highlightChips_notifsOpenInDualShade_notifsStrongQuickSettingsWeak() =
        testScope.runTest {
            // Test the lockscreen scenario.
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.NotificationsShade)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.Strong)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.Weak)

            // Test the unlocked scenario.
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.NotificationsShade)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.Strong)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.Weak)
        }

    @Test
    fun highlightChips_quickSettingsOpenInDualShade_notifsWeakQuickSettingsStrong() =
        testScope.runTest {
            // Test the lockscreen scenario.
            setupDualShadeState(scene = Scenes.Lockscreen, overlay = Overlays.QuickSettingsShade)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.Weak)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.Strong)

            // Test the unlocked scenario.
            setupDualShadeState(scene = Scenes.Gone, overlay = Overlays.QuickSettingsShade)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.Weak)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.Strong)
        }

    @Test
    fun highlightChips_noOverlaysInDualShade_bothNone() =
        testScope.runTest {
            // Test the lockscreen scenario.
            setupDualShadeState(scene = Scenes.Lockscreen)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.None)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.None)

            // Test the unlocked scenario.
            setupDualShadeState(scene = Scenes.Gone)
            assertThat(underTest.notificationsChipHighlight).isEqualTo(HeaderChipHighlight.None)
            assertThat(underTest.quickSettingsChipHighlight).isEqualTo(HeaderChipHighlight.None)
        }

    companion object {
        private val SUB_1 =
            SubscriptionModel(
                subscriptionId = 1,
                isOpportunistic = false,
                carrierName = "Carrier 1",
                profileClass = PROFILE_CLASS_UNSET,
            )
        private val SUB_2 =
            SubscriptionModel(
                subscriptionId = 2,
                isOpportunistic = false,
                carrierName = "Carrier 2",
                profileClass = PROFILE_CLASS_UNSET,
            )
    }

    private fun TestScope.setupDualShadeState(scene: SceneKey, overlay: OverlayKey? = null) {
        kosmos.enableDualShade()
        val shadeMode by collectLastValue(kosmos.shadeMode)
        val currentScene by collectLastValue(sceneInteractor.currentScene)
        val currentOverlays by collectLastValue(sceneInteractor.currentOverlays)
        if (scene == Scenes.Gone) {
            // Unlock the device, marking the device has been entered.
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
        }
        runCurrent()
        assertThat(shadeMode).isEqualTo(ShadeMode.Dual)

        sceneInteractor.changeScene(scene, "test")
        checkNotNull(currentOverlays).forEach { sceneInteractor.instantlyHideOverlay(it, "test") }
        runCurrent()
        overlay?.let { sceneInteractor.showOverlay(it, "test") }
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(scene, setOfNotNull(overlay))
            )
        )
        runCurrent()

        assertThat(currentScene).isEqualTo(scene)
        if (overlay == null) {
            assertThat(currentOverlays).isEmpty()
        } else {
            assertThat(currentOverlays).containsExactly(overlay)
        }
    }

    private fun setScene(key: SceneKey) {
        sceneInteractor.changeScene(key, "test")
        sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
        )
        testScope.runCurrent()
    }

    private fun TestScope.setDeviceEntered(isEntered: Boolean) {
        if (isEntered) {
            // Unlock the device marking the device has entered.
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
        }
        setScene(
            if (isEntered) {
                Scenes.Gone
            } else {
                Scenes.Lockscreen
            }
        )
        assertThat(deviceEntryInteractor.isDeviceEntered.value).isEqualTo(isEntered)
    }
}

private class IntentMatcherAction(private val action: String) : ArgumentMatcher<Intent> {
    override fun matches(argument: Intent?): Boolean {
        return argument?.action == action
    }
}

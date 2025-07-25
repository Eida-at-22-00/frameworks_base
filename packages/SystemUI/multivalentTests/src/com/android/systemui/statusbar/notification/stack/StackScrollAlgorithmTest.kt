package com.android.systemui.statusbar.notification.stack

import android.annotation.DimenRes
import android.content.pm.PackageManager
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import android.widget.FrameLayout
import androidx.test.filters.SmallTest
import com.android.keyguard.BouncerPanelExpansionCalculator.aboutToShowBouncerProgress
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ShadeInterpolation.getContentAlpha
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.FeatureFlagsClassic
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.res.R
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.transition.LargeScreenShadeInterpolator
import com.android.systemui.statusbar.NotificationShelf
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.chips.notification.shared.StatusBarNotifChips
import com.android.systemui.statusbar.notification.RoundableState
import com.android.systemui.statusbar.notification.collection.EntryAdapter
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository
import com.android.systemui.statusbar.notification.emptyshade.ui.view.EmptyShadeView
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView
import com.android.systemui.statusbar.notification.footer.ui.view.FooterView.FooterViewState
import com.android.systemui.statusbar.notification.headsup.AvalancheController
import com.android.systemui.statusbar.notification.headsup.HeadsUpAnimator
import com.android.systemui.statusbar.notification.headsup.NotificationsHunSharedAnimationValues
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.row.ExpandableView
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager
import com.android.systemui.statusbar.ui.fakeSystemBarUtilsProxy
import com.android.systemui.testKosmos
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import kotlin.math.roundToInt
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class StackScrollAlgorithmTest(flags: FlagsParameterization) : SysuiTestCase() {

    @JvmField @Rule var expect: Expect = Expect.create()

    private val kosmos = testKosmos()

    private val largeScreenShadeInterpolator = mock<LargeScreenShadeInterpolator>()
    private val avalancheController = mock<AvalancheController>()

    private val hostView = FrameLayout(context)
    private lateinit var headsUpAnimator: HeadsUpAnimator
    private lateinit var stackScrollAlgorithm: StackScrollAlgorithm
    private val notificationRow = mock<ExpandableNotificationRow>()
    private val notificationEntry = mock<NotificationEntry>()
    private val notificationEntryAdapter = mock<EntryAdapter>()
    private val dumpManager = mock<DumpManager>()
    private val mStatusBarKeyguardViewManager = mock<StatusBarKeyguardViewManager>()
    private val notificationShelf = mock<NotificationShelf>()
    private val headsUpRepository = mock<HeadsUpRepository>()
    private val emptyShadeView =
        EmptyShadeView(context, /* attrs= */ null).apply {
            layout(/* l= */ 0, /* t= */ 0, /* r= */ 100, /* b= */ 100)
        }
    private val footerView = FooterView(context, /* attrs= */ null)
    private val ambientState =
        AmbientState(
            context,
            dumpManager,
            /* sectionProvider */ { _, _ -> false },
            /* bypassController */ { false },
            mStatusBarKeyguardViewManager,
            largeScreenShadeInterpolator,
            headsUpRepository,
            avalancheController,
        )

    private val testableResources = mContext.getOrCreateTestableResources()
    private val featureFlags = mock<FeatureFlagsClassic>()
    private val maxPanelHeight =
        mContext.resources.displayMetrics.heightPixels -
            px(R.dimen.notification_panel_margin_top) -
            px(R.dimen.notification_panel_margin_bottom)

    private fun px(@DimenRes id: Int): Float =
        testableResources.resources.getDimensionPixelSize(id).toFloat()

    private val notifSectionDividerGap = px(R.dimen.notification_section_divider_height)
    private val scrimPadding = px(R.dimen.notification_side_paddings)
    private val baseZ by lazy { ambientState.baseZHeight }
    private val headsUpZ = px(R.dimen.heads_up_pinned_elevation)
    private val bigGap = notifSectionDividerGap
    private val smallGap = px(R.dimen.notification_section_divider_height_lockscreen)

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf(
                    NotificationsHunSharedAnimationValues.FLAG_NAME
                )
                .andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        Assume.assumeFalse(isTv())
        mDependency.injectTestDependency(FeatureFlags::class.java, featureFlags)
        whenever(notificationShelf.viewState).thenReturn(ExpandableViewState())
        whenever(notificationRow.key).thenReturn("key")
        whenever(notificationRow.viewState).thenReturn(ExpandableViewState())
        whenever(notificationRow.entryLegacy).thenReturn(notificationEntry)
        whenever(notificationRow.entryAdapter).thenReturn(notificationEntryAdapter)
        whenever(notificationRow.roundableState)
            .thenReturn(RoundableState(notificationRow, notificationRow, 0f))
        ambientState.isSmallScreen = true

        hostView.addView(notificationRow)

        if (NotificationsHunSharedAnimationValues.isEnabled) {
            headsUpAnimator = HeadsUpAnimator(context, kosmos.fakeSystemBarUtilsProxy)
        }
        stackScrollAlgorithm =
            StackScrollAlgorithm(
                context,
                hostView,
                if (::headsUpAnimator.isInitialized) headsUpAnimator else null,
            )
    }

    private fun isTv(): Boolean {
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_childPositionedAtStackTop() {
        val stackTop = 100f
        ambientState.stackTop = stackTop

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(notificationRow.viewState.yTranslation).isEqualTo(stackTop)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHun_yTranslationIsHeadsUpTop() {
        val headsUpTop = 200f
        ambientState.headsUpTop = headsUpTop

        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        resetViewStates_hunYTranslationIs(headsUpTop)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHun_hasShadow() {
        val headsUpTop = 200f
        ambientState.headsUpTop = headsUpTop

        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(notificationRow.viewState.zTranslation).isGreaterThan(baseZ)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_defaultHunWhenShadeIsOpening_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)

        // scroll the panel over the HUN inset
        ambientState.stackY = stackScrollAlgorithm.mHeadsUpInset + bigGap

        // the HUN translation should be the panel scroll position + the scrim padding
        resetViewStates_hunYTranslationIs(ambientState.stackY + scrimPadding)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_defaultHun_yTranslationIsInset() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_defaultHunWithStackMargin_changesHunYTranslation() {
        whenever(notificationRow.isPinned).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHunInShade_stackTopEqualsHunTop_hunHasFullHeight() {
        // Given: headsUpTop == stackTop -> haven't scrolled the stack yet
        val headsUpTop = 150f
        val collapsedHeight = 100
        val intrinsicHeight = 300
        fakeHunInShade(
            headsUpTop = headsUpTop,
            stackTop = headsUpTop,
            collapsedHeight = collapsedHeight,
            intrinsicHeight = intrinsicHeight,
        )

        // When
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: HUN is at the headsUpTop
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTop)
        // And: HUN is not elevated
        assertThat(notificationRow.viewState.zTranslation).isEqualTo(baseZ)
        // And: HUN has its full height
        assertThat(notificationRow.viewState.height).isEqualTo(intrinsicHeight)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHunInShade_stackTopGreaterThanHeadsUpTop_hunClampedToHeadsUpTop() {
        // Given: headsUpTop < stackTop -> scrolled the stack a little bit
        val stackTop = -25f
        val headsUpTop = 150f
        val collapsedHeight = 100
        val intrinsicHeight = 300
        fakeHunInShade(
            headsUpTop = headsUpTop,
            stackTop = stackTop,
            collapsedHeight = collapsedHeight,
            intrinsicHeight = intrinsicHeight,
        )

        // When
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: HUN is translated to the headsUpTop
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTop)
        // And: HUN is not elevated
        assertThat(notificationRow.viewState.zTranslation).isEqualTo(baseZ)
        // And: HUN is clipped to the available space
        // newTranslation = max(150, -25)
        // distToReal = 150 - (-25)
        // height = max(300 - 175, 100)
        assertThat(notificationRow.viewState.height).isEqualTo(125)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHunInShade_stackOverscrolledHun_hunClampedToHeadsUpTop() {
        // Given: headsUpTop << stackTop -> stack has fully overscrolled the HUN
        val stackTop = -500f
        val headsUpTop = 150f
        val collapsedHeight = 100
        val intrinsicHeight = 300
        fakeHunInShade(
            headsUpTop = headsUpTop,
            stackTop = stackTop,
            collapsedHeight = collapsedHeight,
            intrinsicHeight = intrinsicHeight,
        )

        // When
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: HUN is translated to the headsUpTop
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTop)
        // And: HUN fully elevated to baseZ + headsUpZ
        assertThat(notificationRow.viewState.zTranslation).isEqualTo(baseZ + headsUpZ)
        // And: HUN is clipped to its collapsed height
        assertThat(notificationRow.viewState.height).isEqualTo(collapsedHeight)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_defaultHun_showingQS_hunTranslatedToHeadsUpTop() {
        // Given: the shade is open and scrolled to the bottom to show the QuickSettings
        val headsUpTop = 2000f
        val intrinsicHunHeight = 300
        fakeHunInShade(
            headsUpTop = headsUpTop,
            stackTop = 2600f, // stack scrolled below the screen
            stackCutoff = 4000f,
            collapsedHeight = 100,
            intrinsicHeight = intrinsicHunHeight,
        )
        ambientState.qsExpansionFraction = 1.0f
        whenever(notificationRow.isAboveShelf).thenReturn(true)

        // When
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: HUN is translated to the headsUpTop
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTop)
        // And: HUN is elevated to baseZ + headsUpZ
        assertThat(notificationRow.viewState.zTranslation).isEqualTo(baseZ + headsUpZ)
        // And: HUN maintained its full height
        assertThat(notificationRow.viewState.height).isEqualTo(intrinsicHunHeight)
    }

    @Test
    @EnableSceneContainer
    fun updateZTranslationForHunInStack_fullOverlap_hunHasFullElevation() {
        // Given: the overlap equals to the top content padding
        val contentTop = 280f
        val contentTopPadding = 20f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation = 200f
            }

        // When
        stackScrollAlgorithm.updateZTranslationForHunInStack(
            /* scrollingContentTop = */ contentTop,
            /* scrollingContentTopPadding */ contentTopPadding,
            /* baseZ = */ 0f,
            /* viewState = */ viewState,
        )

        // Then: HUN is fully elevated to baseZ + headsUpZ
        assertThat(viewState.zTranslation).isEqualTo(headsUpZ)
    }

    @Test
    @EnableSceneContainer
    fun updateZTranslationForHunInStack_someOverlap_hunIsPartlyElevated() {
        // Given: the overlap is bigger than zero, but less than the top content padding
        val contentTop = 290f
        val contentTopPadding = 20f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation = 200f
            }

        // When
        stackScrollAlgorithm.updateZTranslationForHunInStack(
            /* scrollingContentTop = */ contentTop,
            /* scrollingContentTopPadding */ contentTopPadding,
            /* baseZ = */ 0f,
            /* viewState = */ viewState,
        )

        // Then: HUN is partly elevated
        assertThat(viewState.zTranslation).apply {
            isGreaterThan(0f)
            isLessThan(headsUpZ)
        }
    }

    @Test
    @EnableSceneContainer
    fun updateZTranslationForHunInStack_noOverlap_hunIsNotElevated() {
        // Given: no overlap between the content and the HUN
        val contentTop = 300f
        val contentTopPadding = 20f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation = 200f
            }

        // When
        stackScrollAlgorithm.updateZTranslationForHunInStack(
            /* scrollingContentTop = */ contentTop,
            /* scrollingContentTopPadding */ contentTopPadding,
            /* baseZ = */ 0f,
            /* viewState = */ viewState,
        )

        // Then: HUN is not elevated
        assertThat(viewState.zTranslation).isEqualTo(0f)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_defaultHun_showingQS_hunTranslatedToMax() {
        // Given: the shade is open and scrolled to the bottom to show the QuickSettings
        val maxHunTranslation = 2000f
        ambientState.maxHeadsUpTranslation = maxHunTranslation
        ambientState.setLayoutMinHeight(2500) // Mock the height of shade
        ambientState.stackY = 2500f // Scroll over the max translation
        stackScrollAlgorithm.setIsExpanded(true) // Mark the shade open
        whenever(notificationRow.mustStayOnScreen()).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        whenever(notificationRow.isAboveShelf).thenReturn(true)

        resetViewStates_hunYTranslationIs(maxHunTranslation)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_hunAnimatingAway_showingQS_hunTranslatedToBottomOfScreen() {
        // Given: the shade is open and scrolled to the bottom to show the QuickSettings
        val bottomOfScreen = 2600f
        val maxHunTranslation = 2000f
        ambientState.maxHeadsUpTranslation = maxHunTranslation
        ambientState.setLayoutMinHeight(2500) // Mock the height of shade
        ambientState.stackY = 2500f // Scroll over the max translation
        stackScrollAlgorithm.setIsExpanded(true) // Mark the shade open
        if (NotificationsHunSharedAnimationValues.isEnabled) {
            headsUpAnimator.headsUpAppearHeightBottom = bottomOfScreen.toInt()
        } else {
            stackScrollAlgorithm.setHeadsUpAppearHeightBottom(bottomOfScreen.toInt())
        }
        whenever(notificationRow.mustStayOnScreen()).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        whenever(notificationRow.isAboveShelf).thenReturn(true)
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        resetViewStates_hunYTranslationIs(
            expected = bottomOfScreen + stackScrollAlgorithm.mHeadsUpAppearStartAboveScreen
        )
    }

    @Test
    fun resetViewStates_hunAnimatingAway_hunTranslatedToTopOfScreen() {
        val topMargin = 100f
        ambientState.maxHeadsUpTranslation = 2000f
        ambientState.stackTopMargin = topMargin.toInt()
        if (NotificationsHunSharedAnimationValues.isEnabled) {
            headsUpAnimator.stackTopMargin = topMargin.toInt()
        }
        whenever(notificationRow.intrinsicHeight).thenReturn(100)
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        resetViewStates_hunYTranslationIs(
            expected = -topMargin - stackScrollAlgorithm.mHeadsUpAppearStartAboveScreen
        )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_noStatusBarChip_hunTranslatedToTopOfScreen() {
        val topMargin = 100f
        ambientState.maxHeadsUpTranslation = 2000f
        ambientState.stackTopMargin = topMargin.toInt()
        headsUpAnimator?.stackTopMargin = topMargin.toInt()
        whenever(notificationRow.intrinsicHeight).thenReturn(100)

        val statusBarHeight = 432
        kosmos.fakeSystemBarUtilsProxy.fakeStatusBarHeight = statusBarHeight
        headsUpAnimator!!.updateResources(context)

        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)
        whenever(notificationRow.hasStatusBarChipDuringHeadsUpAnimation()).thenReturn(false)

        resetViewStates_hunYTranslationIs(
            expected = -topMargin - stackScrollAlgorithm.mHeadsUpAppearStartAboveScreen
        )
    }

    @Test
    @EnableFlags(NotificationsHunSharedAnimationValues.FLAG_NAME, StatusBarNotifChips.FLAG_NAME)
    fun resetViewStates_hunAnimatingAway_withStatusBarChip_hunTranslatedToBottomOfStatusBar() {
        val topMargin = 100f
        ambientState.maxHeadsUpTranslation = 2000f
        ambientState.stackTopMargin = topMargin.toInt()
        headsUpAnimator?.stackTopMargin = topMargin.toInt()
        whenever(notificationRow.intrinsicHeight).thenReturn(100)

        val statusBarHeight = 432
        kosmos.fakeSystemBarUtilsProxy.fakeStatusBarHeight = statusBarHeight
        headsUpAnimator!!.updateResources(context)

        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)
        whenever(notificationRow.hasStatusBarChipDuringHeadsUpAnimation()).thenReturn(true)

        resetViewStates_hunYTranslationIs(expected = statusBarHeight - topMargin)
    }

    @Test
    fun resetViewStates_hunAnimatingAway_bottomNotClipped() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.clipBottomAmount).isEqualTo(0)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_hunAnimatingAwayWhileDozing_yTranslationIsInset() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        ambientState.isDozing = true

        resetViewStates_hunYTranslationIs(stackScrollAlgorithm.mHeadsUpInset)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_hunAnimatingAwayWhileDozing_hasStackMargin_changesHunYTranslation() {
        whenever(notificationRow.isHeadsUpAnimatingAway).thenReturn(true)

        ambientState.isDozing = true

        resetViewStates_stackMargin_changesHunYTranslation()
    }

    @Test
    fun resetViewStates_hunsOverlapping_bottomHunClipped() {
        val topHun = mockExpandableNotificationRow()
        whenever(topHun.key).thenReturn("key")
        whenever(topHun.entryAdapter).thenReturn(notificationEntryAdapter)
        val bottomHun = mockExpandableNotificationRow()
        whenever(bottomHun.key).thenReturn("key")
        whenever(bottomHun.entryAdapter).thenReturn(notificationEntryAdapter)
        whenever(topHun.isHeadsUp).thenReturn(true)
        whenever(topHun.isPinned).thenReturn(true)
        whenever(bottomHun.isHeadsUp).thenReturn(true)
        whenever(bottomHun.isPinned).thenReturn(true)

        resetViewStates_hunsOverlapping_bottomHunClipped(topHun, bottomHun)
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_emptyShadeView_isCenteredVertically_withSceneContainer() {
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)
        ambientState.layoutMaxHeight = maxPanelHeight.toInt()

        val stackTop = 200f
        val stackBottom = 2000f
        val stackHeight = stackBottom - stackTop
        ambientState.stackTop = stackTop
        ambientState.stackCutoff = stackBottom

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val centeredY = stackTop + stackHeight / 2f - emptyShadeView.height / 2f
        assertThat(emptyShadeView.viewState.yTranslation).isEqualTo(centeredY)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_emptyShadeView_isCenteredVertically() {
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)
        ambientState.layoutMaxHeight = maxPanelHeight.toInt()

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val marginBottom =
            context.resources.getDimensionPixelSize(R.dimen.notification_panel_margin_bottom)
        val fullHeight = ambientState.layoutMaxHeight + marginBottom - ambientState.stackY
        val centeredY = ambientState.stackY + fullHeight / 2f - emptyShadeView.height / 2f
        assertThat(emptyShadeView.viewState.yTranslation).isEqualTo(centeredY)
    }

    @Test
    fun resetViewStates_hunGoingToShade_viewBecomesOpaque() {
        whenever(notificationRow.isAboveShelf).thenReturn(true)
        ambientState.isShadeExpanded = true
        ambientState.trackedHeadsUpRow = notificationRow
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(1f)
    }

    @Test
    fun resetViewStates_expansionChanging_notificationBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.25f,
            expectedAlpha = 0.0f,
        )
    }

    @Test
    fun resetViewStates_expansionChangingWhileBouncerInTransit_viewBecomesTransparent() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.85f,
            expectedAlpha = 0.0f,
        )
    }

    @Test
    fun resetViewStates_expansionChanging_notificationAlphaUpdated() {
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.6f,
            expectedAlpha = getContentAlpha(0.6f),
        )
    }

    @Test
    fun resetViewStates_largeScreen_expansionChanging_alphaUpdated_largeScreenValue() {
        val expansionFraction = 0.6f
        val surfaceAlpha = 123f
        ambientState.isSmallScreen = false
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(false)
        whenever(largeScreenShadeInterpolator.getNotificationContentAlpha(expansionFraction))
            .thenReturn(surfaceAlpha)

        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = expansionFraction,
            expectedAlpha = surfaceAlpha,
        )
    }

    @Test
    fun expansionChanging_largeScreen_bouncerInTransit_alphaUpdated_bouncerValues() {
        ambientState.isSmallScreen = false
        whenever(mStatusBarKeyguardViewManager.isPrimaryBouncerInTransit).thenReturn(true)
        resetViewStates_expansionChanging_notificationAlphaUpdated(
            expansionFraction = 0.95f,
            expectedAlpha = aboutToShowBouncerProgress(0.95f),
        )
    }

    @Test
    fun resetViewStates_expansionChanging_shelfUpdated() {
        ambientState.shelf = notificationShelf
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = 0.6f
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        verify(notificationShelf)
            .updateState(/* algorithmState= */ any(), /* ambientState= */ eq(ambientState))
    }

    @Test
    fun resetViewStates_isOnKeyguard_viewBecomesTransparent() {
        ambientState.fakeShowingStackOnLockscreen()
        ambientState.hideAmount = 0.25f
        whenever(notificationRow.isHeadsUpState).thenReturn(true)

        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(1f - ambientState.hideAmount)
    }

    @Test
    fun resetViewStates_isOnKeyguard_emptyShadeViewBecomesTransparent() {
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.fractionToShade = 0.25f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val expected = getContentAlpha(ambientState.fractionToShade)
        assertThat(emptyShadeView.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_shadeCollapsed_emptyShadeViewBecomesTransparent() {
        ambientState.expansionFraction = 0f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(emptyShadeView.viewState.alpha).isEqualTo(0f)
    }

    @Test
    fun resetViewStates_isOnKeyguard_emptyShadeViewBecomesOpaque() {
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.fractionToShade = 0.25f
        stackScrollAlgorithm.initView(context)
        hostView.removeAllViews()
        hostView.addView(emptyShadeView)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        val expected = getContentAlpha(ambientState.fractionToShade)
        assertThat(emptyShadeView.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_hiddenShelf_allRowsBecomesTransparent() {
        hostView.removeAllViews()
        val row1 = mockExpandableNotificationRow()
        hostView.addView(row1)
        val row2 = mockExpandableNotificationRow()
        hostView.addView(row2)

        whenever(row1.isHeadsUpState).thenReturn(true)
        whenever(row2.isHeadsUpState).thenReturn(false)

        ambientState.fakeShowingStackOnLockscreen()
        ambientState.hideAmount = 0.25f
        ambientState.dozeAmount = 0.33f
        notificationShelf.viewState.hidden = true
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(row1.viewState.alpha).isEqualTo(1f - ambientState.hideAmount)
        assertThat(row2.viewState.alpha).isEqualTo(1f - ambientState.dozeAmount)
    }

    @Test
    fun resetViewStates_hiddenShelf_shelfAlphaDoesNotChange() {
        val expected = notificationShelf.viewState.alpha
        notificationShelf.viewState.hidden = true
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationShelf.viewState.alpha).isEqualTo(expected)
    }

    @Test
    fun resetViewStates_shelfTopLessThanViewTop_hidesView() {
        notificationRow.viewState.yTranslation = 10f
        notificationShelf.viewState.yTranslation = 0.9f
        notificationShelf.viewState.hidden = false
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(0f)
    }

    @Test
    fun resetViewStates_shelfTopGreaterOrEqualThanViewTop_viewAlphaDoesNotChange() {
        val expected = notificationRow.viewState.alpha
        notificationRow.viewState.yTranslation = 10f
        notificationShelf.viewState.yTranslation = 10f
        notificationShelf.viewState.hidden = false
        ambientState.shelf = notificationShelf
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        assertThat(notificationRow.viewState.alpha).isEqualTo(expected)
    }

    @Test
    @DisableSceneContainer
    fun resetViewStates_noSpaceForFooter_footerHidden() {
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = 0f // no space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    @EnableSceneContainer
    fun resetViewStates_noSpaceForFooter_footerHidden_withSceneContainer() {
        ambientState.isShadeExpanded = true
        ambientState.stackTop = 0f
        ambientState.stackCutoff = 100f
        val footerView = mockFooterView(height = 200) // no space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    fun resetViewStates_clearAllInProgress_hasNonClearableRow_footerVisible() {
        whenever(notificationRow.canViewBeCleared()).thenReturn(false)
        ambientState.isClearAllInProgress = true
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = maxPanelHeight // plenty space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(footerView.viewState.hidden).isFalse()
        assertThat((footerView.viewState as FooterViewState).hideContent).isFalse()
    }

    @Test
    fun resetViewStates_clearAllInProgress_allRowsClearable_footerHidden() {
        whenever(notificationRow.canViewBeCleared()).thenReturn(true)
        ambientState.isClearAllInProgress = true
        ambientState.isShadeExpanded = true
        ambientState.stackEndHeight = maxPanelHeight // plenty space for the footer in the stack
        hostView.addView(footerView)

        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat((footerView.viewState as FooterViewState).hideContent).isTrue()
    }

    @Test
    fun getGapForLocation_onLockscreen_returnsSmallGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f,
                /* onKeyguard= */ true,
            )
        assertThat(gap).isEqualTo(smallGap)
    }

    @Test
    fun getGapForLocation_goingToShade_interpolatesGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0.5f,
                /* onKeyguard= */ true,
            )
        assertThat(gap).isEqualTo(smallGap * 0.5f + bigGap * 0.5f)
    }

    @Test
    fun getGapForLocation_notOnLockscreen_returnsBigGap() {
        val gap =
            stackScrollAlgorithm.getGapForLocation(
                /* fractionToShade= */ 0f,
                /* onKeyguard= */ false,
            )
        assertThat(gap).isEqualTo(bigGap)
    }

    @Test
    fun updateViewWithShelf_viewAboveShelf_viewShown() {
        val viewStart = 0f
        val shelfStart = 1f

        val expandableView = mock<ExpandableView>()
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertThat(expandableViewState.hidden).isFalse()
    }

    @Test
    fun updateViewWithShelf_viewBelowShelf_viewHidden() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock<ExpandableView>()
        whenever(expandableView.isExpandAnimationRunning).thenReturn(false)
        whenever(expandableView.hasExpandingChild()).thenReturn(false)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertThat(expandableViewState.hidden).isTrue()
    }

    @Test
    fun updateViewWithShelf_viewBelowShelfButIsExpanding_viewShown() {
        val shelfStart = 0f
        val viewStart = 1f

        val expandableView = mock<ExpandableView>()
        whenever(expandableView.isExpandAnimationRunning).thenReturn(true)
        whenever(expandableView.hasExpandingChild()).thenReturn(true)

        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = viewStart

        stackScrollAlgorithm.updateViewWithShelf(expandableView, expandableViewState, shelfStart)
        assertThat(expandableViewState.hidden).isFalse()
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endVisible_true() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = false

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* topVisible = */ true,
            /* viewEnd= */ 0f,
            /* hunMax = */ 10f,
        )

        assertThat(expandableViewState.headsUpIsVisible).isTrue()
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_endHidden_false() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* topVisible = */ true,
            /* viewEnd= */ 10f,
            /* hunMax = */ 0f,
        )

        assertThat(expandableViewState.headsUpIsVisible).isFalse()
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_shadeClosed_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ false,
            /* mustStayOnScreen= */ true,
            /* topVisible = */ true,
            /* viewEnd= */ 10f,
            /* hunMax = */ 1f,
        )

        assertThat(expandableViewState.headsUpIsVisible).isTrue()
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_notHUN_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ false,
            /* topVisible = */ true,
            /* viewEnd= */ 10f,
            /* hunMax = */ 1f,
        )

        assertThat(expandableViewState.headsUpIsVisible).isTrue()
    }

    @Test
    fun maybeUpdateHeadsUpIsVisible_topHidden_noUpdate() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.headsUpIsVisible = true

        stackScrollAlgorithm.maybeUpdateHeadsUpIsVisible(
            expandableViewState,
            /* isShadeExpanded= */ true,
            /* mustStayOnScreen= */ true,
            /* topVisible = */ false,
            /* viewEnd= */ 10f,
            /* hunMax = */ 1f,
        )

        assertThat(expandableViewState.headsUpIsVisible).isTrue()
    }

    @Test
    fun clampHunToTop_viewYGreaterThanQqs_viewYUnchanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = 50f

        stackScrollAlgorithm.clampHunToTop(
            /* headsUpTop= */ 10f,
            /* collapsedHeight= */ 1f,
            expandableViewState,
        )

        // qqs (10 + 0) < viewY (50)
        assertThat(expandableViewState.yTranslation).isEqualTo(50f)
    }

    @Test
    fun clampHunToTop_viewYLessThanQqs_viewYChanged() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.yTranslation = -10f

        stackScrollAlgorithm.clampHunToTop(
            /* headsUpTop= */ 10f,
            /* collapsedHeight= */ 1f,
            expandableViewState,
        )

        // qqs (10 + 0) > viewY (-10)
        assertThat(expandableViewState.yTranslation).isEqualTo(10f)
    }

    @Test
    fun clampHunToTop_viewYFarAboveVisibleStack_heightCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = -100f

        stackScrollAlgorithm.clampHunToTop(
            /* headsUpTop= */ 10f,
            /* collapsedHeight= */ 10f,
            expandableViewState,
        )

        // newTranslation = max(10, -100) = 10
        // distToRealY = 10 - (-100f) = 110
        // height = max(20 - 110, 10f)
        assertThat(expandableViewState.height).isEqualTo(10)
    }

    @Test
    fun clampHunToTop_viewYNearVisibleStack_heightTallerThanCollapsed() {
        val expandableViewState = ExpandableViewState()
        expandableViewState.height = 20
        expandableViewState.yTranslation = 5f

        stackScrollAlgorithm.clampHunToTop(
            /* headsUpTop= */ 10f,
            /* collapsedHeight= */ 10f,
            expandableViewState,
        )

        // newTranslation = max(10, 5) = 10
        // distToRealY = 10 - 5 = 5
        // height = max(20 - 5, 10) = 15
        assertThat(expandableViewState.height).isEqualTo(15)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackBelowScreen_round() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 110f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRadius = */ 0f,
            )
        assertThat(currentRoundness).isEqualTo(1f)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAboveScreenBelowPinPoint_halfRound() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 90f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRadius = */ 0f,
            )
        assertThat(currentRoundness).isEqualTo(0.5f)
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_stackAbovePinPoint_notRound() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRadius = */ 0f,
            )
        assertThat(currentRoundness).isZero()
    }

    @Test
    fun computeCornerRoundnessForPinnedHun_originallyRoundAndStackAbovePinPoint_round() {
        val currentRoundness =
            stackScrollAlgorithm.computeCornerRoundnessForPinnedHun(
                /* hostViewHeight= */ 100f,
                /* stackY= */ 0f,
                /* viewMaxHeight= */ 20f,
                /* originalCornerRadius = */ 1f,
            )
        assertThat(currentRoundness).isEqualTo(1f)
    }

    @Test
    @DisableSceneContainer
    fun shadeOpened_hunFullyOverlapsQqsPanel_hunShouldHaveFullShadow() {
        // Given: shade is opened, yTranslation of HUN is 0,
        // the height of HUN equals to the height of QQS Panel,
        // and HUN fully overlaps with QQS Panel
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = false, headerVisibleAmount = 1f)
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
            /* algorithmState = */ algorithmState,
            /* ambientState= */ ambientState,
            /* isTopHun = */ true,
        )

        // Then: full shadow would be applied
        assertThat(childHunView.viewState.zTranslation)
            .isEqualTo(px(R.dimen.heads_up_pinned_elevation))
    }

    @Test
    @DisableSceneContainer
    fun shadeOpened_hunPartiallyOverlapsQQS_hunShouldHavePartialShadow() {
        // Given: shade is opened, yTranslation of HUN is greater than 0,
        // the height of HUN is equal to the height of QQS Panel,
        // and HUN partially overlaps with QQS Panel
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = false, headerVisibleAmount = 1f)
        // Use half of the HUN's height as overlap
        childHunView.viewState.yTranslation = (childHunView.viewState.height + 1 shr 1).toFloat()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
            /* algorithmState = */ algorithmState,
            /* ambientState= */ ambientState,
            /* isTopHun = */ true,
        )

        // Then: HUN should have shadow, but not as full size
        assertThat(childHunView.viewState.zTranslation).isGreaterThan(0.0f)
        assertThat(childHunView.viewState.zTranslation)
            .isLessThan(px(R.dimen.heads_up_pinned_elevation))
    }

    @Test
    @DisableSceneContainer
    fun shadeOpened_hunDoesNotOverlapQQS_hunShouldHaveNoShadow() {
        // Given: shade is opened, yTranslation of HUN is equal to QQS Panel's height,
        // the height of HUN is equal to the height of QQS Panel,
        // and HUN doesn't overlap with QQS Panel
        ambientState.stackTranslation =
            px(R.dimen.qqs_layout_margin_top) + px(R.dimen.qqs_layout_padding_bottom)
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = true, fullyVisible = true, headerVisibleAmount = 1f)
        // HUN doesn't overlap with QQS Panel
        childHunView.viewState.yTranslation =
            ambientState.topPadding + ambientState.stackTranslation
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
            /* algorithmState = */ algorithmState,
            /* ambientState= */ ambientState,
            /* isTopHun = */ true,
        )

        // Then: HUN should not have shadow
        assertThat(childHunView.viewState.zTranslation).isZero()
    }

    @Test
    @DisableSceneContainer
    fun shadeClosed_hunShouldHaveFullShadow() {
        // Given: shade is closed, ambientState.stackTranslation == -ambientState.topPadding,
        // the height of HUN is equal to the height of QQS Panel,
        ambientState.stackTranslation = (-ambientState.topPadding).toFloat()
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = false, fullyVisible = false, headerVisibleAmount = 0f)
        childHunView.viewState.yTranslation = 0f
        // Shade is closed, thus childHunView's headerVisibleAmount is 0
        childHunView.headerVisibleAmount = 0f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
            /* algorithmState = */ algorithmState,
            /* ambientState= */ ambientState,
            /* isTopHun = */ true,
        )

        // Then: HUN should have full shadow
        assertThat(childHunView.viewState.zTranslation)
            .isEqualTo(px(R.dimen.heads_up_pinned_elevation))
    }

    @Test
    @DisableSceneContainer
    fun draggingHunToOpenShade_hunShouldHavePartialShadow() {
        // Given: shade is closed when HUN pops up,
        // now drags down the HUN to open shade
        ambientState.stackTranslation = (-ambientState.topPadding).toFloat()
        // Mock the height of shade
        ambientState.setLayoutMinHeight(1000)
        val childHunView =
            createHunViewMock(isShadeOpen = false, fullyVisible = false, headerVisibleAmount = 0.5f)
        childHunView.viewState.yTranslation = 0f
        // Shade is being opened, thus childHunView's headerVisibleAmount is between 0 and 1
        // use 0.5 as headerVisibleAmount here
        childHunView.headerVisibleAmount = 0.5f
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(childHunView)

        // When: updateChildZValue() is called for the top HUN
        stackScrollAlgorithm.updateChildZValue(
            /* i= */ 0,
            /* childrenOnTop= */ 0.0f,
            /* algorithmState = */ algorithmState,
            /* ambientState= */ ambientState,
            /* isTopHun = */ true,
        )

        // Then: HUN should have shadow, but not as full size
        assertThat(childHunView.viewState.zTranslation).isGreaterThan(0.0f)
        assertThat(childHunView.viewState.zTranslation)
            .isLessThan(px(R.dimen.heads_up_pinned_elevation))
    }

    @Test
    fun aodToLockScreen_hasPulsingNotification_pulsingNotificationRowDoesNotChange() {
        // Given: Before AOD to LockScreen, there was a pulsing notification
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: during AOD to LockScreen, any dozeAmount between (0, 1.0) is equivalent as a middle
        // stage; here we use 0.5 for testing.
        // stackScrollAlgorithm.updatePulsingStates is called
        ambientState.dozeAmount = 0.5f
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should still be pulsingNotificationView
        assertThat(ambientState.isPulsingRow(pulsingNotificationView)).isTrue()
    }

    @Test
    fun deviceOnAod_hasPulsingNotification_recordPulsingNotificationRow() {
        // Given: Device is on AOD, there is a pulsing notification
        // ambientState.pulsingRow is null before stackScrollAlgorithm.updatePulsingStates
        ambientState.dozeAmount = 1.0f
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(null)

        // When: stackScrollAlgorithm.updatePulsingStates is called
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should record the pulsingNotificationView
        assertThat(ambientState.isPulsingRow(pulsingNotificationView)).isTrue()
    }

    @Test
    fun deviceOnLockScreen_hasPulsingNotificationBefore_clearPulsingNotificationRowRecord() {
        // Given: Device finished AOD to LockScreen, there was a pulsing notification, and
        // ambientState.pulsingRow was not null before AOD to LockScreen
        // pulsingNotificationView.showingPulsing() returns false since the device is on LockScreen
        ambientState.dozeAmount = 0.0f
        val pulsingNotificationView = createPulsingViewMock()
        whenever(pulsingNotificationView.showingPulsing()).thenReturn(false)
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: stackScrollAlgorithm.updatePulsingStates is called
        stackScrollAlgorithm.updatePulsingStates(algorithmState, ambientState)

        // Then: ambientState.pulsingRow should be null
        assertThat(ambientState.isPulsingRow(null)).isTrue()
    }

    @Test
    fun aodToLockScreen_hasPulsingNotification_pulsingNotificationRowShowAtFullHeight() {
        // Given: Before AOD to LockScreen, there was a pulsing notification
        val pulsingNotificationView = createPulsingViewMock()
        val algorithmState = StackScrollAlgorithm.StackScrollAlgorithmState()
        algorithmState.visibleChildren.add(pulsingNotificationView)
        ambientState.setPulsingRow(pulsingNotificationView)

        // When: during AOD to LockScreen, any dozeAmount between (0, 1.0) is equivalent as a middle
        // stage; here we use 0.5 for testing. The expansionFraction is also 0.5.
        // stackScrollAlgorithm.resetViewStates is called.
        ambientState.dozeAmount = 0.5f
        setExpansionFractionWithoutShelfDuringAodToLockScreen(
            ambientState,
            algorithmState,
            fraction = 0.5f,
        )
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // Then: pulsingNotificationView should show at full height
        assertThat(pulsingNotificationView.viewState.height)
            .isEqualTo(stackScrollAlgorithm.getMaxAllowedChildHeight(pulsingNotificationView))

        // After: reset dozeAmount and expansionFraction
        ambientState.dozeAmount = 0f
        setExpansionFractionWithoutShelfDuringAodToLockScreen(
            ambientState,
            algorithmState,
            fraction = 1f,
        )
    }

    // region shouldPinHunToBottomOfExpandedQs
    @Test
    fun shouldHunBeVisibleWhenScrolled_mustStayOnScreenFalse_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ false,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /*headsUpOnKeyguard=*/ false,
                )
            )
            .isFalse()
    }

    @Test
    fun shouldPinHunToBottomOfExpandedQs_headsUpIsVisible_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ true,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /*headsUpOnKeyguard=*/ false,
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_showingPulsing_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ true,
                    /* isOnKeyguard=*/ false,
                    /* headsUpOnKeyguard= */ false,
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_isOnKeyguard_false() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ true,
                    /* headsUpOnKeyguard= */ false,
                )
            )
            .isFalse()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_isNotOnKeyguard_true() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ false,
                    /* headsUpOnKeyguard= */ false,
                )
            )
            .isTrue()
    }

    @Test
    fun shouldHunBeVisibleWhenScrolled_headsUpOnKeyguard_true() {
        assertThat(
                stackScrollAlgorithm.shouldHunBeVisibleWhenScrolled(
                    /* mustStayOnScreen= */ true,
                    /* headsUpIsVisible= */ false,
                    /* showingPulsing= */ false,
                    /* isOnKeyguard=*/ true,
                    /* headsUpOnKeyguard= */ true,
                )
            )
            .isTrue()
    }

    @Test
    fun shouldHunAppearFromBottom_hunAtMaxHunTranslation() {
        ambientState.maxHeadsUpTranslation = 400f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation = ambientState.maxHeadsUpTranslation - height // move it to the max
            }

        assertThat(stackScrollAlgorithm.shouldHunAppearFromBottom(ambientState, viewState)).isTrue()
    }

    @Test
    fun shouldHunAppearFromBottom_hunBelowMaxHunTranslation() {
        ambientState.maxHeadsUpTranslation = 400f
        val viewState =
            ExpandableViewState().apply {
                height = 100
                yTranslation =
                    ambientState.maxHeadsUpTranslation - height - 1 // move it below the max
            }

        assertThat(stackScrollAlgorithm.shouldHunAppearFromBottom(ambientState, viewState))
            .isFalse()
    }

    // endregion

    private fun createHunViewMock(
        isShadeOpen: Boolean,
        fullyVisible: Boolean,
        headerVisibleAmount: Float,
    ) =
        mock<ExpandableNotificationRow>().apply {
            val childViewStateMock = createHunChildViewState(isShadeOpen, fullyVisible)
            whenever(this.viewState).thenReturn(childViewStateMock)

            whenever(this.mustStayOnScreen()).thenReturn(true)
            whenever(this.headerVisibleAmount).thenReturn(headerVisibleAmount)
        }

    private fun createHunChildViewState(isShadeOpen: Boolean, fullyVisible: Boolean) =
        ExpandableViewState().apply {
            // Mock the HUN's height with ambientState.topPadding +
            // ambientState.stackTranslation
            height = (ambientState.topPadding + ambientState.stackTranslation).toInt()
            if (isShadeOpen && fullyVisible) {
                yTranslation = ambientState.topPadding + ambientState.stackTranslation
            } else {
                yTranslation = 0f
            }
            headsUpIsVisible = fullyVisible
        }

    private fun createPulsingViewMock() =
        mock<ExpandableNotificationRow>().apply {
            whenever(this.viewState).thenReturn(ExpandableViewState())
            whenever(this.showingPulsing()).thenReturn(true)
        }

    private fun setExpansionFractionWithoutShelfDuringAodToLockScreen(
        ambientState: AmbientState,
        algorithmState: StackScrollAlgorithm.StackScrollAlgorithmState,
        fraction: Float,
    ) {
        // showingShelf: false
        algorithmState.firstViewInShelf = null
        // scrimPadding: 0, because device is on lock screen
        ambientState.setStatusBarState(StatusBarState.KEYGUARD)
        ambientState.dozeAmount = 0.0f
        // set stackEndHeight and stackHeight
        // ExpansionFractionWithoutShelf == stackHeight / stackEndHeight
        ambientState.stackEndHeight = 100f
        ambientState.interpolatedStackHeight = ambientState.stackEndHeight * fraction
    }

    private fun resetViewStates_hunYTranslationIs(expected: Float) {
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        assertThat(notificationRow.viewState.yTranslation).isEqualTo(expected)
    }

    private fun resetViewStates_stackMargin_changesHunYTranslation() {
        val stackTopMargin = bigGap.toInt() // a gap smaller than the headsUpInset
        val headsUpTranslationY = stackScrollAlgorithm.mHeadsUpInset - stackTopMargin

        // we need the shelf to mock the real-life behaviour of StackScrollAlgorithm#updateChild
        ambientState.shelf = notificationShelf

        // split shade case with top margin introduced by shade's status bar
        ambientState.stackTopMargin = stackTopMargin
        stackScrollAlgorithm.resetViewStates(ambientState, 0)

        // heads up translation should be decreased by the top margin
        assertThat(notificationRow.viewState.yTranslation).isEqualTo(headsUpTranslationY)
    }

    private fun resetViewStates_hunsOverlapping_bottomHunClipped(
        topHun: ExpandableNotificationRow,
        bottomHun: ExpandableNotificationRow,
    ) {
        val topHunHeight =
            mContext.resources.getDimensionPixelSize(R.dimen.notification_content_min_height)
        val bottomHunHeight =
            mContext.resources.getDimensionPixelSize(R.dimen.notification_max_heads_up_height)
        whenever(topHun.intrinsicHeight).thenReturn(topHunHeight)
        whenever(bottomHun.intrinsicHeight).thenReturn(bottomHunHeight)

        // we need the shelf to mock the real-life behaviour of StackScrollAlgorithm#updateChild
        ambientState.shelf = notificationShelf

        // add two overlapping HUNs
        hostView.removeAllViews()
        hostView.addView(topHun)
        hostView.addView(bottomHun)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        // the height shouldn't change
        assertThat(topHun.viewState.height).isEqualTo(topHunHeight)
        assertThat(bottomHun.viewState.height).isEqualTo(bottomHunHeight)
        // the HUN at the bottom should be clipped
        assertThat(topHun.viewState.clipBottomAmount).isEqualTo(0)
        assertThat(bottomHun.viewState.clipBottomAmount).isEqualTo(bottomHunHeight - topHunHeight)
    }

    private fun resetViewStates_expansionChanging_notificationAlphaUpdated(
        expansionFraction: Float,
        expectedAlpha: Float,
    ) {
        ambientState.isExpansionChanging = true
        ambientState.expansionFraction = expansionFraction
        stackScrollAlgorithm.initView(context)

        stackScrollAlgorithm.resetViewStates(ambientState, /* speedBumpIndex= */ 0)

        expect.that(notificationRow.viewState.alpha).isEqualTo(expectedAlpha)
    }

    /** fakes the notification row under test, to be a HUN in a fully opened shade */
    private fun fakeHunInShade(
        collapsedHeight: Int,
        intrinsicHeight: Int,
        headsUpTop: Float,
        headsUpBottom: Float = headsUpTop + intrinsicHeight, // assume all the space available
        stackTop: Float,
        stackCutoff: Float = 2000f,
        fullStackHeight: Float = 3000f,
    ) {
        ambientState.headsUpTop = headsUpTop
        if (NotificationsHunSharedAnimationValues.isEnabled) {
            headsUpAnimator.headsUpAppearHeightBottom = headsUpBottom.roundToInt()
        } else {
            ambientState.headsUpBottom = headsUpBottom
        }
        ambientState.stackTop = stackTop
        ambientState.stackCutoff = stackCutoff

        // shade is fully open
        ambientState.expansionFraction = 1.0f
        with(fullStackHeight) {
            ambientState.interpolatedStackHeight = this
            ambientState.stackEndHeight = this
        }
        stackScrollAlgorithm.setIsExpanded(true)

        whenever(notificationRow.headerVisibleAmount).thenReturn(1.0f)
        whenever(notificationRow.mustStayOnScreen()).thenReturn(true)
        whenever(notificationRow.isHeadsUp).thenReturn(true)
        whenever(notificationRow.collapsedHeight).thenReturn(collapsedHeight)
        whenever(notificationRow.intrinsicHeight).thenReturn(intrinsicHeight)
    }
}

private fun mockExpandableNotificationRow(): ExpandableNotificationRow {
    return mock<ExpandableNotificationRow>().apply {
        whenever(viewState).thenReturn(ExpandableViewState())
    }
}

private fun mockFooterView(height: Int): FooterView {
    return mock<FooterView>().apply {
        whenever(viewState).thenReturn(FooterViewState())
        whenever(intrinsicHeight).thenReturn(height)
    }
}

private fun AmbientState.fakeShowingStackOnLockscreen() {
    if (SceneContainerFlag.isEnabled) {
        isShowingStackOnLockscreen = true
        lockscreenStackFadeInProgress = 1f // stack is fully opaque
    } else {
        setStatusBarState(StatusBarState.KEYGUARD)
    }
}

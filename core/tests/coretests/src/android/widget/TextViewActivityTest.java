/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.widget;

import static android.app.PendingIntent.FLAG_IMMUTABLE;
import static android.widget.espresso.CustomViewActions.longPressAtRelativeCoordinates;
import static android.widget.espresso.DragHandleUtils.assertNoSelectionHandles;
import static android.widget.espresso.DragHandleUtils.onHandleView;
import static android.widget.espresso.TextViewActions.Handle;
import static android.widget.espresso.TextViewActions.clickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleClickOnTextAtIndex;
import static android.widget.espresso.TextViewActions.doubleTapAndDragHandle;
import static android.widget.espresso.TextViewActions.doubleTapAndDragOnText;
import static android.widget.espresso.TextViewActions.doubleTapHandle;
import static android.widget.espresso.TextViewActions.dragHandle;
import static android.widget.espresso.TextViewActions.longPressAndDragHandle;
import static android.widget.espresso.TextViewActions.longPressAndDragOnText;
import static android.widget.espresso.TextViewActions.longPressHandle;
import static android.widget.espresso.TextViewActions.longPressOnTextAtIndex;
import static android.widget.espresso.TextViewAssertions.doesNotHaveStyledText;
import static android.widget.espresso.TextViewAssertions.hasInsertionPointerAtIndex;
import static android.widget.espresso.TextViewAssertions.hasSelection;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.longClick;
import static androidx.test.espresso.action.ViewActions.pressKey;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.PendingIntent;
import android.app.RemoteAction;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.text.InputType;
import android.text.Selection;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.LinkMovementMethod;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.textclassifier.SelectionEvent;
import android.view.textclassifier.TextClassification;
import android.view.textclassifier.TextClassificationManager;
import android.view.textclassifier.TextClassifier;
import android.view.textclassifier.TextLinks;
import android.view.textclassifier.TextLinksParams;
import android.view.textclassifier.TextSelection;
import android.widget.espresso.CustomViewActions.RelativeCoordinatesProvider;

import androidx.test.InstrumentationRegistry;
import androidx.test.espresso.action.EspressoKey;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;
import androidx.test.rule.ActivityTestRule;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Tests the TextView widget from an Activity
 */
@RunWith(AndroidJUnit4.class)
@MediumTest
public class TextViewActivityTest {

    @Rule
    public ActivityTestRule<TextViewActivity> mActivityRule = new ActivityTestRule<>(
            TextViewActivity.class);

    private Activity mActivity;
    private Instrumentation mInstrumentation;
    private UiDevice mDevice;
    private FloatingToolbarUtils mToolbar;

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mDevice = UiDevice.getInstance(mInstrumentation);
        mDevice.wakeUp();
        mToolbar = new FloatingToolbarUtils();
        TextClassificationManager tcm = mActivity.getSystemService(
                TextClassificationManager.class);
        tcm.setTextClassifier(TextClassifier.NO_OP);
        tcm.setTextClassificationSessionFactory(null);
    }

    @Test
    public void testTypedTextIsOnScreen() {
        final String helloWorld = "Hello world!";
        // We use replaceText instead of typeTextIntoFocusedView to input text to avoid
        // unintentional interactions with software keyboard.
        setText(helloWorld);

        onView(withId(R.id.textview)).check(matches(withText(helloWorld)));
    }
    @Test
    public void testPositionCursorAtTextAtIndex() {
        final String helloWorld = "Hello world!";
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("world")));

        // Delete text at specified index and see if we got the right one.
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_FORWARD_DEL));
        onView(withId(R.id.textview)).check(matches(withText("Hello orld!")));
    }

    @Test
    public void testPositionCursorAtTextAtIndex_arabic() {
        // Arabic text. The expected cursorable boundary is
        // | \u0623 \u064F | \u067A | \u0633 \u0652 |
        final String text = "\u0623\u064F\u067A\u0633\u0652";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(1));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(0), is(2))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(2));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(3));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(3));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(4));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(3), is(5))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(5));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(5));
    }

    @Test
    public void testPositionCursorAtTextAtIndex_devanagari() {
        // Devanagari text. The expected cursorable boundary is | \u0915 \u093E |
        final String text = "\u0915\u093E";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(0));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(1));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(anyOf(is(0), is(2))));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(2));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(2));
    }

    @Test
    public void testLongPressToSelect() {
        final String helloWorld = "Hello Kirk!";
        onView(withId(R.id.textview)).perform(click());
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(helloWorld.indexOf("Kirk")));

        onView(withId(R.id.textview)).check(hasSelection("Kirk"));
    }

    @Test
    public void testLongPressEmptySpace() {
        final String helloWorld = "Hello big round sun!";
        setText(helloWorld);
        // Move cursor somewhere else
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(helloWorld.indexOf("big")));
        // Long-press at end of line.
        onView(withId(R.id.textview)).perform(longPressAtRelativeCoordinates(
                RelativeCoordinatesProvider.HorizontalReference.RIGHT, -5,
                RelativeCoordinatesProvider.VerticalReference.CENTER, 0));

        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(helloWorld.length()));
    }

    @Test
    public void testLongPressAndDragToSelect() {
        final String helloWorld = "Hello little handsome boy!";
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(helloWorld.indexOf("little"), helloWorld.indexOf(" boy!")));

        onView(withId(R.id.textview)).check(hasSelection("little handsome"));
    }

    @Test
    public void testLongPressAndDragToSelect_emoji() {
        final String text = "\uD83D\uDE00\uD83D\uDE01\uD83D\uDE02\uD83D\uDE03";
        setText(text);

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 6));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE02"));

        onView(withId(R.id.textview)).perform(click());

        onView(withId(R.id.textview)).perform(longPressAndDragOnText(4, 2));
        onView(withId(R.id.textview)).check(hasSelection("\uD83D\uDE01"));
    }

    @Test
    public void testDragAndDrop() {
        final String text = "abc def ghi.";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("e")));

        onView(withId(R.id.textview)).perform(
                longPressAndDragOnText(text.indexOf("e"), text.length()));

        onView(withId(R.id.textview)).check(matches(withText("abc ghi.def")));
        onView(withId(R.id.textview)).check(hasSelection(""));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex("abc ghi.def".length()));

        // Test undo returns to the original state.
        onView(withId(R.id.textview)).perform(pressKey(
                (new EspressoKey.Builder()).withCtrlPressed(true).withKeyCode(KeyEvent.KEYCODE_Z)
                        .build()));
        onView(withId(R.id.textview)).check(matches(withText(text)));
    }

    @Test
    public void testDoubleTapToSelect() {
        final String helloWorld = "Hello SuetYi!";
        setText(helloWorld);

        onView(withId(R.id.textview)).perform(
                doubleClickOnTextAtIndex(helloWorld.indexOf("SuetYi")));

        onView(withId(R.id.textview)).check(hasSelection("SuetYi"));
    }

    @Test
    public void testDoubleTapAndDragToSelect() {
        final String helloWorld = "Hello young beautiful person!";
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(doubleTapAndDragOnText(helloWorld.indexOf("young"),
                        helloWorld.indexOf(" person!")));

        onView(withId(R.id.textview)).check(hasSelection("young beautiful"));
    }

    @Test
    public void testDoubleTapAndDragToSelect_multiLine() {
        final String helloWorld = "abcd\n" + "efg\n" + "hijklm\n" + "nop";
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf("m"), helloWorld.indexOf("a")));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijklm"));
    }

    @Test
    public void testSelectBackwordsByTouch() {
        final String helloWorld = "Hello king of the Jungle!";
        setText(helloWorld);
        onView(withId(R.id.textview)).perform(
                doubleTapAndDragOnText(helloWorld.indexOf(" Jungle!"), helloWorld.indexOf("king")));

        onView(withId(R.id.textview)).check(hasSelection("king of the"));
    }

    @Test
    public void testToolbarAppearsAfterSelection() {
        final String text = "Toolbar appears after selection.";
        setText(text);
        onView(withId(R.id.textview)).perform(
                longPressOnTextAtIndex(text.indexOf("appears")));

        mToolbar.assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarAppearsAfterSelection_withFirstStringLtrAlgorithmAndRtlHint()
            throws Throwable {
        // after the hint layout change, the floating toolbar was not visible in the case below
        // this test tests that the floating toolbar is displayed on the screen and is visible to
        // user.
        mActivityRule.runOnUiThread(() -> {
            final TextView textView = mActivity.findViewById(R.id.textview);
            textView.setTextDirection(TextView.TEXT_DIRECTION_FIRST_STRONG_LTR);
            textView.setInputType(InputType.TYPE_CLASS_TEXT);
            textView.setSingleLine(true);
            textView.setHint("الروبوت");
        });
        mInstrumentation.waitForIdleSync();

        setText("test");
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(1));
        mToolbar.clickFloatingToolbarItem(mActivity.getString(com.android.internal.R.string.cut));
        onView(withId(R.id.textview)).perform(longClick());

        mToolbar.assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarAppearsAfterLinkClicked() throws Throwable {
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.textview);
        int position = (textLink.getStart() + textLink.getEnd()) / 2;
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(position));
        mToolbar.assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarAppearsAfterLinkClickedNonselectable() throws Throwable {
        final TextView textView = mActivity.findViewById(R.id.nonselectable_textview);
        final TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        final int position = (textLink.getStart() + textLink.getEnd()) / 2;

        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(position));
        mToolbar.assertFloatingToolbarIsDisplayed();
        assertTrue(textView.hasSelection());

        // toggle
        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(position));
        mToolbar.waitForFloatingToolbarPopup();
        assertFalse(textView.hasSelection());

        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(position));
        mToolbar.assertFloatingToolbarIsDisplayed();
        assertTrue(textView.hasSelection());

        // click outside
        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(0));
        assertFalse(textView.hasSelection());
    }

    @Test
    public void testToolbarAppearsAccessibilityLongClick() throws Throwable {
        final String text = "Toolbar appears after performing accessibility's ACTION_LONG_CLICK.";
        mActivityRule.runOnUiThread(() -> {
            final TextView textView = mActivity.findViewById(R.id.textview);
            final Bundle args = new Bundle();
            textView.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, args);
        });
        mInstrumentation.waitForIdleSync();

        mToolbar.assertFloatingToolbarIsDisplayed();
    }

    @Test
    public void testToolbarMenuItemClickAfterSelectionChange() throws Throwable {
        final MenuItem[] latestItem = new MenuItem[1];
        final MenuItem[] clickedItem = new MenuItem[1];
        final String text = "abcd efg hijk";
        mActivityRule.runOnUiThread(() -> {
            final TextView textView = mActivity.findViewById(R.id.textview);
            textView.setText(text);
            textView.setCustomSelectionActionModeCallback(
                    new ActionModeCallbackAdapter() {
                        @Override
                        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                            menu.clear();
                            latestItem[0] = menu.add("Item");
                            return true;
                        }

                        @Override
                        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                            clickedItem[0] = item;
                            return true;
                        }
                    });
        });
        mInstrumentation.waitForIdleSync();

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("f")));

        // Change the selection so that the menu items are refreshed.
        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, 0));
        mToolbar.assertFloatingToolbarIsDisplayed();

        mToolbar.clickFloatingToolbarItem("Item");
        mInstrumentation.waitForIdleSync();

        assertEquals(latestItem[0], clickedItem[0]);
    }

    @Test
    public void testSelectionOnCreateActionModeReturnsFalse() throws Throwable {
        final String text = "hello world";
        mActivityRule.runOnUiThread(() -> {
            final TextView textView = mActivity.findViewById(R.id.textview);
            textView.setText(text);
            textView.setCustomSelectionActionModeCallback(
                    new ActionMode.Callback() {
                        @Override
                        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                            return false;
                        }

                        @Override
                        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                            return false;
                        }

                        @Override
                        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                            return false;
                        }


                        @Override
                        public void onDestroyActionMode(ActionMode mode) {
                        }
                    });
        });
        mInstrumentation.waitForIdleSync();
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("d")));
        mInstrumentation.waitForIdleSync();
        assertNoSelectionHandles();
    }

    @Test
    public void testSelectionRemovedWhenNonselectableTextLosesFocus() throws Throwable {
        final TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        final int position = (textLink.getStart() + textLink.getEnd()) / 2;
        final TextView textView = mActivity.findViewById(R.id.nonselectable_textview);
        mActivityRule.runOnUiThread(() -> textView.setFocusableInTouchMode(true));

        onView(withId(R.id.nonselectable_textview)).perform(clickOnTextAtIndex(position));
        mToolbar.assertFloatingToolbarIsDisplayed();
        assertTrue(textView.hasSelection());

        mActivityRule.runOnUiThread(() -> textView.clearFocus());
        mInstrumentation.waitForIdleSync();

        assertFalse(textView.hasSelection());
    }

    @Test
    public void testSelectionRemovedFromNonselectableTextWhenWindowLosesFocus() throws Throwable {
        TextLinks.TextLink textLink = addLinkifiedTextToTextView(R.id.nonselectable_textview);
        int nonselectablePosition = (textLink.getStart() + textLink.getEnd()) / 2;
        TextView nonselectableTextView = mActivity.findViewById(R.id.nonselectable_textview);

        onView(withId(R.id.nonselectable_textview))
                .perform(clickOnTextAtIndex(nonselectablePosition));
        mToolbar.assertFloatingToolbarIsDisplayed();
        assertTrue(nonselectableTextView.hasSelection());

        mDevice.openNotification();
        Thread.sleep(2000);
        mDevice.pressBack();
        Thread.sleep(2000);

        assertFalse(nonselectableTextView.hasSelection());
    }

    private TextLinks.TextLink addLinkifiedTextToTextView(int id) throws Throwable {
        TextView textView = mActivity.findViewById(id);
        useSystemDefaultTextClassifier();
        TextClassificationManager textClassificationManager =
                mActivity.getSystemService(TextClassificationManager.class);
        TextClassifier textClassifier = textClassificationManager.getTextClassifier();
        Spannable content = new SpannableString("Call me at +19148277737");
        TextLinks.Request request = new TextLinks.Request.Builder(content).build();
        TextLinks links = textClassifier.generateLinks(request);
        TextLinksParams applyParams = new TextLinksParams.Builder()
                .setApplyStrategy(TextLinks.APPLY_STRATEGY_REPLACE)
                .build();
        applyParams.apply(content, links);

        mActivityRule.runOnUiThread(() -> {
            textView.setText(content);
            textView.setMovementMethod(LinkMovementMethod.getInstance());
        });
        mInstrumentation.waitForIdleSync();

        // Wait for the UI thread to refresh
        Thread.sleep(1000);

        return links.getLinks().iterator().next();
    }

    @Test
    public void testToolbarAndInsertionHandle() throws Throwable {
        final String text = "text";
        setText(text);
        Thread.sleep(500);
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());

        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.copy));
        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.cut));
    }

    @Test
    public void testToolbarAndSelectionHandle() {
        final String text = "abcd efg hijk";
        setText(text);

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf("f")));
        mToolbar.assertFloatingToolbarIsDisplayed();

        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.cut));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        mToolbar.assertFloatingToolbarIsDisplayed();

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.length()));
        mToolbar.assertFloatingToolbarIsDisplayed();

        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.cut));
    }

    @Test
    public void testInsertionHandle() {
        final String text = "abcd efg hijk ";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("a")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));
    }

    @Test
    public void testInsertionHandle_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("f")));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(dragHandle(textView, Handle.INSERTION, text.indexOf('i')));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.indexOf("i")));
    }

    private void enableFlagsForInsertionHandleGestures() {
        final TextView textView = mActivity.findViewById(R.id.textview);
        final Editor editor = textView.getEditorForTesting();
        editor.setFlagCursorDragFromAnywhereEnabled(true);
        editor.setFlagInsertionHandleGesturesEnabled(true);
        // Note: We don't need to reset these flags explicitly at the end of each test, because a
        // fresh TextView and Editor will be created for each test.
    }

    @Test
    public void testInsertionHandle_touchThrough() {
        enableFlagsForInsertionHandleGestures();
        testInsertionHandle();
        testInsertionHandle_multiLine();
    }

    @Test
    public void testInsertionHandle_longPressToSelect() {
        enableFlagsForInsertionHandleGestures();
        final TextView textView = mActivity.findViewById(R.id.textview);

        final String text = "hello the world";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle).perform(longPressHandle(textView));
        onView(withId(R.id.textview)).check(hasSelection("world"));
    }

    @Test
    public void testInsertionHandle_longPressAndDragToSelect() {
        enableFlagsForInsertionHandleGestures();
        final TextView textView = mActivity.findViewById(R.id.textview);
        final String text = "hello the world";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(longPressAndDragHandle(textView, Handle.INSERTION, text.indexOf('t')));
        onView(withId(R.id.textview)).check(hasSelection("the world"));
    }

    @Test
    public void testInsertionHandle_doubleTapToSelect() {
        enableFlagsForInsertionHandleGestures();
        final TextView textView = mActivity.findViewById(R.id.textview);

        final String text = "hello the world";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle).perform(doubleTapHandle(textView));
        onView(withId(R.id.textview)).check(hasSelection("world"));
    }

    @Test
    public void testInsertionHandle_doubleTapAndDragToSelect() {
        enableFlagsForInsertionHandleGestures();
        final TextView textView = mActivity.findViewById(R.id.textview);

        final String text = "hello the world";
        setText(text);

        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onView(withId(R.id.textview)).check(hasInsertionPointerAtIndex(text.length()));

        onHandleView(com.android.internal.R.id.insertion_handle)
                .perform(doubleTapAndDragHandle(textView, Handle.INSERTION, text.indexOf('t')));
        onView(withId(R.id.textview)).check(hasSelection("the world"));
    }

    @Test
    public void testSelectionHandles() {
        final String text = "abcd efg hijk lmn";
        setText(text);

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('k') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg hijk"));
    }

    @Test
    public void testSelectionHandles_bidi() {
        final String text = "abc \u0621\u0622\u0623 def";
        setText(text);

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0622')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));

        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u0623'),
                        false));
        onView(withId(R.id.textview)).check(hasSelection("\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u0621'),
                        false));
        onView(withId(R.id.textview)).check(hasSelection("\u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abc \u0621\u0622\u0623"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.length()));
        onView(withId(R.id.textview)).check(hasSelection("abc \u0621\u0622\u0623 def"));
    }

    @Test
    public void testSelectionHandles_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('e')));
        onView(withId(R.id.textview)).check(hasSelection("efg\nhijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('n') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk\nlmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('r') + 1));
        onView(withId(R.id.textview)).check(hasSelection("abcd\nefg\nhijk\nlmn\nopqr"));
    }

    @Suppress // Consistently failing.
    @Test
    public void testSelectionHandles_multiLine_rtl() {
        // Arabic text.
        final String text = "\u062A\u062B\u062C\n" + "\u062D\u062E\u062F\n"
                + "\u0630\u0631\u0632\n" + "\u0633\u0634\u0635\n" + "\u0636\u0637\u0638\n"
                + "\u0639\u063A\u063B";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('\u0634')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u062E')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062D'), text.indexOf('\u0635') + 1)));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('\u062A')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062A'), text.indexOf('\u0635') + 1)));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('\u0638')));
        onView(withId(R.id.textview)).check(hasSelection(
                text.substring(text.indexOf('\u062A'), text.indexOf('\u0638') + 1)));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('\u063B')));
        onView(withId(R.id.textview)).check(hasSelection(text));
    }

    @Test
    public void testSelectionHandles_doesNotPassAnotherHandle() {
        final String text = "abcd efg hijk lmn";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('l')));
        onView(withId(R.id.textview)).check(hasSelection("g"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("e"));
    }

    @Test
    public void testSelectionHandles_doesNotPassAnotherHandle_multiLine() {
        final String text = "abcd\n" + "efg\n" + "hijk\n" + "lmn\n" + "opqr";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('r') + 1));
        onView(withId(R.id.textview)).check(hasSelection("k"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('a')));
        onView(withId(R.id.textview)).check(hasSelection("h"));
    }

    @Test
    public void testSelectionHandles_snapToWordBoundary() {
        final String text = "abcd efg hijk lmn opqr";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('f')));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('d') + 1));
        onView(withId(R.id.textview)).check(hasSelection("efg hijk"));


        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('c')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('d')));
        onView(withId(R.id.textview)).check(hasSelection("d efg hijk"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('b')));
        onView(withId(R.id.textview)).check(hasSelection("bcd efg hijk"));

        onView(withId(R.id.textview)).perform(click());
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('n')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('o')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('q')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn opqr"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('p')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn o"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('r')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn opq"));
    }

    @Test
    public void testSelectionHandles_snapToWordBoundary_multiLine() {
        final String text = "abcd efg\n" + "hijk lmn\n" + "opqr stu";
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('m')));

        final TextView textView = mActivity.findViewById(R.id.textview);

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('c')));
        onView(withId(R.id.textview)).check(hasSelection("abcd efg\nhijk lmn"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('g')));
        onView(withId(R.id.textview)).check(hasSelection("g\nhijk lmn"));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, text.indexOf('m')));
        onView(withId(R.id.textview)).check(hasSelection("lmn"));

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('i')));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('u')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn\nopqr stu"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('p')));
        onView(withId(R.id.textview)).check(hasSelection("hijk lmn\no"));

        onHandleView(com.android.internal.R.id.selection_end_handle)
                .perform(dragHandle(textView, Handle.SELECTION_END, text.indexOf('i')));
        onView(withId(R.id.textview)).check(hasSelection("hijk"));
    }

    @Test
    public void testSelectionHandles_visibleEvenWithEmptyMenu() {
        ((TextView) mActivity.findViewById(R.id.textview)).setCustomSelectionActionModeCallback(
                new ActionModeCallbackAdapter() {
                    @Override
                    public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                        menu.clear();
                        return true;
                    }

                    @Override
                    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                        menu.clear();
                        return true;
                    }
                });
        final String text = "abcd efg hijk lmn";
        setText(text);

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('f')));

        onHandleView(com.android.internal.R.id.selection_start_handle)
                .check(matches(isDisplayed()));
        onHandleView(com.android.internal.R.id.selection_end_handle)
                .check(matches(isDisplayed()));
    }

    @Test
    public void testSetSelectionAndActionMode() throws Throwable {
        final TextView textView = mActivity.findViewById(R.id.textview);
        final ActionMode.Callback amCallback = mock(ActionMode.Callback.class);
        when(amCallback.onCreateActionMode(any(ActionMode.class), any(Menu.class)))
                .thenReturn(true);
        when(amCallback.onPrepareActionMode(any(ActionMode.class), any(Menu.class)))
                .thenReturn(true);
        textView.setCustomSelectionActionModeCallback(amCallback);

        final String text = "abc def";
        setText(text);
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        mInstrumentation.waitForIdleSync();
        // Don't automatically start action mode.
        verify(amCallback, never()).onCreateActionMode(any(ActionMode.class), any(Menu.class));
        // Make sure that "Select All" is included in the selection action mode when the entire text
        // is not selected.
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('e')));
        mToolbar.assertFloatingToolbarIsDisplayed();
        // Changing the selection range by API should not interrupt the selection action mode.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, 3));
        mInstrumentation.waitForIdleSync();
        mToolbar.assertFloatingToolbarIsDisplayed();
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        // Make sure that "Select All" is no longer included when the entire text is selected by
        // API.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        mInstrumentation.waitForIdleSync();

        mToolbar.assertFloatingToolbarIsDisplayed();
        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.selectAll));
        // Make sure that shrinking the selection range to cursor (an empty range) by API
        // terminates selection action mode and does not trigger the insertion action mode.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0));
        mInstrumentation.waitForIdleSync();

        // Make sure that user click can trigger the insertion action mode.
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.length()));
        onHandleView(com.android.internal.R.id.insertion_handle).perform(click());
        mToolbar.assertFloatingToolbarIsDisplayed();
        // Make sure that an existing insertion action mode keeps alive after the insertion point is
        // moved by API.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0));
        mInstrumentation.waitForIdleSync();

        mToolbar.assertFloatingToolbarIsDisplayed();
        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.copy));
        // Make sure that selection action mode is started after selection is created by API when
        // insertion action mode is active.
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 1, text.length()));
        mInstrumentation.waitForIdleSync();

        mToolbar.assertFloatingToolbarIsDisplayed();
        mToolbar.assertFloatingToolbarContainsItem(
                mActivity.getString(com.android.internal.R.string.copy));
    }

    @Test
    public void testTransientState() throws Throwable {
        final String text = "abc def";
        setText(text);

        final TextView textView = mActivity.findViewById(R.id.textview);
        assertFalse(textView.hasTransientState());

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('b')));
        // hasTransientState should return true when user generated selection is active.
        assertTrue(textView.hasTransientState());
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(text.indexOf('d')));
        // hasTransientState should return false as the selection has been cleared.
        assertFalse(textView.hasTransientState());
        mActivityRule.runOnUiThread(
                () -> Selection.setSelection((Spannable) textView.getText(), 0, text.length()));
        mInstrumentation.waitForIdleSync();

        // hasTransientState should return false when selection is created by API.
        assertFalse(textView.hasTransientState());
    }

    @Test
    public void testResetMenuItemTitle() throws Throwable {
        mActivity.getSystemService(TextClassificationManager.class)
                .setTextClassifier(TextClassifier.NO_OP);
        final TextView textView = mActivity.findViewById(R.id.textview);
        final int itemId = 1;
        final String title1 = "@AFIGBO";
        final int index = 3;
        final String title2 = "IGBO";
        final String[] title = new String[]{title1};
        mActivityRule.runOnUiThread(() -> textView.setCustomSelectionActionModeCallback(
                new ActionModeCallbackAdapter() {
                    @Override
                    public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
                        menu.clear();
                        menu.add(Menu.NONE /* group */, itemId, 0 /* order */, title[0]);
                        return true;
                    }
                }));
        mInstrumentation.waitForIdleSync();

        setText(title1);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(index));
        mToolbar.assertFloatingToolbarContainsItem(title1);

        // Change the menu item title.
        title[0] = title2;
        // Change the selection to invalidate the action mode without restarting it.
        onHandleView(com.android.internal.R.id.selection_start_handle)
                .perform(dragHandle(textView, Handle.SELECTION_START, index));
        mToolbar.assertFloatingToolbarContainsItem(title2);
    }

    @Test
    public void testAssistItemIsAtIndexZero() throws Throwable {
        final SingleActionTextClassifier tc = useSingleActionTextClassifier();
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setCustomSelectionActionModeCallback(
                new ActionModeCallbackAdapter() {
                    @Override
                    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
                        // Create another item at order position 0 to confirm that it will never be
                        // placed before the textAssist item.
                        menu.add(Menu.NONE, 0 /* id */, 0 /* order */, "Test");
                        return true;
                    }
                }));
        mInstrumentation.waitForIdleSync();
        final String text = "droid@android.com";

        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('@')));
        mToolbar.assertFloatingToolbarContainsItemAtIndex(tc.getActionLabel(), 0);
    }

    @Test
    public void testNoAssistItemForPasswordField() throws Throwable {
        final SingleActionTextClassifier tc = useSingleActionTextClassifier();

        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> {
            textView.setInputType(
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        });
        mInstrumentation.waitForIdleSync();
        final String password = "afigbo@android.com";

        setText(password);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(password.indexOf('@')));
        mToolbar.assertFloatingToolbarDoesNotContainItem(tc.getActionLabel());
    }

    @Test
    public void testNoAssistItemForTextFieldWithUnsupportedCharacters() throws Throwable {
        // NOTE: This test addresses a security bug.
        final SingleActionTextClassifier tc = useSingleActionTextClassifier();
        final String text = "\u202Emoc.diordna.com";
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setText(text));
        mInstrumentation.waitForIdleSync();

        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('.')));
        mToolbar.assertFloatingToolbarDoesNotContainItem(tc.getActionLabel());
    }

    @Test
    public void testSelectionMetricsLogger_noAbandonAfterCopy() throws Throwable {
        final List<SelectionEvent> selectionEvents = new ArrayList<>();
        final TextClassifier classifier = new TextClassifier() {
            @Override
            public void onSelectionEvent(SelectionEvent event) {
                selectionEvents.add(event);
            }
        };
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setTextClassifier(classifier));
        mInstrumentation.waitForIdleSync();
        final String text = "andyroid@android.com";

        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('@')));
        mToolbar.clickFloatingToolbarItem(mActivity.getString(com.android.internal.R.string.copy));
        mInstrumentation.waitForIdleSync();

        final SelectionEvent lastEvent = selectionEvents.get(selectionEvents.size() - 1);
        assertEquals(SelectionEvent.ACTION_COPY, lastEvent.getEventType());
    }

    @Test
    public void testSelectionMetricsLogger_abandonEventIncludesEntityType() throws Throwable {
        final TestableTextClassifier classifier = new TestableTextClassifier();
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setTextClassifier(classifier));
        mInstrumentation.waitForIdleSync();

        final String text = "My number is 987654321";

        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('9')));
        onView(withId(R.id.textview)).perform(clickOnTextAtIndex(0));
        mInstrumentation.waitForIdleSync();

        // Abandon event is logged 100ms later. See SelectionActionModeHelper.SelectionTracker
        final long abandonDelay = 100;
        final long pollInterval = 10;
        long waitTime = 0;
        SelectionEvent lastEvent;
        do {
            final List<SelectionEvent> selectionEvents = classifier.getSelectionEvents();
            lastEvent = selectionEvents.get(selectionEvents.size() - 1);
            if (lastEvent.getEventType() == SelectionEvent.ACTION_ABANDON) {
                break;
            }
            Thread.sleep(pollInterval);
            waitTime += pollInterval;
        } while (waitTime < abandonDelay * 10);
        assertEquals(SelectionEvent.ACTION_ABANDON, lastEvent.getEventType());
    }

    @Test
    public void testSelectionMetricsLogger_overtypeEventIncludesEntityType() throws Throwable {
        final TestableTextClassifier classifier = new TestableTextClassifier();
        final TextView textView = mActivity.findViewById(R.id.textview);
        mActivityRule.runOnUiThread(() -> textView.setTextClassifier(classifier));
        mInstrumentation.waitForIdleSync();

        final String text = "My number is 987654321";

        // Long press to trigger selection
        setText(text);
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(text.indexOf('9')));

        // Type over the selection
        onView(withId(R.id.textview)).perform(pressKey(KeyEvent.KEYCODE_A));
        mInstrumentation.waitForIdleSync();

        final List<SelectionEvent> selectionEvents = classifier.getSelectionEvents();
        final SelectionEvent lastEvent = selectionEvents.get(selectionEvents.size() - 1);
        assertEquals(SelectionEvent.ACTION_OVERTYPE, lastEvent.getEventType());
        assertEquals(TextClassifier.TYPE_PHONE, lastEvent.getEntityType());
    }

    @Test
    public void testTextClassifierSession() throws Throwable {
        useSystemDefaultTextClassifier();
        TextClassificationManager tcm =
                mActivity.getSystemService(TextClassificationManager.class);
        List<TestableTextClassifier> testableTextClassifiers = new ArrayList<>();
        tcm.setTextClassificationSessionFactory(classificationContext -> {
            TestableTextClassifier textClassifier = new TestableTextClassifier();
            testableTextClassifiers.add(textClassifier);
            return new TextClassifier() {
                private boolean mIsDestroyed = false;

                @Override
                public TextSelection suggestSelection(TextSelection.Request request) {
                    return textClassifier.suggestSelection(request);
                }

                @Override
                public void destroy() {
                    mIsDestroyed = true;
                }

                @Override
                public boolean isDestroyed() {
                    return mIsDestroyed;
                }
            };
        });

        // Long press to trigger selection
        setText("android.com");
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(0));
        // Click "Copy" to dismiss the selection.
        mToolbar.clickFloatingToolbarItem(mActivity.getString(com.android.internal.R.string.copy));

        // Long press to trigger another selection
        setText("android@android.com");
        onView(withId(R.id.textview)).perform(longPressOnTextAtIndex(0));

        // suggestSelection should be called in two different TextClassifier sessions.
        assertEquals(2, testableTextClassifiers.size());
        assertEquals(1, testableTextClassifiers.get(0).getTextSelectionRequests().size());
        assertEquals(1, testableTextClassifiers.get(1).getTextSelectionRequests().size());
    }

    @Test
    public void testPastePlainText_menuAction() {
        initializeClipboardWithText(TextStyle.STYLED);

        setText("");
        onView(withId(R.id.textview)).perform(longClick());
        mToolbar.clickFloatingToolbarItem(
                mActivity.getString(com.android.internal.R.string.paste_as_plain_text));
        mInstrumentation.waitForIdleSync();

        onView(withId(R.id.textview)).check(matches(withText("styledtext")));
        onView(withId(R.id.textview)).check(doesNotHaveStyledText());
    }

    @Test
    public void testPastePlainText_noMenuItemForPlainText() {
        initializeClipboardWithText(TextStyle.PLAIN);

        setText("");
        onView(withId(R.id.textview)).perform(longClick());

        mToolbar.assertFloatingToolbarDoesNotContainItem(
                mActivity.getString(com.android.internal.R.string.paste_as_plain_text));
    }

    private void setText(String text) {
        onView(withId(R.id.textview)).perform(replaceText(text));
        mDevice.wait(Until.findObject(By.text(text)), 1000);
        mInstrumentation.waitForIdleSync();
    }

    private void useSystemDefaultTextClassifier() {
        mActivity.getSystemService(TextClassificationManager.class).setTextClassifier(null);
    }

    private SingleActionTextClassifier useSingleActionTextClassifier() {
        useSystemDefaultTextClassifier();
        final TextClassificationManager tcm =
                mActivity.getSystemService(TextClassificationManager.class);
        final SingleActionTextClassifier oneActionTC =
                new SingleActionTextClassifier(mActivity, tcm.getTextClassifier());
        tcm.setTextClassifier(oneActionTC);
        return oneActionTC;
    }

    private void initializeClipboardWithText(TextStyle textStyle) {
        final ClipData clip;
        switch (textStyle) {
            case STYLED:
                clip = ClipData.newHtmlText("html", "styledtext", "<b>styledtext</b>");
                break;
            case PLAIN:
                clip = ClipData.newPlainText("plain", "plaintext");
                break;
            default:
                throw new IllegalArgumentException("Invalid text style");
        }
        mActivity.getWindow().getDecorView().post(() ->
                mActivity.getSystemService(ClipboardManager.class).setPrimaryClip(clip));
        mInstrumentation.waitForIdleSync();
    }

    private enum TextStyle {
        PLAIN, STYLED
    }

    private static final class TestableTextClassifier implements TextClassifier {
        final List<SelectionEvent> mSelectionEvents = new ArrayList<>();
        final List<TextSelection.Request> mTextSelectionRequests = new ArrayList<>();

        @Override
        public void onSelectionEvent(SelectionEvent event) {
            mSelectionEvents.add(event);
        }

        @Override
        public TextSelection suggestSelection(TextSelection.Request request) {
            mTextSelectionRequests.add(request);
            return new TextSelection.Builder(request.getStartIndex(), request.getEndIndex())
                    .setEntityType(TextClassifier.TYPE_PHONE, 1)
                    .build();
        }

        List<SelectionEvent> getSelectionEvents() {
            return mSelectionEvents;
        }

        List<TextSelection.Request> getTextSelectionRequests() {
            return mTextSelectionRequests;
        }
    }

    private static final class SingleActionTextClassifier implements TextClassifier {

        private final RemoteAction mAction;
        private final TextClassifier mOriginal;
        private final TextClassification mClassificationResult;

        SingleActionTextClassifier(Context context, TextClassifier original) {
            mAction = new RemoteAction(
                    Icon.createWithResource(context, android.R.drawable.btn_star),
                    "assist",
                    "assist",
                    PendingIntent.getActivity(context, 0, new Intent(), FLAG_IMMUTABLE));
            mClassificationResult = new TextClassification.Builder().addAction(mAction).build();
            mOriginal = Objects.requireNonNull(original);
        }

        public String getActionLabel() {
            return mAction.getTitle().toString();
        }

        @Override
        public TextSelection suggestSelection(TextSelection.Request request) {
            final TextSelection sel = mOriginal.suggestSelection(request);
            return new TextSelection.Builder(
                    sel.getSelectionStartIndex(), sel.getSelectionEndIndex())
                    .setTextClassification(mClassificationResult)
                    .build();
        }
    }

    private static class ActionModeCallbackAdapter implements ActionMode.Callback {
        @Override
        public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode actionMode, Menu menu) {
            return true;
        }

        @Override
        public boolean onActionItemClicked(ActionMode actionMode, MenuItem menuItem) {
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode actionMode) {}
    }
}

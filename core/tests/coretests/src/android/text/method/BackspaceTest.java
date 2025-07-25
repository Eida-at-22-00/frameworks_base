/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text.method;

import android.platform.test.annotations.DisabledOnRavenwood;
import android.platform.test.annotations.Presubmit;
import android.text.InputType;
import android.util.KeyUtils;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.TextView.BufferType;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test backspace key handling of {@link android.text.method.BaseKeyListener}.
 *
 * Only contains edge cases. For normal cases, see {@see android.text.method.cts.BackspaceTest}.
 * TODO: introduce test cases for surrogate pairs and replacement span.
 */

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
@DisabledOnRavenwood(blockedBy = EditText.class)
public class BackspaceTest {
    private EditText mTextView;

    private static final BaseKeyListener mKeyListener = new BaseKeyListener() {
        public int getInputType() {
            return InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL;
        }
    };

    @Before
    public void setup() {
        mTextView = new EditText(InstrumentationRegistry.getInstrumentation().getContext());
    }


    // Sync the state to the TextView and call onKeyDown with KEYCODE_DEL key event.
    // Then update the state to the result of TextView.
    private void backspace(final EditorState state, int modifiers) {
        mTextView.setText(state.mText, BufferType.EDITABLE);
        mTextView.setKeyListener(mKeyListener);
        mTextView.setSelection(state.mSelectionStart, state.mSelectionEnd);

        final KeyEvent keyEvent = KeyUtils.generateKeyEvent(
            KeyEvent.KEYCODE_DEL, KeyEvent.ACTION_DOWN, modifiers);
        mTextView.onKeyDown(keyEvent.getKeyCode(), keyEvent);

        state.mText = mTextView.getText();
        state.mSelectionStart = mTextView.getSelectionStart();
        state.mSelectionEnd = mTextView.getSelectionEnd();
    }

    @Test
    public void testCombiningEnclosingKeycaps() {
        EditorState state = new EditorState();

        state.setByString("'1' U+E0101 U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // multiple COMBINING ENCLOSING KEYCAP
        state.setByString("'1' U+20E3 U+20E3 |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated COMBINING ENCLOSING KEYCAP
        state.setByString("U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated multiple COMBINING ENCLOSING KEYCAP
        state.setByString("U+20E3 U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");
    }

    @Test
    public void testVariationSelector() {
        EditorState state = new EditorState();

        // Isolated variation selector
        state.setByString("U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+E0100 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated multiple variation selectors
        state.setByString("U+FE0F U+FE0F |");
        backspace(state, 0);
        state.assertEquals("U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+FE0F U+E0100 |");
        backspace(state, 0);
        state.assertEquals("U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+E0100 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("U+E0100 |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+E0100 U+E0100 |");
        backspace(state, 0);
        state.assertEquals("U+E0100 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Multiple variation selectors
        state.setByString("'#' U+FE0F U+FE0F |");
        backspace(state, 0);
        state.assertEquals("'#' U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("'#' U+FE0F U+E0100 |");
        backspace(state, 0);
        state.assertEquals("'#' U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+845B U+E0100 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("U+845B U+E0100 |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+845B U+E0100 U+E0100 |");
        backspace(state, 0);
        state.assertEquals("U+845B U+E0100 |");
        backspace(state, 0);
        state.assertEquals("|");
    }

    @Test
    public void testEmojiZWJSequence() {
        EditorState state = new EditorState();

        // U+200D is ZERO WIDTH JOINER.
        state.setByString("U+1F441 U+200D U+1F5E8 |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F441 U+200D U+1F5E8 U+FE0E |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F469 U+200D U+1F373 |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F487 U+200D U+2640 |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F487 U+200D U+2640 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F468 U+200D U+2764 U+FE0F U+200D U+1F48B U+200D U+1F468 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Emoji modifier can be appended to each emoji.
        state.setByString("U+1F469 U+1F3FB U+200D U+1F4BC |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+1F468 U+1F3FF U+200D U+2764 U+FE0F U+200D U+1F468 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // End with ZERO WIDTH JOINER
        state.setByString("U+1F441 U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F441 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER
        state.setByString("U+200D U+1F5E8 |");
        backspace(state, 0);
        state.assertEquals("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");

        state.setByString("U+FE0E U+200D U+1F5E8 |");
        backspace(state, 0);
        state.assertEquals("U+FE0E U+200D |");
        backspace(state, 0);
        state.assertEquals("U+FE0E |");
        backspace(state, 0);
        state.assertEquals("|");

        // Multiple ZERO WIDTH JOINER
        state.setByString("U+1F441 U+200D U+200D U+1F5E8 |");
        backspace(state, 0);
        state.assertEquals("U+1F441 U+200D U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F441 U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F441 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated ZERO WIDTH JOINER
        state.setByString("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated multiple ZERO WIDTH JOINER
        state.setByString("U+200D U+200D |");
        backspace(state, 0);
        state.assertEquals("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");
    }

    @Test
    public void testFlags() {
        EditorState state = new EditorState();

        // Isolated regional indicator symbol
        state.setByString("U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("|");

        // Odd numbered regional indicator symbols
        state.setByString("U+1F1FA U+1F1F8 U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("U+1F1FA U+1F1F8 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Incomplete sequence. (no tag_term: U+E007E)
        state.setByString("'a' U+1F3F4 U+E0067 'b' |");
        backspace(state, 0);
        state.assertEquals("'a' U+1F3F4 U+E0067 |");
        backspace(state, 0);
        state.assertEquals("'a' U+1F3F4 |");
        backspace(state, 0);
        state.assertEquals("'a' |");

        // No tag_base
        state.setByString("'a' U+E0067 U+E007F 'b' |");
        backspace(state, 0);
        state.assertEquals("'a' U+E0067 U+E007F |");
        backspace(state, 0);
        state.assertEquals("'a' U+E0067 |");
        backspace(state, 0);
        state.assertEquals("'a' |");

        // Isolated tag chars
        state.setByString("'a' U+E0067 U+E0067 'b' |");
        backspace(state, 0);
        state.assertEquals("'a' U+E0067 U+E0067 |");
        backspace(state, 0);
        state.assertEquals("'a' U+E0067 |");
        backspace(state, 0);
        state.assertEquals("'a' |");

        // Isolated tab term.
        state.setByString("'a' U+E007F U+E007F 'b' |");
        backspace(state, 0);
        state.assertEquals("'a' U+E007F U+E007F |");
        backspace(state, 0);
        state.assertEquals("'a' U+E007F |");
        backspace(state, 0);
        state.assertEquals("'a' |");

        // Immediate tag_term after tag_base
        state.setByString("'a' U+1F3F4 U+E007F U+1F3F4 U+E007F 'b' |");
        backspace(state, 0);
        state.assertEquals("'a' U+1F3F4 U+E007F U+1F3F4 U+E007F |");
        backspace(state, 0);
        state.assertEquals("'a' U+1F3F4 U+E007F |");
        backspace(state, 0);
        state.assertEquals("'a' |");
    }

    @Test
    public void testEmojiModifier() {
        EditorState state = new EditorState();

        // U+1F3FB is EMOJI MODIFIER FITZPATRICK TYPE-1-2.
        state.setByString("U+1F466 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated emoji modifier
        state.setByString("U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // Isolated multiple emoji modifier
        state.setByString("U+1F3FB U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // Multiple emoji modifiers
        state.setByString("U+1F466 U+1F3FB U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+1F466 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");
    }

    @Test
    public void testMixedEdgeCases() {
        EditorState state = new EditorState();

        // COMBINING ENCLOSING KEYCAP + variation selector
        state.setByString("'1' U+20E3 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("'1' |");
        backspace(state, 0);
        state.assertEquals("|");

        // Variation selector + COMBINING ENCLOSING KEYCAP
        state.setByString("U+2665 U+FE0F U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+2665 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + ending with ZERO WIDTH JOINER
        state.setByString("'1' U+20E3 U+200D |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + ZERO WIDTH JOINER
        state.setByString("'1' U+20E3 U+200D U+1F5E8 |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 U+200D |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + COMBINING ENCLOSING KEYCAP
        state.setByString("U+200D U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + COMBINING ENCLOSING KEYCAP
        state.setByString("U+1F441 U+200D U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+1F441 U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F441 |");
        backspace(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + regional indicator symbol
        state.setByString("'1' U+20E3 U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + COMBINING ENCLOSING KEYCAP
        state.setByString("U+1F1FA U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("|");

        // COMBINING ENCLOSING KEYCAP + emoji modifier
        state.setByString("'1' U+20E3 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("'1' U+20E3 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Emoji modifier + COMBINING ENCLOSING KEYCAP
        state.setByString("U+1F466 U+1F3FB U+20E3 |");
        backspace(state, 0);
        state.assertEquals("U+1f466 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // Variation selector + end with ZERO WIDTH JOINER
        state.setByString("U+2665 U+FE0F U+200D |");
        backspace(state, 0);
        state.assertEquals("U+2665 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // Variation selector + ZERO WIDTH JOINER
        state.setByString("U+1F469 U+200D U+2764 U+FE0F U+200D U+1F469 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + variation selector
        state.setByString("U+200D U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + variation selector
        state.setByString("U+1F469 U+200D U+FE0F |");
        backspace(state, 0);
        state.assertEquals("U+1F469 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Variation selector + regional indicator symbol
        state.setByString("U+2665 U+FE0F U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("U+2665 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + variation selector
        state.setByString("U+1F1FA U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // Variation selector + emoji modifier
        state.setByString("U+2665 U+FE0F U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+2665 U+FE0F |");
        backspace(state, 0);
        state.assertEquals("|");

        // Emoji modifier + variation selector
        state.setByString("U+1F466 U+1F3FB U+FE0F |");
        backspace(state, 0);
        state.assertEquals("U+1F466 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Start withj ZERO WIDTH JOINER + regional indicator symbol
        state.setByString("U+200D U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + Regional indicator symbol
        state.setByString("U+1F469 U+200D U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("U+1F469 U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F469 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + end with ZERO WIDTH JOINER
        state.setByString("U+1F1FA U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + ZERO WIDTH JOINER
        state.setByString("U+1F1FA U+200D U+1F469 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Start with ZERO WIDTH JOINER + emoji modifier
        state.setByString("U+200D U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+200D |");
        backspace(state, 0);
        state.assertEquals("|");

        // ZERO WIDTH JOINER + emoji modifier
        state.setByString("U+1F469 U+200D U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+1F469 U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F469 |");
        backspace(state, 0);
        state.assertEquals("|");

        // Emoji modifier + end with ZERO WIDTH JOINER
        state.setByString("U+1F466 U+1F3FB U+200D |");
        backspace(state, 0);
        state.assertEquals("U+1F466 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // Regional indicator symbol + Emoji modifier
        state.setByString("U+1F1FA U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("|");

        // Emoji modifier + regional indicator symbol
        state.setByString("U+1F466 U+1F3FB U+1F1FA |");
        backspace(state, 0);
        state.assertEquals("U+1F466 U+1F3FB |");
        backspace(state, 0);
        state.assertEquals("|");

        // RIS + LF
        state.setByString("U+1F1E6 U+000A |");
        backspace(state, 0);
        state.assertEquals("U+1F1E6 |");
        backspace(state, 0);
        state.assertEquals("|");
    }
}

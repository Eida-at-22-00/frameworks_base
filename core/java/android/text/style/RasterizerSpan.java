/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.text.style;

import android.graphics.Rasterizer;
import android.text.TextPaint;

/**
 *  @removed Rasterizer is not supported for hw-accerlerated and PDF rendering
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class RasterizerSpan extends CharacterStyle implements UpdateAppearance {

    private Rasterizer mRasterizer;

    public RasterizerSpan(Rasterizer r) {
        mRasterizer = r;
    }

    public Rasterizer getRasterizer() {
        return mRasterizer;
    }

    @Override
    public void updateDrawState(TextPaint ds) {
        ds.setRasterizer(mRasterizer);
    }
}

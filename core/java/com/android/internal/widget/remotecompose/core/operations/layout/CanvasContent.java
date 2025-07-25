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
package com.android.internal.widget.remotecompose.core.operations.layout;

import static com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation.INT;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;

import java.util.List;

/** Represents the content of a CanvasLayout (i.e. contains the canvas commands) */
public class CanvasContent extends Component {

    public CanvasContent(
            int componentId,
            float x,
            float y,
            float width,
            float height,
            @Nullable Component parent,
            int animationId) {
        super(parent, componentId, animationId, x, y, width, height);
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return "CanvasContent";
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return Operations.LAYOUT_CANVAS_CONTENT;
    }

    @NonNull
    @Override
    protected String getSerializedName() {
        return "CANVAS_CONTENT";
    }

    /**
     * Write the operation on the buffer
     *
     * @param buffer
     * @param componentId
     */
    public static void apply(@NonNull WireBuffer buffer, int componentId) {
        buffer.start(Operations.LAYOUT_CANVAS_CONTENT);
        buffer.writeInt(componentId);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int componentId = buffer.readInt();
        operations.add(new CanvasContent(componentId, 0, 0, 0, 0, null, -1));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Layout Operations", id(), name())
                .field(INT, "COMPONENT_ID", "unique id for this component")
                .description("Container for canvas commands.");
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mComponentId);
    }
}

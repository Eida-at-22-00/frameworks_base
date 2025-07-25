/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.internal.widget.remotecompose.core.operations;

import android.annotation.NonNull;

import com.android.internal.widget.remotecompose.core.Operation;
import com.android.internal.widget.remotecompose.core.Operations;
import com.android.internal.widget.remotecompose.core.RemoteContext;
import com.android.internal.widget.remotecompose.core.SerializableToString;
import com.android.internal.widget.remotecompose.core.WireBuffer;
import com.android.internal.widget.remotecompose.core.documentation.DocumentationBuilder;
import com.android.internal.widget.remotecompose.core.documentation.DocumentedOperation;
import com.android.internal.widget.remotecompose.core.operations.utilities.StringSerializer;
import com.android.internal.widget.remotecompose.core.serialize.MapSerializer;
import com.android.internal.widget.remotecompose.core.serialize.Serializable;

import java.util.List;

/** Generate HapticFeedback */
public class HapticFeedback extends Operation implements SerializableToString, Serializable {
    private static final int OP_CODE = Operations.HAPTIC_FEEDBACK;
    private static final String CLASS_NAME = "HapticFeedback";
    private int mHapticFeedbackType;

    public HapticFeedback(int hapticFeedbackType) {
        this.mHapticFeedbackType = hapticFeedbackType;
    }

    @Override
    public void write(@NonNull WireBuffer buffer) {
        apply(buffer, mHapticFeedbackType);
    }

    @NonNull
    @Override
    public String toString() {
        return CLASS_NAME + "(" + mHapticFeedbackType + ")";
    }

    /**
     * The name of the class
     *
     * @return the name
     */
    @NonNull
    public static String name() {
        return CLASS_NAME;
    }

    /**
     * The OP_CODE for this command
     *
     * @return the opcode
     */
    public static int id() {
        return OP_CODE;
    }

    /**
     * add a text data operation
     *
     * @param buffer buffer to add to
     * @param hapticFeedbackType the vibration effect
     */
    public static void apply(@NonNull WireBuffer buffer, int hapticFeedbackType) {
        buffer.start(OP_CODE);
        buffer.writeInt(hapticFeedbackType);
    }

    /**
     * Read this operation and add it to the list of operations
     *
     * @param buffer the buffer to read
     * @param operations the list of operations that will be added to
     */
    public static void read(@NonNull WireBuffer buffer, @NonNull List<Operation> operations) {
        int hapticFeedbackType = buffer.readInt();

        operations.add(new HapticFeedback(hapticFeedbackType));
    }

    /**
     * Populate the documentation with a description of this operation
     *
     * @param doc to append the description to.
     */
    public static void documentation(@NonNull DocumentationBuilder doc) {
        doc.operation("Data Operations", OP_CODE, CLASS_NAME)
                .description("Generate an haptic feedback")
                .field(DocumentedOperation.INT, "HapticFeedbackType", "Type of haptic feedback");
    }

    @Override
    public void apply(@NonNull RemoteContext context) {
        context.hapticEffect(mHapticFeedbackType);
    }

    @NonNull
    @Override
    public String deepToString(@NonNull String indent) {
        return indent + toString();
    }

    @Override
    public void serializeToString(int indent, @NonNull StringSerializer serializer) {
        serializer.append(indent, getSerializedName() + "<" + mHapticFeedbackType + ">");
    }

    @NonNull
    private String getSerializedName() {
        return "HAPTIC_FEEDBACK";
    }

    @Override
    public void serialize(MapSerializer serializer) {
        serializer.addType(CLASS_NAME).add("hapticFeedbackType", mHapticFeedbackType);
    }
}

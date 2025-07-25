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

package android.hardware.display;

import static android.hardware.display.DisplayTopology.TreeNode.POSITION_BOTTOM;
import static android.hardware.display.DisplayTopology.TreeNode.POSITION_LEFT;
import static android.hardware.display.DisplayTopology.TreeNode.POSITION_RIGHT;
import static android.hardware.display.DisplayTopology.TreeNode.POSITION_TOP;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.DisplayMetrics;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Display;

import androidx.annotation.NonNull;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.display.feature.flags.Flags;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * Represents the relative placement of extended displays.
 * Does not support concurrent calls, so a lock should be held when calling into this class.
 *
 * @hide
 */
@TestApi
@FlaggedApi(Flags.FLAG_DISPLAY_TOPOLOGY)
public final class DisplayTopology implements Parcelable {
    private static final String TAG = "DisplayTopology";
    private static final float EPSILON = 0.0001f;
    private static final float MAX_GAP = 5;

    @android.annotation.NonNull
    public static final Creator<DisplayTopology> CREATOR =
            new Creator<>() {
                @Override
                public DisplayTopology createFromParcel(Parcel source) {
                    return new DisplayTopology(source);
                }

                @Override
                public DisplayTopology[] newArray(int size) {
                    return new DisplayTopology[size];
                }
            };

    /**
     * @param px The value in logical pixels
     * @param dpi The logical density of the display
     * @return The value in density-independent pixels
     * @hide
     */
    public static float pxToDp(float px, int dpi) {
        return px * DisplayMetrics.DENSITY_DEFAULT / dpi;
    }

    /**
     * @param dp The value in density-independent pixels
     * @param dpi The logical density of the display
     * @return The value in logical pixels
     * @hide
     */
    public static float dpToPx(float dp, int dpi) {
        return dp * dpi / DisplayMetrics.DENSITY_DEFAULT;
    }

    /**
     * The topology tree
     */
    @Nullable
    private TreeNode mRoot;

    /**
     * The logical display ID of the primary display that will show certain UI elements.
     * This is not necessarily the same as the default display.
     */
    private int mPrimaryDisplayId = Display.INVALID_DISPLAY;

    /**
     * @hide
     */
    public DisplayTopology() {}

    /**
     * @hide
     */
    public DisplayTopology(@Nullable TreeNode root, int primaryDisplayId) {
        mRoot = root;
        if (mRoot != null) {
            // Set mRoot's position and offset to predictable values, just so we don't leak state
            // from some previous arrangement the node was used in, or leak arbitrary values passed
            // to the TreeNode constructor. The position and offset don't mean anything because
            // mRoot doesn't have a parent.
            mRoot.mPosition = POSITION_LEFT;
            mRoot.mOffset = 0f;
        }

        mPrimaryDisplayId = primaryDisplayId;
    }

    /**
     * @hide
     */
    public DisplayTopology(Parcel source) {
        this(source.readTypedObject(TreeNode.CREATOR), source.readInt());
    }

    /**
     * @hide
     */
    @Nullable
    public TreeNode getRoot() {
        return mRoot;
    }

    /**
     * @hide
     */
    public int getPrimaryDisplayId() {
        return mPrimaryDisplayId;
    }

    /**
     * Add a display to the topology.
     * If this is the second display in the topology, it will be placed above the first display.
     * Subsequent displays will be places to the left or right of the second display.
     * @param displayId The logical display ID
     * @param width The width of the display
     * @param height The height of the display
     * @hide
     */
    public void addDisplay(int displayId, float width, float height) {
        if (findDisplay(displayId, mRoot) != null) {
            return;
        }
        if (mRoot == null) {
            mRoot = new TreeNode(displayId, width, height, POSITION_LEFT, /* offset= */ 0);
            mPrimaryDisplayId = displayId;
        } else if (mRoot.mChildren.isEmpty()) {
            // This is the 2nd display. Align the middles of the top and bottom edges.
            float offset = mRoot.mWidth / 2 - width / 2;
            TreeNode display = new TreeNode(displayId, width, height, POSITION_TOP, offset);
            mRoot.mChildren.add(display);
        } else {
            TreeNode rightMostDisplay = findRightMostDisplay(mRoot, mRoot.mWidth).first;
            TreeNode newDisplay = new TreeNode(displayId, width, height, POSITION_RIGHT,
                    /* offset= */ 0);
            rightMostDisplay.mChildren.add(newDisplay);
        }
    }

    /**
     * Update the size of a display and normalize the topology.
     * @param displayId The logical display ID
     * @param width The new width
     * @param height The new height
     * @return True if the topology has changed.
     * @hide
     */
    public boolean updateDisplay(int displayId, float width, float height) {
        TreeNode display = findDisplay(displayId, mRoot);
        if (display == null) {
            return false;
        }
        if (floatEquals(display.mWidth, width) && floatEquals(display.mHeight, height)) {
            return false;
        }
        display.mWidth = width;
        display.mHeight = height;
        normalize();
        Slog.i(TAG, "Display with ID " + displayId + " updated, new width: " + width
                + ", new height: " + height);
        return true;
    }

    /**
     * Remove a display from the topology.
     * The default topology is created from the remaining displays, as if they were reconnected
     * one by one.
     * @param displayId The logical display ID
     * @return True if the display was present in the topology and removed.
     * @hide
     */
    public boolean removeDisplay(int displayId) {
        if (findDisplay(displayId, mRoot) == null) {
            return false;
        }

        // Re-add the other displays to a new tree
        Queue<TreeNode> queue = new ArrayDeque<>();
        queue.add(mRoot);
        mRoot = null;
        while (!queue.isEmpty()) {
            TreeNode node = queue.poll();
            if (node.mDisplayId != displayId) {
                addDisplay(node.mDisplayId, node.mWidth, node.mHeight);
            }
            queue.addAll(node.mChildren);
        }

        if (mPrimaryDisplayId == displayId) {
            if (mRoot != null) {
                mPrimaryDisplayId = mRoot.mDisplayId;
            } else {
                mPrimaryDisplayId = Display.INVALID_DISPLAY;
            }
        }
        return true;
    }

    /**
     * Rearranges the topology toward the positions given for each display. The width and height of
     * each display, as well as the primary display, are not changed by this call.
     * <p>
     * Upon returning, the topology will be valid and normalized with each display as close to the
     * requested positions as possible.
     *
     * @param newPos the desired positions (upper-left corner) of each display. The keys in the map
     *               are the display IDs.
     * @throws IllegalArgumentException if the keys in {@code positions} are not the exact display
     *                                  IDs in this topology, no more, no less
     * @hide
     */
    public void rearrange(Map<Integer, PointF> newPos) {
        if (mRoot == null) {
            return;
        }
        var availableParents = new ArrayList<TreeNode>();

        availableParents.addLast(mRoot);

        var needsParent = allNodesIdMap();

        // In the case of missing items, if this check doesn't detect it, a NPE will be thrown
        // later.
        if (needsParent.size() != newPos.size()) {
            throw new IllegalArgumentException("newPos has wrong number of entries: " + newPos);
        }

        mRoot.mChildren.clear();
        for (TreeNode n : needsParent.values()) {
            n.mChildren.clear();
        }

        needsParent.remove(mRoot.mDisplayId);
        // Start with a root island and add children to it one-by-one until the island consists of
        // all the displays. The root island begins with only the root node, which has no
        // parent. Then we greedily choose an optimal pairing of two nodes, consisting of a node
        // from the island and a node not yet in the island. This is repeating until all nodes are
        // in the island.
        //
        // The optimal pair is the pair which has the smallest deviation. The deviation consists of
        // an x-axis component and a y-axis component, called xDeviation and yDeviation.
        //
        // The deviations are like distances but a little different. When they are calculated, each
        // dimension is treated differently, depending on which edges (left+right or top+bottom) are
        // attached.
        while (!needsParent.isEmpty()) {
            double bestDist = Double.POSITIVE_INFINITY;
            TreeNode bestChild = null, bestParent = null;

            for (var child : needsParent.values()) {
                PointF childPos = newPos.get(child.mDisplayId);
                float childRight = childPos.x + child.getWidth();
                float childBottom = childPos.y + child.getHeight();
                for (var parent : availableParents) {
                    PointF parentPos = newPos.get(parent.mDisplayId);
                    float parentRight = parentPos.x + parent.getWidth();
                    float parentBottom = parentPos.y + parent.getHeight();

                    // The "amount of overlap" indicates how much of one display is within the other
                    // (considering one axis only). It's zero if they only share an edge and
                    // negative if they're away from each other.
                    // A zero or negative overlap does not make a parenting ineligible, because we
                    // allow for attaching at the corner and for floating point error.
                    float xOverlap =
                            Math.min(parentRight, childRight) - Math.max(parentPos.x, childPos.x);
                    float yOverlap =
                            Math.min(parentBottom, childBottom) - Math.max(parentPos.y, childPos.y);
                    float xDeviation, yDeviation;

                    float offset;
                    int pos;
                    if (xOverlap > yOverlap) {
                        // Deviation in each dimension is a penalty in the potential parenting. To
                        // get the X deviation, overlap is subtracted from the lesser width so that
                        // a maximum overlap results in a deviation of zero.
                        // Note that because xOverlap is *subtracted* from the lesser width, no
                        // overlap in X becomes a *penalty* if we are attaching on the top+bottom
                        // edges.
                        //
                        // The Y deviation is simply the distance from the clamping edges.
                        //
                        // Treatment of the X and Y deviations are swapped for
                        // POSITION_LEFT/POSITION_RIGHT attachments in the "else" block below.
                        xDeviation = Math.min(child.getWidth(), parent.getWidth()) - xOverlap;
                        if (childPos.y < parentPos.y) {
                            yDeviation = childBottom - parentPos.y;
                            pos = POSITION_TOP;
                        } else {
                            yDeviation = parentBottom - childPos.y;
                            pos = POSITION_BOTTOM;
                        }
                        offset = childPos.x - parentPos.x;
                    } else {
                        yDeviation = Math.min(child.getHeight(), parent.getHeight()) - yOverlap;
                        if (childPos.x < parentPos.x) {
                            xDeviation = childRight - parentPos.x;
                            pos = POSITION_LEFT;
                        } else {
                            xDeviation = parentRight - childPos.x;
                            pos = POSITION_RIGHT;
                        }
                        offset = childPos.y - parentPos.y;
                    }

                    double dist = Math.hypot(xDeviation, yDeviation);
                    if (dist >= bestDist) {
                        continue;
                    }

                    bestDist = dist;
                    bestChild = child;
                    bestParent = parent;
                    // Eagerly update the child's parenting info, even though we may not use it, in
                    // which case it will be overwritten later.
                    bestChild.mPosition = pos;
                    bestChild.mOffset = offset;
                }
            }

            assert bestParent != null & bestChild != null;

            bestParent.addChild(bestChild);
            if (null == needsParent.remove(bestChild.mDisplayId)) {
                throw new IllegalStateException("child not in pending set! " + bestChild);
            }
            availableParents.add(bestChild);
        }

        // The conversion may have introduced an intersection of two display rects. If they are
        // bigger than our error tolerance, this function will remove them.
        normalize();
    }

    /**
     * Clamp offsets and remove any overlaps between displays.
     * @hide
     */
    public void normalize() {
        if (mRoot == null) {
            return;
        }
        clampOffsets(mRoot);

        Map<TreeNode, RectF> bounds = new HashMap<>();
        Map<TreeNode, Integer> depths = new HashMap<>();
        Map<TreeNode, TreeNode> parents = new HashMap<>();
        getInfo(bounds, depths, parents, mRoot, /* x= */ 0, /* y= */ 0, /* depth= */ 0);

        // Sort the displays first by their depth in the tree, then by the distance of their top
        // left point from the root display's origin (0, 0). This way we process the displays
        // starting at the root and we push out a display if necessary.
        Comparator<TreeNode> comparator = (d1, d2) -> {
            if (d1 == d2) {
                return 0;
            }

            int compareDepths = Integer.compare(depths.get(d1), depths.get(d2));
            if (compareDepths != 0) {
                return compareDepths;
            }

            RectF bounds1 = bounds.get(d1);
            RectF bounds2 = bounds.get(d2);
            return Double.compare(Math.hypot(bounds1.left, bounds1.top),
                    Math.hypot(bounds2.left, bounds2.top));
        };
        List<TreeNode> displays = new ArrayList<>(bounds.keySet());
        displays.sort(comparator);

        for (int i = 1; i < displays.size(); i++) {
            TreeNode targetDisplay = displays.get(i);
            TreeNode lastIntersectingSourceDisplay = null;
            float lastOffsetX = 0;
            float lastOffsetY = 0;

            for (int j = 0; j < i; j++) {
                TreeNode sourceDisplay = displays.get(j);
                RectF sourceBounds = bounds.get(sourceDisplay);
                RectF targetBounds = bounds.get(targetDisplay);

                if (!RectF.intersects(sourceBounds, targetBounds)) {
                    continue;
                }

                // Find the offset by which to move the display. Pick the smaller one among the x
                // and y axes.
                float offsetX = targetBounds.left >= 0
                        ? sourceBounds.right - targetBounds.left
                        : sourceBounds.left - targetBounds.right;
                float offsetY = targetBounds.top >= 0
                        ? sourceBounds.bottom - targetBounds.top
                        : sourceBounds.top - targetBounds.bottom;
                if (Math.abs(offsetX) <= Math.abs(offsetY)) {
                    targetBounds.left += offsetX;
                    targetBounds.right += offsetX;
                    // We need to also update the offset in the tree
                    if (targetDisplay.mPosition == POSITION_TOP
                            || targetDisplay.mPosition == POSITION_BOTTOM) {
                        targetDisplay.mOffset += offsetX;
                    }
                    offsetY = 0;
                } else {
                    targetBounds.top += offsetY;
                    targetBounds.bottom += offsetY;
                    // We need to also update the offset in the tree
                    if (targetDisplay.mPosition == POSITION_LEFT
                            || targetDisplay.mPosition == POSITION_RIGHT) {
                        targetDisplay.mOffset += offsetY;
                    }
                    offsetX = 0;
                }

                lastIntersectingSourceDisplay = sourceDisplay;
                lastOffsetX = offsetX;
                lastOffsetY = offsetY;
            }

            // Now re-parent the target display to the last intersecting source display if it no
            // longer touches its parent.
            if (lastIntersectingSourceDisplay == null) {
                // There was no overlap.
                continue;
            }
            TreeNode parent = parents.get(targetDisplay);
            if (parent == lastIntersectingSourceDisplay) {
                // The displays are moved in such a way that they're adjacent to the intersecting
                // display. If the last intersecting display happens to be the parent then we
                // already know that the display is adjacent to its parent.
                continue;
            }

            RectF childBounds = bounds.get(targetDisplay);
            RectF parentBounds = bounds.get(parent);
            // Check that the edges are on the same line
            boolean areTouching = switch (targetDisplay.mPosition) {
                case POSITION_LEFT -> floatEquals(parentBounds.left, childBounds.right);
                case POSITION_RIGHT -> floatEquals(parentBounds.right, childBounds.left);
                case POSITION_TOP -> floatEquals(parentBounds.top, childBounds.bottom);
                case POSITION_BOTTOM -> floatEquals(parentBounds.bottom, childBounds.top);
                default -> throw new IllegalStateException(
                        "Unexpected value: " + targetDisplay.mPosition);
            };
            // Check that the offset is within bounds
            areTouching &= switch (targetDisplay.mPosition) {
                case POSITION_LEFT, POSITION_RIGHT ->
                        childBounds.bottom + EPSILON > parentBounds.top
                                && childBounds.top < parentBounds.bottom + EPSILON;
                case POSITION_TOP, POSITION_BOTTOM ->
                        childBounds.right + EPSILON > parentBounds.left
                                && childBounds.left < parentBounds.right + EPSILON;
                default -> throw new IllegalStateException(
                        "Unexpected value: " + targetDisplay.mPosition);
            };

            if (!areTouching) {
                // Re-parent the display.
                parent.mChildren.remove(targetDisplay);
                RectF lastIntersectingSourceDisplayBounds =
                        bounds.get(lastIntersectingSourceDisplay);
                lastIntersectingSourceDisplay.mChildren.add(targetDisplay);

                if (lastOffsetX != 0) {
                    targetDisplay.mPosition = lastOffsetX > 0 ? POSITION_RIGHT : POSITION_LEFT;
                    targetDisplay.mOffset =
                            childBounds.top - lastIntersectingSourceDisplayBounds.top;
                } else if (lastOffsetY != 0) {
                    targetDisplay.mPosition = lastOffsetY > 0 ? POSITION_BOTTOM : POSITION_TOP;
                    targetDisplay.mOffset =
                            childBounds.left - lastIntersectingSourceDisplayBounds.left;
                }
            }
        }

        // Sort children lists by display ID.
        final Comparator<TreeNode> idComparator = (d1, d2) -> {
            return Integer.compare(d1.mDisplayId, d2.mDisplayId);
        };
        for (TreeNode display : displays) {
            display.mChildren.sort(idComparator);
        }
    }

    /**
     * @return A deep copy of the topology that will not be modified by the system.
     * @hide
     */
    public DisplayTopology copy() {
        TreeNode rootCopy = mRoot == null ? null : mRoot.copy();
        return new DisplayTopology(rootCopy, mPrimaryDisplayId);
    }

    /**
     * Assign absolute bounds to each display. The top-left corner of the root is at position
     * (0, 0).
     * @return Map from logical display ID to the display's absolute bounds
     */
    @NonNull
    public SparseArray<RectF> getAbsoluteBounds() {
        Map<TreeNode, RectF> bounds = new HashMap<>();
        getInfo(bounds, /* depths= */ null, /* parents= */ null, mRoot, /* x= */ 0, /* y= */ 0,
                /* depth= */ 0);
        SparseArray<RectF> boundsById = new SparseArray<>();
        for (Map.Entry<TreeNode, RectF> entry : bounds.entrySet()) {
            boundsById.append(entry.getKey().mDisplayId, entry.getValue());
        }
        return boundsById;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeTypedObject(mRoot, flags);
        dest.writeInt(mPrimaryDisplayId);
    }

    /**
     * Print the object's state and debug information into the given stream.
     * @hide
     * @param pw The stream to dump information to.
     */
    public void dump(PrintWriter pw) {
        pw.println("DisplayTopology:");
        pw.println("--------------------");
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        ipw.increaseIndent();

        ipw.println("mPrimaryDisplayId: " + mPrimaryDisplayId);

        ipw.println("Topology tree:");
        if (mRoot != null) {
            ipw.increaseIndent();
            mRoot.dump(ipw);
            ipw.decreaseIndent();
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof DisplayTopology)) {
            return false;
        }
        return obj.toString().equals(toString());
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public String toString() {
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        dump(writer);
        return out.toString();
    }

    /**
     * @param display The display from which the search should start.
     * @param xPos The x position of the right edge of that display.
     * @return The display that is the furthest to the right and the x position of the right edge
     * of that display
     */
    private static Pair<TreeNode, Float> findRightMostDisplay(TreeNode display, float xPos) {
        Pair<TreeNode, Float> result = new Pair<>(display, xPos);
        for (TreeNode child : display.mChildren) {
            // The x position of the right edge of the child
            float childXPos;
            switch (child.mPosition) {
                case POSITION_LEFT -> childXPos = xPos - display.mWidth;
                case POSITION_TOP, POSITION_BOTTOM ->
                        childXPos = xPos - display.mWidth + child.mOffset + child.mWidth;
                case POSITION_RIGHT -> childXPos = xPos + child.mWidth;
                default -> throw new IllegalStateException("Unexpected value: " + child.mPosition);
            }

            // Recursive call - find the rightmost display starting from the child
            Pair<TreeNode, Float> childResult = findRightMostDisplay(child, childXPos);
            // Check if the one found is further right
            if (childResult.second > result.second) {
                result = new Pair<>(childResult.first, childResult.second);
            }
        }
        return result;
    }

    /**
     * @hide
     */
    @Nullable
    public static TreeNode findDisplay(int displayId, @Nullable TreeNode startingNode) {
        if (startingNode == null) {
            return null;
        }
        if (startingNode.mDisplayId == displayId) {
            return startingNode;
        }
        for (TreeNode child : startingNode.mChildren) {
            TreeNode display = findDisplay(displayId, child);
            if (display != null) {
                return display;
            }
        }
        return null;
    }

    /**
     * Get information about the topology.
     * Assigns positions to each display to compute the bounds. The root is at position (0, 0).
     * @param bounds The map where the bounds of each display will be put
     * @param depths The map where the depths of each display in the tree will be put
     * @param parents The map where the parent of each display will be put
     * @param display The starting node
     * @param x The starting x position
     * @param y The starting y position
     * @param depth The starting depth
     */
    private static void getInfo(@Nullable Map<TreeNode, RectF> bounds,
            @Nullable Map<TreeNode, Integer> depths, @Nullable Map<TreeNode, TreeNode> parents,
            @Nullable TreeNode display, float x, float y, int depth) {
        if (display == null) {
            return;
        }
        if (bounds != null) {
            bounds.put(display, new RectF(x, y, x + display.mWidth, y + display.mHeight));
        }
        if (depths != null) {
            depths.put(display, depth);
        }
        for (TreeNode child : display.mChildren) {
            if (parents != null) {
                parents.put(child, display);
            }
            if (child.mPosition == POSITION_LEFT) {
                getInfo(bounds, depths, parents, child, x - child.mWidth, y + child.mOffset,
                        depth + 1);
            } else if (child.mPosition == POSITION_RIGHT) {
                getInfo(bounds, depths, parents, child, x + display.mWidth, y + child.mOffset,
                        depth + 1);
            } else if (child.mPosition == POSITION_TOP) {
                getInfo(bounds, depths, parents, child, x + child.mOffset, y - child.mHeight,
                        depth + 1);
            } else if (child.mPosition == POSITION_BOTTOM) {
                getInfo(bounds, depths, parents, child, x + child.mOffset, y + display.mHeight,
                        depth + 1);
            }
        }
    }

    /**
     * Check if two displays are touching.
     * If the gap between two edges is <= {@link MAX_GAP}, they are still considered adjacent.
     * The position indicates where the second display is touching the first one and the offset
     * indicates where along the first display the second display is located.
     * @param bounds1 The bounds of the first display
     * @param bounds2 The bounds of the second display
     * @return Empty list if the displays are not adjacent;
     * List of one Pair(position, offset) if the displays are adjacent but not by a corner;
     * List of two Pair(position, offset) if the displays are adjacent by a corner.
     */
    private List<Pair<Integer, Float>> findDisplayPlacements(RectF bounds1, RectF bounds2) {
        List<Pair<Integer, Float>> placements = new ArrayList<>();
        if (bounds1.top <= bounds2.bottom + MAX_GAP && bounds2.top <= bounds1.bottom + MAX_GAP) {
            if (MathUtils.abs(bounds1.left - bounds2.right) <= MAX_GAP) {
                placements.add(new Pair<>(POSITION_LEFT, bounds2.top - bounds1.top));
            }
            if (MathUtils.abs(bounds1.right - bounds2.left) <= MAX_GAP) {
                placements.add(new Pair<>(POSITION_RIGHT, bounds2.top - bounds1.top));
            }
        }
        if (bounds1.left <= bounds2.right + MAX_GAP && bounds2.left <= bounds1.right + MAX_GAP) {
            if (MathUtils.abs(bounds1.top - bounds2.bottom) < MAX_GAP) {
                placements.add(new Pair<>(POSITION_TOP, bounds2.left - bounds1.left));
            }
            if (MathUtils.abs(bounds1.bottom - bounds2.top) < MAX_GAP) {
                placements.add(new Pair<>(POSITION_BOTTOM, bounds2.left - bounds1.left));
            }
        }
        return placements;
    }

    /**
     * @param densityPerDisplay The logical display densities, indexed by logical display ID
     * @return The graph representation of the topology. If there is a corner adjacency, the same
     * display will appear twice in the list of adjacent displays with both possible placements.
     * @hide
     */
    @Nullable
    public DisplayTopologyGraph getGraph(SparseIntArray densityPerDisplay) {
        // Sort the displays by position
        SparseArray<RectF> bounds = getAbsoluteBounds();
        Comparator<Integer> comparator = (displayId1, displayId2) -> {
            RectF bounds1 = bounds.get(displayId1);
            RectF bounds2 = bounds.get(displayId2);

            int compareX = Float.compare(bounds1.left, bounds2.left);
            if (compareX != 0) {
                return compareX;
            }
            return Float.compare(bounds1.top, bounds2.top);
        };
        List<Integer> displayIds = new ArrayList<>(bounds.size());
        for (int i = 0; i < bounds.size(); i++) {
            displayIds.add(bounds.keyAt(i));
        }
        displayIds.sort(comparator);

        SparseArray<List<DisplayTopologyGraph.AdjacentDisplay>> adjacentDisplaysPerId =
                new SparseArray<>();
        for (int id : displayIds) {
            if (densityPerDisplay.get(id) == 0) {
                Slog.e(TAG, "Cannot construct graph, no density for display " + id);
                return null;
            }
            adjacentDisplaysPerId.append(id, new ArrayList<>(Math.min(10, displayIds.size())));
        }

        // Find touching displays
        for (int i = 0; i < displayIds.size(); i++) {
            int displayId1 = displayIds.get(i);
            RectF bounds1 = bounds.get(displayId1);
            List<DisplayTopologyGraph.AdjacentDisplay> adjacentDisplays1 =
                    adjacentDisplaysPerId.get(displayId1);

            for (int j = i + 1; j < displayIds.size(); j++) {
                int displayId2 = displayIds.get(j);
                RectF bounds2 = bounds.get(displayId2);
                List<DisplayTopologyGraph.AdjacentDisplay> adjacentDisplays2 =
                        adjacentDisplaysPerId.get(displayId2);

                List<Pair<Integer, Float>> placements1 = findDisplayPlacements(bounds1, bounds2);
                List<Pair<Integer, Float>> placements2 = findDisplayPlacements(bounds2, bounds1);
                for (Pair<Integer, Float> placement : placements1) {
                    adjacentDisplays1.add(new DisplayTopologyGraph.AdjacentDisplay(displayId2,
                            /* position= */ placement.first, /* offsetDp= */ placement.second));
                }
                for (Pair<Integer, Float> placement : placements2) {
                    adjacentDisplays2.add(new DisplayTopologyGraph.AdjacentDisplay(displayId1,
                            /* position= */ placement.first, /* offsetDp= */ placement.second));
                }
                if (bounds2.left >= bounds1.right + EPSILON) {
                    // This and the subsequent displays are already too far away
                    break;
                }
            }
        }

        DisplayTopologyGraph.DisplayNode[] nodes =
                new DisplayTopologyGraph.DisplayNode[adjacentDisplaysPerId.size()];
        for (int i = 0; i < nodes.length; i++) {
            int displayId = adjacentDisplaysPerId.keyAt(i);
            nodes[i] = new DisplayTopologyGraph.DisplayNode(displayId,
                    densityPerDisplay.get(displayId), adjacentDisplaysPerId.valueAt(i).toArray(
                            new DisplayTopologyGraph.AdjacentDisplay[0]));
        }
        return new DisplayTopologyGraph(mPrimaryDisplayId, nodes);
    }

    /**
     * Tests whether two float values are within a small enough tolerance of each other.
     * @param a first float to compare
     * @param b second float to compare
     * @return whether the two values are within a small enough tolerance value
     */
    private static boolean floatEquals(float a, float b) {
        return a == b || (Float.isNaN(a) && Float.isNaN(b)) || Math.abs(a - b) < EPSILON;
    }

    private Map<Integer, TreeNode> allNodesIdMap() {
        var pend = new ArrayDeque<TreeNode>();
        var found = new HashMap<Integer, TreeNode>();

        pend.push(mRoot);
        do {
            TreeNode node = pend.pop();
            found.put(node.mDisplayId, node);
            pend.addAll(node.mChildren);
        } while (!pend.isEmpty());

        return found;
    }

    /**
     * Ensure that the offsets of all displays within the given tree are within bounds.
     * @param display The starting node
     */
    private void clampOffsets(@Nullable TreeNode display) {
        if (display == null) {
            return;
        }
        for (TreeNode child : display.mChildren) {
            if (child.mPosition == POSITION_LEFT || child.mPosition == POSITION_RIGHT) {
                child.mOffset = MathUtils.constrain(child.mOffset, -child.mHeight, display.mHeight);
            } else if (child.mPosition == POSITION_TOP || child.mPosition == POSITION_BOTTOM) {
                child.mOffset = MathUtils.constrain(child.mOffset, -child.mWidth, display.mWidth);
            }
            clampOffsets(child);
        }
    }

    /**
     * @hide
     */
    public static final class TreeNode implements Parcelable {
        public static final int POSITION_LEFT = 0;
        public static final int POSITION_TOP = 1;
        public static final int POSITION_RIGHT = 2;
        public static final int POSITION_BOTTOM = 3;

        @IntDef(prefix = { "POSITION_" }, value = {
                POSITION_LEFT, POSITION_TOP, POSITION_RIGHT, POSITION_BOTTOM
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface Position{}

        @android.annotation.NonNull
        public static final Creator<TreeNode> CREATOR =
                new Creator<>() {
                    @Override
                    public TreeNode createFromParcel(Parcel source) {
                        return new TreeNode(source);
                    }

                    @Override
                    public TreeNode[] newArray(int size) {
                        return new TreeNode[size];
                    }
                };

        /**
         * The logical display ID
         */
        private final int mDisplayId;

        /**
         * The width of the display in density-independent pixels (dp).
         */
        private float mWidth;

        /**
         * The height of the display in density-independent pixels (dp).
         */
        private float mHeight;

        /**
         * The position of this display relative to its parent.
         */
        @Position
        private int mPosition;

        /**
         * The distance from the top edge of the parent display to the top edge of this display (in
         * case of POSITION_LEFT or POSITION_RIGHT) or from the left edge of the parent display
         * to the left edge of this display (in case of POSITION_TOP or POSITION_BOTTOM). The unit
         * used is density-independent pixels (dp).
         */
        private float mOffset;

        private final List<TreeNode> mChildren;

        @VisibleForTesting
        public TreeNode(int displayId, float width, float height, @Position int position,
                float offset) {
            this(displayId, width, height, position, offset, List.of());
        }

        public TreeNode(int displayId, float width, float height, int position,
                        float offset, List<TreeNode> children) {
            mDisplayId = displayId;
            mWidth = width;
            mHeight = height;
            mPosition = position;
            mOffset = offset;
            mChildren = new ArrayList<>(children);
        }

        public TreeNode(Parcel source) {
            this(source.readInt(), source.readFloat(), source.readFloat(), source.readInt(),
                    source.readFloat());
            source.readTypedList(mChildren, CREATOR);
        }

        public int getDisplayId() {
            return mDisplayId;
        }

        public float getWidth() {
            return mWidth;
        }

        public float getHeight() {
            return mHeight;
        }

        public int getPosition() {
            return mPosition;
        }

        public float getOffset() {
            return mOffset;
        }

        public List<TreeNode> getChildren() {
            return Collections.unmodifiableList(mChildren);
        }

        /**
         * @return A deep copy of the node that will not be modified by the system.
         */
        public TreeNode copy() {
            TreeNode copy = new TreeNode(mDisplayId, mWidth, mHeight, mPosition, mOffset);
            for (TreeNode child : mChildren) {
                copy.mChildren.add(child.copy());
            }
            return copy;
        }

        @Override
        public String toString() {
            return "Display {id=" + mDisplayId + ", width=" + mWidth + ", height=" + mHeight
                    + ", position=" + positionToString(mPosition) + ", offset=" + mOffset + "}";
        }

        /**
         * @param position The position
         * @return The string representation
         */
        public static String positionToString(@Position int position) {
            return switch (position) {
                case POSITION_LEFT -> "left";
                case POSITION_TOP -> "top";
                case POSITION_RIGHT -> "right";
                case POSITION_BOTTOM -> "bottom";
                default -> throw new IllegalStateException("Unexpected value: " + position);
            };
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mDisplayId);
            dest.writeFloat(mWidth);
            dest.writeFloat(mHeight);
            dest.writeInt(mPosition);
            dest.writeFloat(mOffset);
            dest.writeTypedList(mChildren);
        }

        /**
         * Print the object's state and debug information into the given stream.
         * @param ipw The stream to dump information to.
         */
        public void dump(IndentingPrintWriter ipw) {
            ipw.println(this);
            ipw.increaseIndent();
            for (TreeNode child : mChildren) {
                child.dump(ipw);
            }
            ipw.decreaseIndent();
        }

        /**
         * @param child The child to add
         */
        @VisibleForTesting
        public void addChild(TreeNode child) {
            mChildren.add(child);
        }
    }
}

/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.blocks;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records the player edits that the procedural far-horizon renderers ({@code FarTerrainState},
 * {@code FarTreeState}) must honour, so a felled tree or a reshaped hill stops being regenerated
 * purely from the noise seed once the area leaves the loaded chunk grid.
 *
 * <p>Two persisted overlays, both keyed by <b>canonical</b> coordinates (wrapped modulo the world
 * period, like the chunk saves in {@code ZipFileRepository}) so an edit is consistent across the
 * torus seam :
 * <ul>
 *   <li><b>removed tree anchors</b> : the scatter cells whose trunk the player cut down — the far
 *       billboards skip them (see {@code NoiseTerrainGenerator#forEachTreeAnchor}).</li>
 *   <li><b>height overrides</b> : the measured ground height of a far-terrain heightmap <b>sample</b>
 *       that the player reshaped — the far heightmap is patched there (see {@code FarTerrainState}).
 *       Keyed by sample (not by the raw edited column) so the value is the ground at that exact
 *       sample : an off-sample single-block edit therefore moves nothing (sub-resolution), instead of
 *       dragging a sample to a slope-mismatched height and flashing water where there is land.</li>
 * </ul>
 *
 * <p>The far renderers run partly off the render thread, so the collections are concurrent. Tree and
 * terrain edits are tracked by <b>separate</b> version counters so a relief edit does not needlessly
 * rebuild the far-tree mesh and vice versa. The transient {@link #pollDirtyColumns() dirty-column}
 * queue carries freshly-edited <i>world</i> columns to {@code FarTerrainState}, which converts each to
 * the affected sample and re-measures it from the live world. A {@link #isDirty() dirty} flag tells
 * the persistence layer there is something to save.
 *
 * @author Cedric de Launois
 */
public class WorldEditOverlay {

    /** Canonical scatter-cell keys whose tree was cut down (persisted). */
    private final Set<Long> removedTrees = ConcurrentHashMap.newKeySet();

    /** Canonical far-terrain SAMPLE column key -> measured ground height (persisted). */
    private final Map<Long, Float> heightOverrides = new ConcurrentHashMap<>();

    /** Freshly edited WORLD columns awaiting a far-sample refresh (transient, not persisted). */
    private final Set<Long> dirtyColumns = ConcurrentHashMap.newKeySet();

    // Separate counters so a relief edit doesn't rebuild the far trees, nor a felled tree the relief.
    private volatile int treeVersion;
    private volatile int terrainVersion;
    // Set on every persisted change, cleared by the persistence layer once written to disk.
    private volatile boolean dirty;

    /** Packs two int coordinates into a single long key (recoverable via {@link #unpackX}/{@link #unpackZ}). */
    public static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    public static int unpackZ(long key) {
        return (int) key;
    }

    // --- Trees ---------------------------------------------------------------------------------------

    /** Marks the scatter cell {@code cellKey} as cut down : its far billboard is no longer drawn. */
    public void removeTree(long cellKey) {
        if (removedTrees.add(cellKey)) {
            treeVersion++;
            dirty = true;
        }
    }

    /** Undoes {@link #removeTree} (e.g. a log replanted at the trunk) so the far billboard returns. */
    public void restoreTree(long cellKey) {
        if (removedTrees.remove(cellKey)) {
            treeVersion++;
            dirty = true;
        }
    }

    public boolean isTreeRemoved(long cellKey) {
        return !removedTrees.isEmpty() && removedTrees.contains(cellKey);
    }

    public Set<Long> getRemovedTrees() {
        return removedTrees;
    }

    /** Bumped only on tree changes ; {@code FarTreeState} rebuilds when it moves. */
    public int getTreeVersion() {
        return treeVersion;
    }

    // --- Relief : dirty-column queue + sample overrides ----------------------------------------------

    /** Queues a freshly edited WORLD column (raw, not canonical) for a far-sample refresh. */
    public void addDirtyColumn(long worldColumnKey) {
        dirtyColumns.add(worldColumnKey);
        terrainVersion++;
    }

    public boolean hasDirtyColumns() {
        return !dirtyColumns.isEmpty();
    }

    /** Returns and clears the queued dirty world columns (drained by {@code FarTerrainState}). */
    public long[] pollDirtyColumns() {
        if (dirtyColumns.isEmpty()) {
            return new long[0];
        }
        Long[] keys = dirtyColumns.toArray(new Long[0]);
        long[] out = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = keys[i];
            dirtyColumns.remove(keys[i]);
        }
        return out;
    }

    /** Records (or, with NaN, clears) the measured ground height of a canonical far-terrain sample. */
    public void putHeight(long sampleKey, float groundY) {
        Float previous = Float.isNaN(groundY) ? heightOverrides.remove(sampleKey)
                : heightOverrides.put(sampleKey, groundY);
        boolean changed = Float.isNaN(groundY) ? previous != null : (previous == null || previous != groundY);
        if (changed) {
            dirty = true;
        }
    }

    public Map<Long, Float> getHeightOverrides() {
        return heightOverrides;
    }

    public int getTerrainVersion() {
        return terrainVersion;
    }

    // --- Change tracking -----------------------------------------------------------------------------

    /** True when there is nothing to persist (the transient dirty-column queue does not count). */
    public boolean isEmpty() {
        return removedTrees.isEmpty() && heightOverrides.isEmpty();
    }

    public boolean isDirty() {
        return dirty;
    }

    public void clearDirty() {
        dirty = false;
    }

    /** Drops every edit (used when switching worlds before reloading the new world's overlay). */
    public void clear() {
        boolean had = !isEmpty() || !dirtyColumns.isEmpty();
        removedTrees.clear();
        heightOverrides.clear();
        dirtyColumns.clear();
        if (had) {
            treeVersion++;
            terrainVersion++;
            dirty = true;
        }
    }
}

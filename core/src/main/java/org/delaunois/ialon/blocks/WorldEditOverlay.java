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
 *       the player reshaped — the far heightmap is patched there (see {@code FarTerrainState}).</li>
 * </ul>
 *
 * <p>The overrides are (re)computed lazily, when an edited chunk is <i>unfetched</i> (leaves the
 * loaded grid and becomes visible at the horizon) — never on the edit itself, since the far terrain
 * is discarded inside the loaded grid. This class is therefore just the shared, thread-safe store ;
 * the collections are concurrent because the far-tree mesh is built off the render thread. A
 * {@link #isDirty() dirty} flag tells the persistence layer there is something to save.
 *
 * @author Cedric de Launois
 */
public class WorldEditOverlay {

    /** Canonical scatter-cell keys whose tree was cut down (persisted). */
    private final Set<Long> removedTrees = ConcurrentHashMap.newKeySet();

    /** Canonical far-terrain SAMPLE column key -> measured ground height (persisted). */
    private final Map<Long, Float> heightOverrides = new ConcurrentHashMap<>();

    /**
     * Chunk columns (raw {@code pack(chunkX, chunkZ)}) edited this session and not yet reflected at the
     * horizon. Transient (not persisted) : a cheap marker set on every edit so {@code FarTerrainState}
     * can, when such a column is unfetched, recompute its far samples — and skip the scan entirely for
     * the untouched columns the player merely walks past. Persisted worlds rely on {@link #heightOverrides}
     * replayed at load instead.
     */
    private final Set<Long> editedColumns = ConcurrentHashMap.newKeySet();

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
            dirty = true;
        }
    }

    /** Undoes {@link #removeTree} (e.g. a log replanted at the trunk) so the far billboard returns. */
    public void restoreTree(long cellKey) {
        if (removedTrees.remove(cellKey)) {
            dirty = true;
        }
    }

    public boolean isTreeRemoved(long cellKey) {
        return !removedTrees.isEmpty() && removedTrees.contains(cellKey);
    }

    public Set<Long> getRemovedTrees() {
        return removedTrees;
    }

    // --- Relief : sample height overrides ------------------------------------------------------------

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

    // --- Edited-column markers (transient) -----------------------------------------------------------

    /** Flags a chunk column (raw {@code pack(chunkX, chunkZ)}) as edited, pending a far-relief refresh. */
    public void markColumnEdited(long chunkColumnKey) {
        editedColumns.add(chunkColumnKey);
    }

    public boolean isColumnEdited(long chunkColumnKey) {
        return !editedColumns.isEmpty() && editedColumns.contains(chunkColumnKey);
    }

    /** Clears the marker once the column's far samples have been recomputed (on unfetch). */
    public void clearColumnEdited(long chunkColumnKey) {
        editedColumns.remove(chunkColumnKey);
    }

    // --- Change tracking -----------------------------------------------------------------------------

    /** True when there is nothing to persist. */
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
        editedColumns.clear();
        if (!isEmpty()) {
            removedTrees.clear();
            heightOverrides.clear();
            dirty = true;
        }
    }
}

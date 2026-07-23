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

import com.simsilica.mathd.Vec3i;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * A dense, incrementally maintained list of chunk locations, so a hot pass can walk {@code int[]} instead
 * of a map of {@link Vec3i}.
 *
 * <p><b>Why this exists.</b> Two passes of {@link ChunkPager} have to visit every entry of a set of chunks
 * on the render thread, every time they run : applying cave-culling cull hints (~660 scene pages) and
 * caching face connectivity for the flood-fill (~900 occluders). Iterating a {@code Map} for that means, per
 * entry, dereferencing a {@code Map.Entry}, its key, and its value — pointer chasing through objects
 * scattered across the heap, and on ART every one of those reference loads goes through the concurrent
 * collector's read barrier. Measured on device at ~5 us per entry for work whose arithmetic is a handful of
 * integer operations; flattening the cull pass to primitive arrays took its desktop cost from 0.160 ms to
 * 0.009 ms. This class is that flattening, factored out so the second user does not copy it.
 *
 * <p>Maintained incrementally rather than rebuilt : rebuilding would mean performing the very map scan this
 * replaces. Removal is a swap with the last entry, so the caller's parallel payload arrays must follow —
 * see {@link #remove}. Not thread safe : render-thread scratch, like the passes it serves.
 */
final class ChunkSlots {

    private int[] x = new int[0];
    private int[] y = new int[0];
    private int[] z = new int[0];
    private int count;
    // Locations are few per frame here (a handful of track/untrack calls), so a map is the right shape for
    // the bookkeeping side. It is the per-PASS iteration that had to stop being a map walk, not this.
    private final Map<Vec3i, Integer> slotOf = new HashMap<>();

    int size() {
        return count;
    }

    /*
     * The backing arrays, exposed so a hot pass can hoist them into locals.
     *
     * Per-element accessors (x(i), y(i), z(i)) were the first shape of this and cost 9x on the very loop the
     * class exists to speed up — desktop apply went from 0.009 ms back up to 0.081 ms, because a call through
     * another object's field cannot be hoisted out of the loop the way `final int[] xs = ...` can. Handing out
     * the arrays keeps one implementation of the bookkeeping without paying for the abstraction where it
     * matters.
     *
     * Contract for callers : bound the walk by size(), and re-read them after any put(), which may replace
     * them when it grows.
     */
    int[] xs() {
        return x;
    }

    int[] ys() {
        return y;
    }

    int[] zs() {
        return z;
    }

    /**
     * The slot holding this location, appending it if it is not present yet.
     *
     * <p>The caller writes its payload at the returned slot either way, which is why this deliberately does
     * not report whether the slot is new : for both users, writing the payload is the same statement.
     */
    int put(Vec3i location) {
        Integer existing = slotOf.get(location);
        if (existing != null) {
            return existing;
        }
        if (count == x.length) {
            int capacity = Math.max(64, count * 2);
            x = Arrays.copyOf(x, capacity);
            y = Arrays.copyOf(y, capacity);
            z = Arrays.copyOf(z, capacity);
        }
        x[count] = location.x;
        y[count] = location.y;
        z[count] = location.z;
        slotOf.put(location.clone(), count);
        return count++;
    }

    /**
     * Swap-removes a location, keeping the slots dense so a pass can walk {@code 0..size()}.
     *
     * <p>Returns the two slots involved packed into one long — allocation-free, and stateless in a way a
     * "call this then read that field" protocol would not be. Decode with {@link #vacated} and
     * {@link #movedFrom} : when {@code movedFrom} is not -1 the caller <b>must</b> copy its own payload from
     * that slot to the vacated one, or its arrays fall out of step with these.
     *
     * @return {@link #ABSENT} when the location was not present
     */
    long remove(Vec3i location) {
        Integer slot = slotOf.remove(location);
        if (slot == null) {
            return ABSENT;
        }
        int vacated = slot;
        int last = count - 1;
        int movedFrom = -1;
        if (vacated != last) {
            x[vacated] = x[last];
            y[vacated] = y[last];
            z[vacated] = z[last];
            slotOf.put(new Vec3i(x[vacated], y[vacated], z[vacated]), vacated);
            movedFrom = last;
        }
        count = last;
        return (vacated & 0xFFFFFFFFL) | ((long) movedFrom << 32);
    }

    static final long ABSENT = -1L;

    /** The slot freed by {@link #remove}. */
    static int vacated(long removal) {
        return (int) (removal & 0xFFFFFFFFL);
    }

    /** The slot whose entry was moved into the vacated one, or -1 when nothing had to move. */
    static int movedFrom(long removal) {
        return (int) (removal >> 32);
    }

    void clear() {
        slotOf.clear();
        count = 0;
    }

}

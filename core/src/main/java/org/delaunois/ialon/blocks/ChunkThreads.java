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

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import java.util.concurrent.ThreadFactory;

/**
 * Thread priorities for the background chunk work, and the factory that applies them.
 *
 * <p><b>Why these exist.</b> Every background thread used to be created at {@link Thread#NORM_PRIORITY},
 * i.e. competing with the render thread as an equal. On the target device that is a losing arrangement :
 * the chunk pool alone is up to 6 threads (see {@code IalonConfig.defaultChunkPoolSize}), plus physics,
 * plus persistence, against a mid-range 8-core big.LITTLE chip. There is no headroom, and the scheduler is
 * free to answer by descheduling the render thread or migrating it onto a little core.
 *
 * <p>That is what the device measurements look like. In a burst of slow-merge warnings while the player was
 * moving, {@code ChunkMeshBatchState}'s phases all inflated <b>together</b> and by the same factor
 * ({@code count} 193 to 736 us, {@code copy} 6201 to 25496 us : x3.8 and x4.1) — the signature of the whole
 * thread running slower, not of one phase doing more work. There was not a single GC line in that window,
 * so it was not collector interference ; and the warnings coincided with movement, i.e. with the chunk pool
 * being busy, and stopped once the player was idle.
 *
 * <p>Chunk work is throughput work with seconds of slack (a chunk crossing's slab is generated and meshed in
 * a fraction of the time it takes the player to reach the next boundary — see {@code ChunkPagingBenchmark}),
 * whereas the render thread has 16 ms. Trading worker throughput for render smoothness is the right way
 * round, so the workers ask to be scheduled behind it.
 *
 * <p><b>Platform note.</b> This is an Android measure. ART maps Java thread priority onto the kernel's nice
 * value, so it genuinely arbitrates there ; HotSpot on Linux ignores Java priorities unless explicitly
 * enabled and privileged, so desktop behaviour is unchanged either way.
 */
public final class ChunkThreads {

    /**
     * Chunk generation and meshing : the CPU-hungry pool, and the one that must not outrank the frame.
     * Nothing on the render thread waits for it — results arrive through listeners.
     */
    public static final int GENERATION_PRIORITY = Thread.NORM_PRIORITY - 2;

    /**
     * The pagers' single coordination thread : cheap, but it blocks on generation, so it gains nothing from
     * outranking the frame either.
     */
    public static final int PAGING_PRIORITY = Thread.NORM_PRIORITY - 1;

    /** Chunk and overlay persistence : file I/O, never urgent. */
    public static final int PERSISTENCE_PRIORITY = Thread.MIN_PRIORITY;

    /**
     * Interactive edits keep the default priority <b>deliberately</b> : the render thread blocks on this
     * one ({@code ChunkManager.requestOrderedMeshChunks}) so that a placed block appears in the same frame.
     * Demoting it would put the frame behind a demoted thread — it would add exactly the latency the
     * dedicated edit executor exists to avoid.
     */
    public static final int EDIT_PRIORITY = Thread.NORM_PRIORITY;

    private ChunkThreads() {
        // constants and factory only
    }

    public static ThreadFactory factory(String nameFormat, int priority) {
        return new ThreadFactoryBuilder()
                .setNameFormat(nameFormat)
                .setPriority(priority)
                .build();
    }

}

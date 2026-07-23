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

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;

import java.util.HashSet;
import java.util.Set;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.ChunkLiquidManager;

/**
 * An application state to handle the liquid simulation.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkLiquidManagerState extends BaseAppState {

    /** Wall-clock the simulation may spend on the render thread per frame, water and lava together. */
    private static final long TICK_BUDGET_NANOS = 4_000_000L;

    /** Steps always granted, so the simulation never stalls completely however tight the budget is. */
    private static final int MIN_STEPS_PER_TICK = 8;

    /** How often the deadline is actually checked, in steps (see the note in {@link #drain}). */
    private static final int CLOCK_CHECK_STEPS = 16;

    @Getter
    private final ChunkLiquidManager chunkLiquidManager;

    private float elapsed = 0;
    private float elapsedLava = 0;
    private final IalonConfig config;
    // Reused accumulator : a tick can run thousands of steps, each of which used to hand back its own set.
    private final Set<Vec3i> updatedChunks = new HashSet<>();

    public ChunkLiquidManagerState(IalonConfig config) {
        this.config = config;
        this.chunkLiquidManager = new ChunkLiquidManager(config);
    }

    @Override
    protected void initialize(Application app) {
        // Nothing to do
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        // Nothing to do
    }

    @Override
    protected void onDisable() {
        // Nothing to do
    }

    @Override
    public void update(float tpf) {
        // One budget for the whole state, water first : both queues together cannot exceed it.
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;

        // Water (and shared liquid recession) on its own cadence.
        elapsed += tpf;
        if (elapsed > (1 / config.getWaterSimulationSpeed()) && chunkLiquidManager.queueSize() > 0) {
            elapsed = 0;
            drain(false, deadline);
        }

        // Lava on its own, slower cadence (config.lavaSimulationSpeed < waterSimulationSpeed).
        elapsedLava += tpf;
        if (elapsedLava > (1 / config.getLavaSimulationSpeed()) && chunkLiquidManager.lavaQueueSize() > 0) {
            elapsedLava = 0;
            drain(true, deadline);
        }
    }

    /**
     * Advances one liquid queue by as many BFS nodes as fit in the remaining budget, then queues the mesh
     * rebuild of the chunks that changed.
     *
     * <p>The bound is the point. The flow is a BFS whose own steps enqueue their neighbours, so draining
     * "the queue as it stands" — which is what this used to do — is unbounded work : a waterfall spreading
     * down a hillside grows its wavefront faster than a frame can consume it, each tick starts from a
     * bigger queue than the last, and the frame time runs away with it (60-75 ms per frame, measured on
     * device). Capped, the surplus simply stays queued for the next tick : the water advances a little
     * more slowly under load instead of freezing the frame, which is exactly how a tick-based liquid
     * model is supposed to degrade.
     */
    private void drain(boolean lava, long deadline) {
        int queueSize = lava ? chunkLiquidManager.lavaQueueSize() : chunkLiquidManager.queueSize();
        updatedChunks.clear();
        int steps = 0;
        while (steps < queueSize) {
            if (lava) {
                chunkLiquidManager.stepLava(updatedChunks);
            } else {
                chunkLiquidManager.step(updatedChunks);
            }
            steps++;
            // The clock is only read every so often : a step can be a handful of microseconds, and
            // nanoTime() is not free on Android. MIN_STEPS_PER_TICK guarantees forward progress even if
            // the budget was already spent by the other liquid before this call.
            if (steps >= MIN_STEPS_PER_TICK && steps % CLOCK_CHECK_STEPS == 0 && System.nanoTime() > deadline) {
                break;
            }
        }

        if (!updatedChunks.isEmpty()) {
            chunkLiquidManager.updateChunkMesh(updatedChunks);
        }
        if (steps < queueSize && log.isDebugEnabled()) {
            log.debug("{} simulation budget spent after {}/{} steps, {} chunks to re-mesh",
                    lava ? "Lava" : "Water", steps, queueSize, updatedChunks.size());
        }
    }

}

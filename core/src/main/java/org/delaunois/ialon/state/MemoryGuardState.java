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
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.serialize.IalonConfigRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Last-resort safety net against {@link OutOfMemoryError} : when the Java (ART) heap gets close to its
 * limit, or when a chunk task already failed with an OOM, this state lowers the render distance
 * ({@code gridRadius}) by one step and frees the now out-of-range chunks. The grid loads
 * {@code (2*gridRadius+1)^2 * gridHeight} chunks, each holding its blocks {@code short[]} + lightMap
 * {@code byte[]} on the heap, so shrinking the radius is the most effective heap relief available at
 * runtime. The lowered value is persisted so the next launch starts at the safer setting.
 *
 * <p>This matters mostly on Android (much tighter per-process heap than desktop). It is cheap : the
 * heap is sampled only once per {@link #CHECK_INTERVAL}, never every frame.
 */
@Slf4j
public class MemoryGuardState extends BaseAppState {

    /** How often (seconds) the heap is sampled. */
    private static final float CHECK_INTERVAL = 1f;
    /** Heap used/max ratio above which memory is considered tight. */
    private static final float HEAP_THRESHOLD = 0.88f;
    /** Number of consecutive high-heap samples required before stepping down (filters transient spikes). */
    private static final int SUSTAINED_SAMPLES = 2;
    /** Seconds to wait after a step-down before checking again, letting the GC and re-paging settle. */
    private static final float COOLDOWN = 5f;

    private final IalonConfig config;

    private float timeSinceCheck = 0f;
    private float cooldownRemaining = 0f;
    private int highSamples = 0;

    public MemoryGuardState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        // nothing to allocate
    }

    @Override
    protected void cleanup(Application app) {
        // nothing to release
    }

    @Override
    protected void onEnable() {
        // no-op
    }

    @Override
    protected void onDisable() {
        // no-op
    }

    @Override
    public void update(float tpf) {
        if (cooldownRemaining > 0f) {
            cooldownRemaining -= tpf;
            return;
        }

        timeSinceCheck += tpf;
        if (timeSinceCheck < CHECK_INTERVAL) {
            return;
        }
        timeSinceCheck = 0f;

        boolean oom = config.getChunkManager().consumeMemoryPressure();

        Runtime rt = Runtime.getRuntime();
        long max = rt.maxMemory();
        float ratio = max > 0 ? (float) (rt.totalMemory() - rt.freeMemory()) / max : 0f;
        if (ratio > HEAP_THRESHOLD) {
            highSamples++;
        } else {
            highSamples = 0;
        }

        if (oom || highSamples >= SUSTAINED_SAMPLES) {
            stepDownRenderDistance(oom, ratio);
        }
    }

    /**
     * Reduces the render distance by one (down to {@code gridRadiusMin}), applies it live, hints a GC and
     * persists the safer value. A GC is hinted even when already at the minimum, as a last attempt to
     * reclaim garbage before a possible OOM.
     */
    private void stepDownRenderDistance(boolean oom, float ratio) {
        highSamples = 0;
        cooldownRemaining = COOLDOWN;

        int current = config.getGridRadius();
        int min = config.getGridRadiusMin();
        if (current <= min) {
            log.warn("Memory pressure (heap {}%, oom={}) but render distance already at minimum ({})",
                    Math.round(ratio * 100), oom, min);
            System.gc();
            return;
        }

        int target = current - 1;
        log.warn("Memory pressure (heap {}%, oom={}) - reducing render distance {} -> {}",
                Math.round(ratio * 100), oom, current, target);

        applyRenderDistance(target);
        System.gc(); // reclaim the chunks just freed by the smaller grid
        // Persist only the game settings (render distance), not the player state : survive a later crash
        // with the safer setting without clobbering the live player position with a stale config value.
        IalonConfigRepository.saveGameSettings(config);
    }

    /**
     * Applies the new render distance, preferring {@link SettingsState#applyRenderDistance(int)} (which
     * also keeps the settings slider and the far terrain / far trees in sync). Falls back to a minimal
     * direct apply if the settings state is not attached.
     */
    private void applyRenderDistance(int radius) {
        SettingsState settings = getState(SettingsState.class);
        if (settings != null) {
            settings.applyRenderDistance(radius);
            return;
        }

        config.setGridRadius(radius);
        int size = config.getGridRadius() * 2 + 1;
        BlocksConfig.getInstance().setGrid(new Vec3i(size, config.getGridHeight() * 2 + 1, size));
        ChunkPagerState pagerState = getState(ChunkPagerState.class);
        if (pagerState != null) {
            pagerState.getChunkPager().updateGridSize();
        }
    }

}

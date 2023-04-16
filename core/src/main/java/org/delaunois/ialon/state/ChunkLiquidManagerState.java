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

import org.delaunois.ialon.ChunkLiquidManager;
import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonConfig;

import java.util.HashSet;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;

/**
 * An application state to handle the liquid simulation.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkLiquidManagerState extends BaseAppState {

    private ChunkManager chunkManager;
    private ChunkLiquidManager chunkLiquidManager;
    private float elapsed = 0;
    private Ialon app;

    @Override
    protected void initialize(Application app) {
        this.chunkManager = ((Ialon) app).getChunkManager();
        this.chunkLiquidManager = chunkManager.getChunkLiquidManager();
        this.app = (Ialon) app;
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
        elapsed += tpf;
        int queueSize = chunkLiquidManager.queueSize();
        if (elapsed > (1 / IalonConfig.getInstance().getWaterSimulationSpeed()) && queueSize > 0) {
            Set<Vec3i> updatedChunks = new HashSet<>();
            for (int i = 0; i < queueSize; i ++) {
                updatedChunks.addAll(chunkLiquidManager.step());
            }
            if (!updatedChunks.isEmpty()) {
                chunkManager.requestMeshChunks(updatedChunks);
                for (Vec3i location : updatedChunks) {
                    app.asyncSave(location);
                }
            }
            elapsed = 0;
        }
    }

}

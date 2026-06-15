/**
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

package org.delaunois.ialon.serialize;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Properties that define a world : its identity (id/name), its terrain seed and the high-level
 * generation knobs the player picks when creating it. Persisted per-world in
 * {@code worlds/<id>/world.yml} and fixed once the world is created. These are the WORLD properties,
 * as opposed to the global GAME settings ({@link GameSettingsDTO}) and the per-world player state
 * ({@link PlayerStateDTO}).
 *
 * <p>The defaults below reproduce the original single world (seed 2 and the
 * {@code NoiseTerrainGenerator} constants), so a world.yml with missing fields, or the migrated
 * {@code default} world, generates exactly as before.</p>
 *
 * @author Cedric de Launois
 */
@Getter
@Setter
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorldParams {

    private String id = IalonConfig.DEFAULT_WORLD_ID;
    private String name = "World 1";
    private long seed = 2;
    private boolean finiteWorld = true;
    private int worldSizeChunks = 256;
    private float waterHeight = 30;
    private float reliefAmplitude = 1f;
    private float reliefFrequency = 1f;
    private float treeDensity = 0.70f;
    private float forestPatchSize = 1f;

    public WorldParams(IalonConfig config) {
        this.id = config.getWorldId();
        this.name = config.getWorldName();
        this.seed = config.getSeed();
        this.finiteWorld = config.isFiniteWorld();
        this.worldSizeChunks = config.getWorldSizeChunks();
        this.waterHeight = config.getWaterHeight();
        this.reliefAmplitude = config.getReliefAmplitude();
        this.reliefFrequency = config.getReliefFrequency();
        this.treeDensity = config.getTreeDensity();
        this.forestPatchSize = config.getForestPatchSize();
    }

    /**
     * Applies these world properties onto the live config. Caller is responsible for invalidating the
     * cached generator/repository afterwards (see WorldSelectionState) so the next generation picks up
     * the new values.
     */
    public void applyTo(IalonConfig config) {
        config.setWorldId(id);
        config.setWorldName(name);
        config.setSeed(seed);
        config.setFiniteWorld(finiteWorld);
        config.setWorldSizeChunks(worldSizeChunks);
        config.setWaterHeight(waterHeight);
        config.setReliefAmplitude(reliefAmplitude);
        config.setReliefFrequency(reliefFrequency);
        config.setTreeDensity(treeDensity);
        config.setForestPatchSize(forestPatchSize);
    }
}

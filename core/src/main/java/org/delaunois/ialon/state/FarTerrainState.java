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
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.terrain.geomipmap.TerrainLodControl;
import com.jme3.terrain.geomipmap.TerrainQuad;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.TerrainGenerator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a distant low-detail terrain ("far horizon") well beyond the loaded voxel chunks.
 *
 * <p>The far terrain is a jME geomipmap {@link TerrainQuad} (built-in distance LOD + culling) whose
 * heightmap is sampled from the <b>same</b> {@link TerrainGenerator#getHeight(Vector3f)} used to
 * generate the voxel chunks, so the distant relief matches exactly what the player walks on. No
 * voxels, no chunk meshing : a single heightfield mesh covers a large finite area cheaply.
 *
 * <p>v1 : finite, static island centered on the world origin. Coloring is a simple lit material
 * (relief shown by the terrain normals under the scene's sun light). Fog and player-recentring are
 * handled separately.
 */
@Slf4j
public class FarTerrainState extends BaseAppState {

    // Heightmap resolution : must be (2^n + 1). 513 -> 513x513 samples.
    private static final int HEIGHTMAP_SIZE = 513;
    // Patch size : must be (2^m + 1) and <= HEIGHTMAP_SIZE. Drives the LOD granularity.
    private static final int PATCH_SIZE = 65;

    private final IalonConfig config;
    private final float extent;

    private SimpleApplication app;

    @Getter
    private TerrainQuad terrain;

    private Material material;
    // Sun direction, shared by reference with the material and refreshed each frame from LightingState.
    private final Vector3f lightDir = new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal();
    // The fog colour is bound (once) to SkyControl's live ground colour so it follows the day/night cycle.
    private boolean fogColorBound = false;

    public FarTerrainState(IalonConfig config) {
        this(config, config.getFarTerrainExtent());
    }

    public FarTerrainState(IalonConfig config, float extent) {
        this.config = config;
        this.extent = extent;
    }

    @Override
    protected void initialize(Application application) {
        this.app = (SimpleApplication) application;

        TerrainGenerator generator = config.getTerrainGenerator();
        if (generator == null) {
            log.warn("No terrain generator available : far terrain disabled");
            return;
        }

        // World units between two heightmap samples.
        float step = extent / (HEIGHTMAP_SIZE - 1);
        float[] heightmap = sampleHeightmap(generator, step);

        terrain = new TerrainQuad("FarTerrain", PATCH_SIZE, HEIGHTMAP_SIZE, heightmap);
        material = createMaterial();
        terrain.setMaterial(material);
        // Uniform scale : heights were pre-divided by step (see sampleHeightmap), so world Y is exact
        // again after scaling, AND a uniform scale keeps the terrain normals correct in world space.
        terrain.setLocalScale(step, step, step);
        // The far terrain spans the whole island, including under the loaded voxel chunks. Sinking it
        // keeps it buried inside the solid voxel volume near the player (so the real chunks hide it /
        // avoid z-fighting), while it still rises into view as the horizon beyond the chunks.
        terrain.setLocalTranslation(0f, config.getFarTerrainVerticalOffset(), 0f);
        terrain.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);

        // Distance-based LOD : reduces far patches' triangle count.
        TerrainLodControl lodControl = new TerrainLodControl(terrain, app.getCamera());
        terrain.addControl(lodControl);

        log.info("Far terrain generated : {}x{} heightmap, extent {} units (step {})",
                HEIGHTMAP_SIZE, HEIGHTMAP_SIZE, extent, step);
    }

    /**
     * Samples the generator height over a regular grid centered on the origin. The world coordinate
     * of sample (i, j) matches the position {@link TerrainQuad} gives to that heightmap cell, so the
     * far relief lines up with the voxel terrain.
     */
    private float[] sampleHeightmap(TerrainGenerator generator, float step) {
        float[] heightmap = new float[HEIGHTMAP_SIZE * HEIGHTMAP_SIZE];
        float half = (HEIGHTMAP_SIZE - 1) / 2f;
        Vector3f sample = new Vector3f();
        for (int j = 0; j < HEIGHTMAP_SIZE; j++) {
            float worldZ = (j - half) * step;
            int row = j * HEIGHTMAP_SIZE;
            for (int i = 0; i < HEIGHTMAP_SIZE; i++) {
                float worldX = (i - half) * step;
                // Pre-divided by step : after the uniform localScale (step) the world Y is exact again.
                heightmap[row + i] = generator.getHeight(sample.set(worldX, 0f, worldZ)) / step;
            }
        }
        return heightmap;
    }

    private Material createMaterial() {
        // Custom material : fog lives HERE (only the terrain fades to the horizon colour), so the
        // sky — rendered separately by SkyState — keeps its blue gradient untouched.
        Material mat = new Material(app.getAssetManager(), "Shaders/FarTerrain.j3md");
        mat.setColor("BaseColor", config.getFarTerrainBaseColor());
        // Initial placeholder : update() rebinds this to SkyControl's live ground colour (day/night).
        ColorRGBA ground = config.getGroundDayColor();
        mat.setColor("FogColor", new ColorRGBA(ground.r, ground.g, ground.b, 1f));
        mat.setFloat("FogDistance", config.getFarTerrainFogDistance());
        mat.setFloat("FogDensity", config.getFarTerrainFogDensity());
        mat.setFloat("DepthBias", config.getFarTerrainDepthBias());
        // Discard the far terrain within the loaded-chunk region : it only shows beyond the voxels,
        // starting where the voxel fade begins (gridRadius*chunkSize - chunkSize).
        mat.setFloat("InnerRadius", (float) config.getGridRadius() * config.getChunkSize() - config.getChunkSize());
        // Shared instance : update() mutates it in place so the sun direction follows day/night.
        mat.setVector3("LightDir", lightDir);
        return mat;
    }

    @Override
    protected void cleanup(Application application) {
        // nothing to release : the terrain mesh is GC'd with this state
    }

    @Override
    protected void onEnable() {
        if (terrain != null && terrain.getParent() == null) {
            Node node = app.getRootNode();
            node.attachChild(terrain);
        }
    }

    @Override
    protected void onDisable() {
        if (terrain != null && terrain.getParent() != null) {
            terrain.removeFromParent();
        }
    }

    @Override
    public void update(float tpf) {
        // Keep the far terrain lit by the same sun as the world (day/night cycle). The material holds
        // the lightDir instance by reference, so mutating it in place updates the shader uniform.
        LightingState lighting = getState(LightingState.class);
        if (lighting != null && lighting.getDirectionalLight() != null) {
            lightDir.set(lighting.getDirectionalLight().getDirection());
        }

        // Bind the fog colour to SkyControl's live ground colour (mutated in place as time passes), so
        // the terrain fades into the exact current ground colour. Bound once : the shared reference
        // then tracks the day/night cycle automatically.
        if (!fogColorBound && material != null) {
            SkyState sky = getState(SkyState.class);
            if (sky != null && sky.getSkyControl() != null) {
                material.setColor("FogColor", sky.getSkyControl().getGroundColor());
                fogColorBound = true;
            }
        }
    }
}

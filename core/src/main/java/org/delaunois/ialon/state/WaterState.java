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

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkMeshGenerator;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.control.MoonControl;
import org.delaunois.ialon.control.SkyControl;
import org.delaunois.ialon.control.SunControl;

import lombok.extern.slf4j.Slf4j;

/**
 * Feeds the live sun direction and sky colours into the calm-water surface material so the dedicated
 * water shader (sky reflection + Fresnel + sun glint) tracks the day/night cycle. The wave animation
 * itself is driven by {@code g_Time} inside the shader, so it needs no per-frame push.
 *
 * The calm-water material is built lazily by {@link FacesMeshGenerator} (when the first calm-water
 * chunk is meshed), so this state fetches it on demand and simply skips updates until it exists.
 * It throttles its updates to match the slow {@link SunControl}/{@link SkyControl} colour cadence.
 *
 * Note (prototype limitation) : the calm-water geometry is drawn twice (an outside copy and a
 * front-culled inside copy that owns a cloned material). Only the shared outside material is updated
 * here ; the underwater (inside) view keeps the first-frame placeholder sun/sky values.
 */
@Slf4j
public class WaterState extends BaseAppState {

    private static final long UPDATE_THRESHOLD_MS = 200;

    private final IalonConfig config;
    private final Vector3f sunDirection = new Vector3f();
    private final Vector3f moonDirection = new Vector3f();
    private final ColorRGBA skyColor = new ColorRGBA();

    private Material waterMaterial;
    private SunControl sunControl;
    private SkyControl skyControl;
    private MoonControl moonControl;
    private long lastUpdate = 0;

    public WaterState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        // Nothing to do : dependencies are resolved lazily in update().
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do.
    }

    @Override
    protected void onEnable() {
        // Nothing to do.
    }

    @Override
    protected void onDisable() {
        // Nothing to do.
    }

    @Override
    public void update(float tpf) {
        long now = System.currentTimeMillis();
        if (now - lastUpdate < UPDATE_THRESHOLD_MS) {
            return;
        }
        lastUpdate = now;

        if (!resolveDependencies()) {
            return;
        }

        // Direction TOWARDS the sun (world space), as the shader expects for the glint.
        sunDirection.set(sunControl.getPosition()).normalizeLocal();
        waterMaterial.setVector3("SunDirection", sunDirection);
        waterMaterial.setColor("SunColor", sunControl.getSunColor());

        // Overhead sky reflection colour. skyControl.getColor() is the day/night MULTIPLIER (white at
        // noon, dark at night), not the sky's hue : the rendered sky is skyColor(blue) * thatMultiplier.
        // Reflect that product, else the water reflects plain white and looks grey.
        ColorRGBA base = config.getSkyColor();
        ColorRGBA tint = skyControl.getColor();
        skyColor.set(base.r * tint.r, base.g * tint.g, base.b * tint.b, 1f);
        waterMaterial.setColor("SkyColor", skyColor);
        // Horizon haze for grazing reflections : the ground colour is already the dynamic horizon tint
        // (bound to the far-terrain fog), so it tracks day/night on its own.
        waterMaterial.setColor("SkyHorizonColor", skyControl.getGroundColor());

        // Moon direction (optional : only if a MoonState is present). Points TOWARDS the moon ; the
        // shader gates the moon glint by its height, so it only shows when the moon is up (night).
        if (moonControl == null) {
            MoonState moonState = getStateManager().getState(MoonState.class);
            if (moonState != null) {
                moonControl = moonState.getMoonControl();
            }
        }
        if (moonControl != null) {
            moonDirection.set(moonControl.getPosition()).normalizeLocal();
            waterMaterial.setVector3("MoonDirection", moonDirection);
        }
    }

    private boolean resolveDependencies() {
        if (waterMaterial == null) {
            ChunkMeshGenerator generator = BlocksConfig.getInstance().getChunkMeshGenerator();
            if (generator instanceof FacesMeshGenerator) {
                waterMaterial = ((FacesMeshGenerator) generator).getCalmWaterMaterial();
            }
        }
        if (sunControl == null) {
            SunState sunState = getStateManager().getState(SunState.class);
            if (sunState != null) {
                sunControl = sunState.getSunControl();
            }
        }
        if (skyControl == null) {
            SkyState skyState = getStateManager().getState(SkyState.class);
            if (skyState != null) {
                skyControl = skyState.getSkyControl();
            }
        }
        return waterMaterial != null && sunControl != null && skyControl != null;
    }
}

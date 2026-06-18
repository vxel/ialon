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

package org.delaunois.ialon.control;

import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkyControl extends AbstractControl {

    @Setter
    private SunControl sunControl;

    @Getter
    private final ColorRGBA groundColor = new ColorRGBA();

    @Getter
    private final ColorRGBA color = new ColorRGBA();

    // Reused each update to feed the sky shader's sun direction uniform without allocating in the loop.
    private final Vector3f sunDir = new Vector3f();
    private long lastUpdate = 0;
    private final IalonConfig config;

    public SkyControl(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void controlUpdate(float tpf) {
        long now = System.currentTimeMillis();
        if (this.sunControl != null && (lastUpdate == 0 || now - lastUpdate > getUpdateThreshold())) {
            lastUpdate = now;
            updateSky();
        }
    }

    /**
     * Recomputes the sky colour and horizon glow immediately, bypassing the update throttle. Call this
     * after the time of day is changed abruptly (e.g. switching worlds) : the throttle is unbounded for
     * a slow time factor, so otherwise the sky would stay on the previous world's day/night colour for
     * several seconds. The caller must refresh the sun first so {@link SunControl#getSunHeight()} is current.
     */
    public void forceUpdate() {
        if (sunControl != null && spatial != null) {
            updateSky();
            lastUpdate = System.currentTimeMillis();
        }
    }

    private void updateSky() {
        float sunHeight = sunControl.getSunHeight();
        float shift = FastMath.clamp(FastMath.pow(sunHeight * 2, 4), 0, 1);

        if (sunHeight > 0) {
            color.interpolateLocal(config.getSkyEveningColor(), config.getSkyDayColor(), shift);
            groundColor.interpolateLocal(config.getGroundEveningColor(), config.getGroundDayColor(), shift);
        } else {
            color.interpolateLocal(config.getSkyEveningColor(), config.getSkyNightColor(), shift);
            groundColor.interpolateLocal(config.getGroundEveningColor(), config.getGroundNightColor(), shift);
        }

        Material mat = ((Geometry) spatial).getMaterial();
        mat.setColor("Color", color);

        // Direction (camera -> sun) and glow intensity for the sky shader's horizon glow. The glow
        // peaks when the sun is near the horizon (sunrise/sunset) and vanishes at noon / deep night.
        sunDir.set(sunControl.getPosition()).normalizeLocal();
        mat.setVector3("SunDirection", sunDir);
        float glow = FastMath.clamp(1f - FastMath.abs(sunHeight) * 3f, 0f, 1f);
        mat.setFloat("GlowStrength", glow);
    }

    /**
     * The colour to use for a sky reflection (calm water surface, far-terrain water) : the configured
     * sky hue modulated by the live day/night multiplier ({@link #getColor()}). {@link #getColor()}
     * alone is that multiplier (white at noon), NOT the sky hue, so reflecting it directly would read
     * grey ; the rendered sky is skyColor * multiplier, which is what this returns. Shared by
     * WaterState and FarTerrainState so near and far water reflect the same sky.
     */
    public ColorRGBA getReflectionSkyColor(ColorRGBA store) {
        ColorRGBA base = config.getSkyColor();
        return store.set(base.r * color.r, base.g * color.g, base.b * color.b, 1f);
    }

    private float getUpdateThreshold() {
        if (config.getTimeFactor() > 0) {
            return  1 / (config.getTimeFactor() * 2);
        } else {
            return Float.MAX_VALUE;
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }
}

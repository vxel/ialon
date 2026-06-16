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
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.math.ColorRGBA;
import com.jme3.post.FilterPostProcessor;
import com.jme3.renderer.Camera;
import com.jme3.renderer.ViewPort;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.SkyControl;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies an underwater post-process (bluish distance fog + view ripple) whenever the camera dips
 * below the water surface ({@link IalonConfig#getWaterHeight()}). The effect lives in a
 * {@link FilterPostProcessor} carrying a single {@link UnderwaterFilter}.
 *
 * The FilterPostProcessor adds a full-screen render-to-texture pass, so to keep it free above water
 * (important on the Android target) it is only attached to the main viewport while the camera is
 * submerged and removed as soon as it surfaces. The filter's {@link UnderwaterFilter#setIntensity}
 * ramps over a small {@link #FADE_BAND} just below the surface so the effect fades in smoothly
 * instead of popping on at the exact water line.
 *
 * The fog colour tracks the day/night cycle : the authored underwater hue (kept bluish) is
 * brightness-modulated by {@link SkyControl#getColor()} (white at noon, dark at night), resolved
 * lazily and refreshed at the slow sky cadence, mirroring {@link WaterState}.
 */
@Slf4j
public class UnderwaterState extends BaseAppState {

    // Submersion depth (world units) over which the effect ramps from 0 to full strength.
    private static final float FADE_BAND = 5f;
    // Camera must rise this far above the surface before the processor is detached (hysteresis,
    // avoids attach/detach thrash when bobbing exactly at the water line).
    private static final float SURFACE_MARGIN = 0.5f;
    private static final long COLOR_UPDATE_THRESHOLD_MS = 200;

    private final IalonConfig config;
    private final ColorRGBA fogColor = new ColorRGBA();

    private SimpleApplication app;
    private FilterPostProcessor fpp;
    private UnderwaterFilter filter;
    private SkyControl skyControl;
    private boolean rendering = false;
    private long lastColorUpdate = 0;

    public UnderwaterState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;

        filter = new UnderwaterFilter();
        filter.setFogColor(config.getUnderwaterFogColor());
        filter.setFogDensity(config.getUnderwaterFogDensity());
        filter.setFogDistance(config.getUnderwaterFogDistance());
        filter.setDistortionAmplitude(config.getUnderwaterDistortionAmplitude());
        filter.setDistortionSpeed(config.getUnderwaterDistortionSpeed());
        filter.setDistortionFrequency(config.getUnderwaterDistortionFrequency());
        filter.setManualSrgb(config.isManualGammaEncode());
        filter.setIntensity(0f);

        fpp = new FilterPostProcessor(app.getAssetManager());
        fpp.addFilter(filter);
    }

    @Override
    protected void cleanup(Application app) {
        detach();
    }

    @Override
    protected void onEnable() {
        // Attaching is decided per-frame in update().
    }

    @Override
    protected void onDisable() {
        detach();
    }

    @Override
    public void update(float tpf) {
        Camera cam = app.getCamera();
        float depth = config.getWaterHeight() - cam.getLocation().y;

        if (depth > 0) {
            attach();
            float intensity = Math.min(depth / FADE_BAND, 1f);
            filter.setIntensity(intensity);
            updateFogColor();

        } else if (depth < -SURFACE_MARGIN) {
            detach();
        }
    }

    /**
     * Brightness-modulate the authored underwater hue by the sky day/night level so the fog darkens
     * at night. Throttled to the slow sky colour cadence (the sky control updates infrequently).
     */
    private void updateFogColor() {
        long now = System.currentTimeMillis();
        if (now - lastColorUpdate < COLOR_UPDATE_THRESHOLD_MS) {
            return;
        }
        lastColorUpdate = now;

        if (skyControl == null) {
            SkyState skyState = getStateManager().getState(SkyState.class);
            if (skyState != null) {
                skyControl = skyState.getSkyControl();
            }
        }

        ColorRGBA base = config.getUnderwaterFogColor();
        if (skyControl != null) {
            ColorRGBA level = skyControl.getColor(); // day/night brightness multiplier (white -> dark)
            fogColor.set(base.r * level.r, base.g * level.g, base.b * level.b, base.a);
        } else {
            fogColor.set(base);
        }
        filter.setFogColor(fogColor);
    }

    private void attach() {
        if (!rendering) {
            ViewPort vp = app.getViewPort();
            if (!vp.getProcessors().contains(fpp)) {
                vp.addProcessor(fpp);
            }
            rendering = true;
        }
    }

    private void detach() {
        if (rendering) {
            app.getViewPort().removeProcessor(fpp); // disposes the offscreen framebuffers
            rendering = false;
        }
    }
}

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

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.shapes.Liquid;
import org.delaunois.ialon.control.SkyControl;

import lombok.extern.slf4j.Slf4j;

/**
 * Applies a full-screen liquid post-process (distance fog + view ripple) whenever the camera (eye) is
 * inside a liquid block — water (bluish) or lava (orange). The effect lives in a
 * {@link FilterPostProcessor} carrying a single {@link UnderwaterFilter}.
 *
 * Detection is per-block (the actual block at the camera position), not based on the global water
 * height : this is correct inside caves below sea level (you can be under the water line without being
 * in water) and is what lets the same effect serve both water and lava.
 *
 * The FilterPostProcessor adds a full-screen render-to-texture pass, so to keep it free above the
 * surface (important on the Android target) it is only attached to the main viewport while the camera
 * is submerged and removed as soon as it surfaces.
 *
 * The water fog colour tracks the day/night cycle (brightness-modulated by {@link SkyControl#getColor()},
 * mirroring {@link WaterState}) ; the lava fog stays a constant emissive orange.
 */
@Slf4j
public class UnderwaterState extends BaseAppState {

    private static final long COLOR_UPDATE_THRESHOLD_MS = 200;

    private final IalonConfig config;
    private final ColorRGBA fogColor = new ColorRGBA();
    // Reused when walking the liquid column upward (no per-frame allocation while submerged).
    private final Vector3f probe = new Vector3f();

    private SimpleApplication app;
    private FilterPostProcessor fpp;
    private UnderwaterFilter filter;
    private SkyControl skyControl;
    private boolean rendering = false;
    private long lastColorUpdate = 0;
    // The liquid type the camera is currently submerged in (null when not submerged). Drives the fog
    // colour/params : water (blue, day/night modulated) vs lava (orange, emissive — no night darkening).
    private String currentLiquidType = null;

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
        // Also clears the submerged flag : leaving it set would keep isSubmerged() answering "yes"
        // forever to whoever asks (AmbientSoundState mutes the wildlife on it).
        currentLiquidType = null;
    }

    /**
     * Whether the camera (eye) is currently inside a liquid, below its actual surface. This is the same
     * per-block test that drives the fog — worth reusing rather than repeating, because getting it right
     * means handling a liquid cell that is only partly full and the LIQUID_DISABLED level carried by
     * ordinary blocks. Used by {@link AmbientSoundState} to silence the wildlife underwater.
     */
    public boolean isSubmerged() {
        return currentLiquidType != null;
    }

    /**
     * Which liquid the eye is in — {@link TypeIds#WATER}, {@link TypeIds#LAVA}, or null when not
     * submerged. Callers that react to being submerged AT ALL want {@link #isSubmerged()} ; this is for
     * the ones that must tell the two apart, such as the water ambience loop, which would be plainly
     * wrong playing in lava.
     */
    public String getSubmergedLiquid() {
        return currentLiquidType;
    }

    @Override
    public void update(float tpf) {
        // Per-block detection : the effect is on only when the camera (eye) is actually INSIDE a liquid
        // cell, regardless of the global water height. This is correct in caves below sea level (you
        // can be under the water line without being in water) and is what enables the lava variant.
        Camera cam = app.getCamera();
        Vector3f eye = cam.getLocation();
        Block block = config.getChunkManager() == null
                ? null
                : config.getChunkManager().getBlock(eye).orElse(null);

        // The liquid test comes FIRST : the eye is often inside a non-liquid block (the camera clips into
        // the wall/floor of a freshly dug tunnel), and such a block carries liquidLevel LIQUID_DISABLED
        // (-1), which liquidSurfaceY must never be handed.
        if (block == null || block.getLiquidLevel() <= 0 || eye.y >= liquidSurfaceY(eye, block)) {
            // Not in a liquid cell, or the eye is above the actual liquid surface inside the cell
            // (a liquid block only fills part of its cell : full = cell top, source ~0.8, lower levels
            // less — so the cell top is above the surface and must NOT trigger the effect).
            detach();
            currentLiquidType = null;
            return;
        }

        String type = TypeIds.LAVA.equals(block.getType()) ? TypeIds.LAVA : TypeIds.WATER;
        if (!type.equals(currentLiquidType)) {
            currentLiquidType = type;
            configureFilterFor(type);
            lastColorUpdate = 0; // force an immediate fog-colour refresh on liquid change
        }

        // Fade the fog in with depth below the surface : right under the water line the effect is
        // near-zero (you still see out of the water clearly), ramping to full over fadeDepth. Without
        // this the whole view snaps to blue the instant the eye dips below the surface.
        // The depth is measured against the ACTUAL liquid surface (top of the contiguous liquid column
        // above the eye), not surfaceY (top of the eye's own cell) — using the latter makes the depth
        // reset to ~0 at every cell boundary while descending, which flickers the intensity.
        float fadeDepth = config.getUnderwaterFogFadeDepth();
        float intensity = fadeDepth <= 0f
                ? 1f
                : Math.min(1f, (columnSurfaceY(eye, block, fadeDepth) - eye.y) / fadeDepth);

        attach();
        filter.setIntensity(intensity);
        updateFogColor();
    }

    /**
     * World-space Y of the liquid surface inside the cell the given world position falls in. A liquid
     * block at grid {@code gy} spans world {@code [gy*scale, (gy+1)*scale]} and its top sits at
     * {@code (gy + 0.5 + topOffset(level)) * scale} (full = cell top, source ~0.8, lower levels less).
     */
    private float liquidSurfaceY(Vector3f worldPos, Block block) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        Vec3i cell = ChunkManager.getBlockLocation(worldPos);
        return (cell.y + 0.5f + Liquid.topOffset(block.getLiquidLevel())) * scale;
    }

    /**
     * World-space Y of the actual liquid surface above the eye : the top of the contiguous liquid
     * column starting at the eye's cell and going up. Unlike {@link #liquidSurfaceY} (which only
     * looks at the eye's own cell), this stays stable as the eye descends through successive liquid
     * cells, so the depth-based fog fade doesn't flicker at cell boundaries.
     *
     * The walk is capped : the fog saturates at {@code fadeDepth}, so there is never a need to look
     * higher than a couple of cells above that.
     */
    private float columnSurfaceY(Vector3f eye, Block eyeBlock, float fadeDepth) {
        float scale = BlocksConfig.getInstance().getBlockScale();
        ChunkManager cm = config.getChunkManager();
        Vec3i cell = ChunkManager.getBlockLocation(eye);
        int maxSteps = (int) Math.ceil(fadeDepth / scale) + 1;
        Block top = eyeBlock;
        int topY = cell.y;
        for (int i = 1; i <= maxSteps; i++) {
            probe.set((cell.x + 0.5f) * scale, (cell.y + i + 0.5f) * scale, (cell.z + 0.5f) * scale);
            Block b = cm.getBlock(probe).orElse(null);
            if (b == null || b.getLiquidLevel() <= 0) {
                break;
            }
            top = b;
            topY = cell.y + i;
        }
        return (topY + 0.5f + Liquid.topOffset(top.getLiquidLevel())) * scale;
    }

    /** Sets the per-liquid fog distance/density (lava is denser / shorter-range than water). */
    private void configureFilterFor(String type) {
        if (TypeIds.LAVA.equals(type)) {
            filter.setFogDistance(config.getLavaFogDistance());
            filter.setFogDensity(config.getLavaFogDensity());
        } else {
            filter.setFogDistance(config.getUnderwaterFogDistance());
            filter.setFogDensity(config.getUnderwaterFogDensity());
        }
        filter.setDistortionAmplitude(config.getUnderwaterDistortionAmplitude());
        filter.setDistortionSpeed(config.getUnderwaterDistortionSpeed());
        filter.setDistortionFrequency(config.getUnderwaterDistortionFrequency());
    }

    /**
     * Refreshes the fog colour. Water is brightness-modulated by the sky day/night level (white at
     * noon, dark at night) ; lava is emissive, so its orange stays constant. Throttled to the slow sky
     * colour cadence.
     */
    private void updateFogColor() {
        long now = System.currentTimeMillis();
        if (now - lastColorUpdate < COLOR_UPDATE_THRESHOLD_MS) {
            return;
        }
        lastColorUpdate = now;

        if (TypeIds.LAVA.equals(currentLiquidType)) {
            fogColor.set(config.getLavaFogColor());
            filter.setFogColor(fogColor);
            return;
        }

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

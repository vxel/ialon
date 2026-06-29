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
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Sphere;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.SpatialFollowCamControl;
import org.delaunois.ialon.control.StarControl;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a procedural star field that fades in at night. A coarse dome surrounds the camera and the
 * stars themselves are computed per fragment from the view direction (Shaders/Star.frag), so there is
 * no texture and the field stays anchored to the world. {@link StarControl} drives its visibility from
 * the sun height. Depends on {@link SunState} for that height ; attached after {@link SkyState} so the
 * stars draw over the (dark) sky but under the sun and moon.
 */
@Slf4j
public class StarState extends BaseAppState {

    private SimpleApplication app;
    @Getter
    private StarControl starControl;
    private Geometry stars;
    private final IalonConfig config;

    public StarState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        this.app = (SimpleApplication) app;

        // Coarse dome : the star field is per-fragment from the view direction, so the mesh only has to
        // surround the camera ; tessellation does not affect the result.
        Sphere dome = new Sphere(16, 16, 20f);
        stars = new Geometry("stars", dome);
        stars.setQueueBucket(RenderQueue.Bucket.Sky);
        stars.setCullHint(Spatial.CullHint.Never);
        stars.setShadowMode(RenderQueue.ShadowMode.Off);

        Material starMat = new Material(app.getAssetManager(), "Shaders/Star.j3md");
        // Pure additive (One,One) over the night sky : the shader carries the star brightness in its RGB
        // (so the sRGB encode lifts it identically on desktop and Android) and stars vanish as Intensity -> 0.
        starMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Additive);
        // The camera sits inside the dome : keep the inner (back) faces, cull the outward-facing ones.
        starMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Front);
        starMat.setFloat("Intensity", 0f);
        starMat.setVector4("SkyRotation", new com.jme3.math.Vector4f(0f, 0f, 0f, 1f)); // identity; live by StarControl
        starMat.setBoolean("ManualSrgb", config.isManualGammaEncode());
        stars.setMaterial(starMat);

        starControl = new StarControl(config);
        stars.addControl(starControl);
        stars.addControl(new SpatialFollowCamControl(app.getCamera()));
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        SunState sunState = app.getStateManager().getState(SunState.class);
        if (sunState == null) {
            log.error("StarState requires SunState");
            return;
        }
        starControl.setSunControl(sunState.getSunControl());
        if (stars.getParent() == null) {
            // Just behind the sun and moon but in front of the sky (forced to child 0 by SkyState).
            this.app.getRootNode().attachChildAt(stars, 1);
        }
    }

    @Override
    protected void onDisable() {
        if (stars.getParent() != null) {
            this.app.getRootNode().detachChild(stars);
        }
    }
}

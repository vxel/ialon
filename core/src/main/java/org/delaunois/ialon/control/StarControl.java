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
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Fades the procedural star dome in and out with the day-night cycle and slowly wheels it about the
 * celestial pole. Mirrors the throttled-update pattern of {@link SunControl} / {@link SkyControl} : the
 * star {@code Intensity} uniform follows the sun height, full at deep night and zero once the sun is up.
 * When fully faded the dome is culled so it costs nothing during the day. The dome is also rotated once
 * per day about a fixed, tilted pole axis (Polaris), in step with {@code config.time}, so the stars rise
 * and set and trace circular trails like the real night sky.
 */
@Slf4j
public class StarControl extends AbstractControl {

    // Tilted celestial pole : the fixed point the sky wheels around (Polaris sits here). Aligned with the
    // sun's apparent rotation axis (its orbit spans (1,0,0) and (0,1,1), whose normal is ~(0,-1,1)) so the
    // stars turn in step with the sun. The visible pole — the axis end above the horizon — is thus ~45°
    // up towards -z. Rotating by +config.time about this axis matches the sun's direction of travel.
    private static final Vector3f POLE_AXIS = new Vector3f(0f, -1f, 1f).normalizeLocal();

    @Setter
    private SunControl sunControl;

    private final Quaternion skyRotation = new Quaternion();
    private final Vector4f skyRotationVec = new Vector4f();
    private long lastUpdate = 0;
    private final IalonConfig config;

    public StarControl(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void controlUpdate(float tpf) {
        // Wheel the whole celestial sphere about the pole, every frame so the motion stays smooth even at
        // a high time factor. The pattern is computed per fragment from the view direction, so a sphere
        // mesh centred on the camera can't carry it (any rotation maps the sphere onto itself) : the
        // rotation is instead handed to the shader as a quaternion that turns the sampling direction.
        // config.time wraps at 2*PI, where the rotation is identity again : no jump.
        skyRotation.fromAngleAxis(config.getTime(), POLE_AXIS);
        skyRotationVec.set(skyRotation.getX(), skyRotation.getY(), skyRotation.getZ(), skyRotation.getW());
        ((Geometry) spatial).getMaterial().setVector4("SkyRotation", skyRotationVec);

        long now = System.currentTimeMillis();
        if (sunControl != null && (lastUpdate == 0 || now - lastUpdate > getUpdateThreshold())) {
            lastUpdate = now;
            updateStars();
        }
    }

    /**
     * Recomputes the star intensity immediately, bypassing the update throttle. Call this after the time
     * of day is changed abruptly (e.g. switching worlds) so the stars match the new world's time on the
     * next frame instead of lingering until the (possibly long) throttle elapses.
     */
    public void forceUpdate() {
        if (sunControl != null && spatial != null) {
            updateStars();
            lastUpdate = System.currentTimeMillis();
        }
    }

    private void updateStars() {
        // Stars fade in as the sun sinks below the horizon (sunHeight < 0) and reach full brightness in
        // deep night, the same curve the sky and lighting use for the day-night transition.
        float intensity = FastMath.clamp(-sunControl.getSunHeight() * 4f, 0f, 1f);
        ((Geometry) spatial).getMaterial().setFloat("Intensity", intensity);

        // Skip the (full-screen) dome entirely while it is invisible during the day.
        spatial.setCullHint(intensity <= 0f ? Spatial.CullHint.Always : Spatial.CullHint.Never);
    }

    private float getUpdateThreshold() {
        if (config.getTimeFactor() > 0) {
            return 1 / (config.getTimeFactor() * 2);
        } else {
            return Float.MAX_VALUE;
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }
}

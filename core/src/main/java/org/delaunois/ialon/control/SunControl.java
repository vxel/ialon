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

import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import java.time.LocalTime;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SunControl extends AbstractControl {

    @Getter
    private final Vector3f position = new Vector3f();

    @Getter
    @Setter
    private DirectionalLight directionalLight;

    @Getter
    @Setter
    private AmbientLight ambientLight;

    @Getter
    private float sunHeight;

    private final Camera cam;
    private final ColorRGBA sunColor = new ColorRGBA(1f, 1f, 1f, 1f);
    private long lastUpdate = 0;

    private final IalonConfig config;

    public SunControl(Camera cam, IalonConfig config) {
        this.config = config;
        this.cam = cam;
        updateSunPosition();
    }

    @Override
    protected void controlUpdate(float tpf) {
        long now = System.currentTimeMillis();
        if (lastUpdate == 0 || now - lastUpdate > getUpdateThreshold()) {
            updateSunPosition();
            updateSunLight();
            lastUpdate = now;
        }

        spatial.setLocalTranslation((cam.getLocation().add(position)));
        spatial.lookAt(cam.getLocation(), Vector3f.UNIT_Y);

        config.setTime((config.getTime() + tpf * config.getTimeFactor()) % FastMath.TWO_PI);
        if (log.isTraceEnabled()) {
            log.trace("Time is now {} ({})", getLocalTime(), FastMath.sin(config.getTime()));
        }
    }
    private void updateSunPosition() {
        sunHeight = FastMath.sin(config.getTime());
        float x = FastMath.cos(config.getTime()) * 100f;
        float z = FastMath.sin(config.getTime()) * 100f;
        float y = sunHeight * config.getSunAmplitude() * 10f;
        position.set(x, y, z);
    }

    private void updateSunLight() {
        if (directionalLight != null) {
            directionalLight.setDirection(position.negate());
        }

        float shift = FastMath.clamp(FastMath.pow(sunHeight * 2, 4), 0, 1);

        if (sunHeight > 0) {
            sunColor.interpolateLocal(config.getEveningColor(), config.getDayColor(), shift);
        } else {
            sunColor.interpolateLocal(config.getEveningColor(), config.getNightColor(), shift);
        }

        if (directionalLight != null) {
            directionalLight.getColor().set(sunColor.mult(config.getSunIntensity()));
        }

        if (ambientLight != null) {
            ambientLight.getColor().set(sunColor.mult(config.getAmbiantIntensity()));
        }

        ((Geometry)spatial).getMaterial().setColor("Color", sunColor);
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

    public void toggleTimeRun() {
        this.setEnabled(!this.isEnabled());
    }

    public LocalTime getLocalTime() {
        return LocalTime.ofSecondOfDay((long)((config.getTime() / FastMath.TWO_PI) * 86400)).plusHours(6);
    }

    public float getUpdateThreshold() {
        if (config.getTimeFactor() > 0) {
            return  Math.min(1000, 1 / (config.getTimeFactor() * 2));
        } else {
            return 1000;
        }
    }

}

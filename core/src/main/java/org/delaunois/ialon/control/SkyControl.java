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

import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SkyControl extends AbstractControl {

    private SunControl sun;

    private float updateThreshold = Float.MAX_VALUE;

    @Getter
    private final ColorRGBA color = new ColorRGBA();

    @Getter
    private final ColorRGBA groundColor = new ColorRGBA();

    private long lastUpdate = 0;

    private final IalonConfig config = IalonConfig.getInstance();

    public void setSunControl(SunControl sun) {
        this.sun = sun;
        if (sun.getTimeFactor() > 0) {
            updateThreshold = 1 / (sun.getTimeFactor() * 2);
        } else {
            updateThreshold = Float.MAX_VALUE;
        }
    }

    @Override
    protected void controlUpdate(float tpf) {
        long now = System.currentTimeMillis();
        if (this.sun != null && (lastUpdate == 0 || now - lastUpdate > updateThreshold)) {
            lastUpdate = now;

            float sunHeight = sun.getSunHeight();
            float shift = FastMath.clamp(FastMath.pow(sunHeight * 2, 4), 0, 1);

            if (sunHeight > 0) {
                color.interpolateLocal(config.getSkyEveningColor(), config.getSkyDayColor(), shift);
                groundColor.interpolateLocal(config.getGroundEveningColor(), config.getGroundDayColor(), shift);
            } else {
                color.interpolateLocal(config.getSkyEveningColor(), config.getSkyNightColor(), shift);
                groundColor.interpolateLocal(config.getGroundEveningColor(), config.getGroundNightColor(), shift);
            }
            ((Geometry) spatial).getMaterial().setColor("Color", color);
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }
}

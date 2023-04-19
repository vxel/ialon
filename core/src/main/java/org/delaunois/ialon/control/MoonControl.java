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

import com.jme3.math.FastMath;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import org.delaunois.ialon.IalonConfig;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MoonControl extends AbstractControl {

    @Getter
    @Setter
    private Vector3f position = new Vector3f();

    @Setter
    private SunControl sun;

    @Setter
    private Camera cam;

    private long lastUpdate = 0;
    private final IalonConfig config = IalonConfig.getInstance();

    @Override
    protected void controlUpdate(float tpf) {
        if (!sun.isRun()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (lastUpdate == 0 || now - lastUpdate > sun.getUpdateThreshold()) {
            lastUpdate = now;
            float time = config.getTime() + FastMath.PI;
            float height = FastMath.sin(time);
            float x = FastMath.cos(time) * 100f;
            float z = FastMath.sin(time) * 100f;
            float y = height * IalonConfig.getInstance().getSunAmplitude() * 10f;
            position.set(x, y, z);
        }

        spatial.setLocalTranslation((cam.getLocation().add(position)));
        spatial.lookAt(cam.getLocation(), Vector3f.UNIT_Y);
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }

}

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

import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.control.AbstractControl;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Makes a spatial follow the translation of the camera, with an optional local translation.
 */
@Slf4j
public class SpatialFollowCamControl extends AbstractControl {

    private final Camera cam;
    private final Vector3f tmp = new Vector3f();

    @Setter
    private Vector3f translation;

    public SpatialFollowCamControl(Camera cam) {
        this.cam = cam;
    }

    @Override
    protected void controlUpdate(float tpf) {
        if (translation != null) {
            tmp.set(cam.getLocation()).addLocal(translation);
            spatial.setLocalTranslation(tmp);
        } else {
            spatial.setLocalTranslation(cam.getLocation());
        }
    }

    @Override
    protected void controlRender(RenderManager rm, ViewPort vp) {
        // Nothing to do
    }
}

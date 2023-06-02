/*
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

package org.delaunois.ialon;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

public class CameraHelper {

    private final Matrix3f mat = new Matrix3f();
    private final Quaternion q = new Quaternion();
    private final Vector3f up = new Vector3f();
    private final Vector3f left = new Vector3f();
    private final Vector3f dir = new Vector3f();

    public void rotate(Camera cam, float value, Vector3f axis, Vector3f camUp, Vector3f camLeft, Vector3f camDir) {
        mat.fromAngleNormalAxis(value, axis);

        mat.mult(camUp, up);
        mat.mult(camLeft, left);
        mat.mult(camDir, dir);

        if (up.getY() < 0) {
            return;
        }

        q.fromAxes(left, up, dir);
        q.normalizeLocal();

        cam.setAxes(q);
    }

}

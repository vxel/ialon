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

    public void rotate(Camera cam, float value) {
        rotate(cam, value, Vector3f.UNIT_Y);
    }

    public void rotate(Camera cam, float value, Vector3f axis) {
        mat.fromAngleNormalAxis(value, axis);

        Vector3f up = cam.getUp();
        Vector3f left = cam.getLeft();
        Vector3f dir = cam.getDirection();

        mat.mult(up, up);
        mat.mult(left, left);
        mat.mult(dir, dir);

        if (up.getY() < 0) {
            return;
        }

        q.fromAxes(left, up, dir);
        q.normalizeLocal();

        cam.setAxes(q);
    }

}

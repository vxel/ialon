package org.delaunois.ialon;

import com.jme3.math.Matrix3f;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

import static org.delaunois.ialon.Config.ROTATION_SPEED;

public class CameraHelper {

    private static final Vector3f UP = new Vector3f(0, 1, 0);

    public static void rotate(Camera cam, float value) {
        rotate(cam, value, UP);
    }

    public static void rotate(Camera cam, float value, Vector3f axis) {
        Matrix3f mat = new Matrix3f();
        mat.fromAngleNormalAxis(ROTATION_SPEED * value, axis);

        Vector3f up = cam.getUp();
        Vector3f left = cam.getLeft();
        Vector3f dir = cam.getDirection();

        mat.mult(up, up);
        mat.mult(left, left);
        mat.mult(dir, dir);

        if (up.getY() < 0) {
            return;
        }

        Quaternion q = new Quaternion();
        q.fromAxes(left, up, dir);
        q.normalizeLocal();

        cam.setAxes(q);
    }

}

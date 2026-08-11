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

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;

import org.junit.jupiter.api.Test;

/**
 * Guards the creation viewer's one hard promise about its framing : the model stays whole on screen
 * <b>however the player turns it</b>.
 *
 * <p>That is not the same as fitting the box once. A distance fitted to the box as it is first shown crops it
 * as soon as it turns — a long creation seen end-on needs far less room than the same creation seen along its
 * diagonal — which is why {@link CreationViewerState#framedCamera} fits the box's circumscribed sphere. This
 * test is that reasoning made checkable : the eight corners are turned to hundreds of orientations and every
 * one of them must still project inside the panel.
 *
 * <p>Headless : a jME {@link Camera} is pure maths, no GL context needed.
 *
 * @author Cedric de Launois
 */
class CreationViewerFramingTest {

    /** Creation shapes that frame differently : a cube, a flat slab, a tower, a bar, a big block, a block. */
    private static final float[][] BOXES = {
            {8, 8, 8}, {32, 1, 32}, {1, 48, 1}, {64, 2, 3}, {64, 64, 64}, {1, 1, 1},
    };

    /** Render targets the panel can ask for : square, landscape, portrait, and the extremes of the clamp. */
    private static final int[][] TARGETS = {
            {512, 512}, {1024, 460}, {460, 1024}, {256, 256}, {1024, 1024}, {1024, 256},
    };

    @Test
    void theWholeCreationStaysOnScreenAtEveryOrientation() {
        for (float[] box : BOXES) {
            Vector3f boxSize = new Vector3f(box[0], box[1], box[2]);
            for (int[] target : TARGETS) {
                Camera cam = CreationViewerState.framedCamera(boxSize, target[0], target[1]);
                for (Quaternion rotation : orientations(200)) {
                    for (Vector3f corner : corners(boxSize)) {
                        assertOnScreen(cam, rotation.mult(corner), boxSize, target);
                    }
                }
            }
        }
    }

    @Test
    void theModelIsBigEnoughToBeWorthLookingAt() {
        // The other half of the promise : framed, not lost in the middle. At its widest the model must span
        // most of the panel, or the fit is padding the view rather than filling it.
        for (float[] box : BOXES) {
            Vector3f boxSize = new Vector3f(box[0], box[1], box[2]);
            Camera cam = CreationViewerState.framedCamera(boxSize, 512, 512);
            float radius = boxSize.length() * 0.5f;
            // A point at the bounding sphere's edge, level with the camera : where the sphere touches the
            // frustum. Projected, it must sit well outside the middle of the panel.
            Vector3f edge = new Vector3f(radius, 0, 0);
            Vector3f screen = cam.getScreenCoordinates(edge);
            float spanFraction = Math.abs(screen.x - 256f) / 256f;
            assertTrue(spanFraction > 0.6f,
                    "the model only spans " + spanFraction + " of the half-panel for box " + boxSize);
        }
    }

    private static void assertOnScreen(Camera cam, Vector3f world, Vector3f boxSize, int[] target) {
        Vector3f screen = cam.getScreenCoordinates(world);
        String where = "corner " + world + " of box " + boxSize
                + " at " + target[0] + "x" + target[1] + " projects to " + screen;
        assertTrue(screen.z > 0 && screen.z < 1, "in front of the camera, within the depth range : " + where);
        assertTrue(screen.x >= 0 && screen.x <= target[0], "inside the panel horizontally : " + where);
        assertTrue(screen.y >= 0 && screen.y <= target[1], "inside the panel vertically : " + where);
    }

    /** The eight corners of the box, about the centre it turns about. */
    private static Vector3f[] corners(Vector3f boxSize) {
        Vector3f half = boxSize.mult(0.5f);
        Vector3f[] corners = new Vector3f[8];
        int at = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    corners[at++] = new Vector3f(half.x * sx, half.y * sy, half.z * sz);
                }
            }
        }
        return corners;
    }

    /**
     * Orientations spread over the whole sphere, from a fixed sequence rather than a random one : a framing
     * test that fails on one machine and passes on the next is worthless.
     */
    private static Quaternion[] orientations(int count) {
        Quaternion[] rotations = new Quaternion[count];
        float golden = FastMath.PI * (3f - FastMath.sqrt(5f));
        for (int i = 0; i < count; i++) {
            float y = 1f - 2f * i / (float) (count - 1);
            float radius = FastMath.sqrt(Math.max(0f, 1f - y * y));
            float theta = golden * i;
            Vector3f axis = new Vector3f(FastMath.cos(theta) * radius, y, FastMath.sin(theta) * radius);
            rotations[i] = new Quaternion().fromAngleNormalAxis(theta, axis.normalizeLocal());
        }
        return rotations;
    }
}

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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.Camera;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;

import org.junit.jupiter.api.Test;

/**
 * Guards the framing of the card image the 3D viewer takes : everything the player can see must be in the
 * picture, and the picture must be <b>full</b> of it — that is the whole point of taking it from the angle
 * they chose ({@code CreationPreview.tightCamera}).
 *
 * <p>Headless : a jME {@code Camera} and a {@code Mesh} are pure maths, so the fit can be checked by
 * projecting the very vertices it was fitted on. Which is also how the two mistakes this guards against show
 * up — and both were made :</p>
 * <ul>
 *   <li>a fresh {@code Camera} is <b>orthographic</b> ({@code parallelProjection} starts {@code true}, and
 *       {@code setFrustumPerspective} is the only method that clears it), so a camera set up through
 *       {@code setFrustum} alone reads its planes as world units instead of angles and renders the creation
 *       at an unrelated scale ;</li>
 *   <li>a frustum centred on the axis can only bound the largest offset from it, so a silhouette sitting off
 *       centre fills one side of the card and leaves black on the other.</li>
 * </ul>
 *
 * @author Cedric de Launois
 */
class CreationPreviewFramingTest {

    /** The card is wide and short ; the fit has to hold at other shapes too. */
    private static final int[][] TARGETS = {{256, 94}, {256, 256}, {128, 256}};

    /** How full the picture must be along the axis the fit binds on : the margin is 4%. */
    private static final float TIGHT = 0.90f;

    @Test
    void theCreationIsWhollyInThePictureAndFillsIt() {
        for (int[] target : TARGETS) {
            for (float[] box : new float[][]{{8, 8, 8}, {32, 4, 24}, {3, 20, 3}, {40, 12, 2}}) {
                for (Quaternion rotation : orientations(24)) {
                    assertFramed(new Vector3f(box[0], box[1], box[2]), rotation, target[0], target[1]);
                }
            }
        }
    }

    /** An off-centre creation is framed on what it is, not on where its box happens to be. */
    @Test
    void anOffCentreCreationIsStillFilledTo() {
        // A model hugging one corner of the box it was captured in : the box centre is nowhere near it, so a
        // frustum kept on the axis would spend most of the card on the empty side.
        Node content = new Node("offCentre");
        content.attachChild(quadAt(new Vector3f(12f, 9f, 0f), 4f));
        content.setLocalRotation(new Quaternion().fromAngles(0.3f, 0.7f, 0f));
        content.updateGeometricState();

        Camera cam = CreationPreview.tightCamera(new Vector3f(32, 24, 8), content, 256, 94);
        assertFalse(cam.isParallelProjection(), "the viewer's thumbnail camera is perspective");
        float[] span = projectedSpan(cam, content);
        assertTrue(span[1] >= TIGHT || span[0] >= TIGHT,
                "an off-centre model still fills the card : spans " + span[0] + " x " + span[1]);
    }

    private static void assertFramed(Vector3f size, Quaternion rotation, int width, int height) {
        Node content = new Node("content");
        // A cross of quads spanning the box : enough vertices, in enough places, to stand for a creation.
        content.attachChild(quadAt(new Vector3f(0, 0, 0), Math.min(size.x, size.y)));
        content.attachChild(quadAt(new Vector3f(size.x * 0.4f, size.y * 0.4f, size.z * 0.4f), 1f));
        content.attachChild(quadAt(new Vector3f(-size.x * 0.4f, -size.y * 0.4f, -size.z * 0.4f), 1f));
        content.setLocalRotation(rotation);
        content.updateGeometricState();

        Camera cam = CreationPreview.tightCamera(size, content, width, height);
        String what = size + " at " + width + "x" + height;
        assertFalse(cam.isParallelProjection(), "perspective, not orthographic : " + what);

        float[] span = projectedSpan(cam, content);
        assertTrue(span[0] <= 1f && span[1] <= 1f, "nothing is cropped : spans " + span[0] + " x " + span[1]
                + " of " + what);
        assertTrue(span[0] >= TIGHT || span[1] >= TIGHT, "one axis is filled : spans " + span[0] + " x "
                + span[1] + " of " + what);
    }

    /** The fraction of the picture the content spans, horizontally then vertically (1 = the whole picture). */
    private static float[] projectedSpan(Camera cam, Node content) {
        float minX = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (Vector3f vertex : worldVertices(content)) {
            Vector4f clip = cam.getViewProjectionMatrix()
                    .mult(new Vector4f(vertex.x, vertex.y, vertex.z, 1f));
            float x = clip.x / clip.w;
            float y = clip.y / clip.w;
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
        }
        // NDC spans [-1, 1], so a full picture is a span of 2.
        return new float[]{(maxX - minX) / 2f, (maxY - minY) / 2f};
    }

    private static java.util.List<Vector3f> worldVertices(Node content) {
        java.util.List<Vector3f> vertices = new java.util.ArrayList<>();
        for (com.jme3.scene.Spatial child : content.getChildren()) {
            Geometry geometry = (Geometry) child;
            java.nio.FloatBuffer buffer =
                    (java.nio.FloatBuffer) geometry.getMesh().getBuffer(VertexBuffer.Type.Position).getData();
            for (int i = 0; i < buffer.limit() / 3; i++) {
                Vector3f vertex = new Vector3f(buffer.get(i * 3), buffer.get(i * 3 + 1), buffer.get(i * 3 + 2));
                vertices.add(geometry.getWorldTransform().transformVector(vertex, vertex));
            }
        }
        return vertices;
    }

    /** A square of the given side, centred on {@code centre}, facing +Z : four vertices, no GL. */
    private static Geometry quadAt(Vector3f centre, float side) {
        float half = side * 0.5f;
        Mesh mesh = new Mesh();
        mesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(
                centre.x - half, centre.y - half, centre.z,
                centre.x + half, centre.y - half, centre.z,
                centre.x + half, centre.y + half, centre.z,
                centre.x - half, centre.y + half, centre.z));
        mesh.setBuffer(VertexBuffer.Type.Index, 3, new short[]{0, 1, 2, 0, 2, 3});
        mesh.updateBound();
        return new Geometry("quad", mesh);
    }

    /** Orientations spread over the sphere, from a fixed sequence : a flaky framing test is worthless. */
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

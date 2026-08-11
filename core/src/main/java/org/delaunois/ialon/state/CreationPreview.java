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

import com.jme3.app.SimpleApplication;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Transform;
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.system.JmeSystem;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;

import org.delaunois.ialon.IalonInitializer;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

/**
 * Renders an isolated thumbnail of a creation : the meshed blocks alone, offscreen, from a fixed
 * 3/4 viewpoint, written to a PNG (the creation card preview). The camera aims at the bounding box
 * centre from a distance of twice the centre-to-(upper corner) distance, along the centre→corner ray, so
 * the whole box is framed from above one corner.
 *
 * <p>Implemented as a one-shot {@link SceneProcessor} on a dedicated pre-view viewport with its own
 * framebuffer : in {@code postFrame} it reads the framebuffer, writes the PNG, then removes the viewport.
 * The block material is lit, so the offscreen scene carries its own ambient + directional light.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class CreationPreview extends AbstractPreviewProcessor {

    /** Width, in pixels, of the rendered thumbnail ; the height follows from the card's aspect ratio. */
    private static final int THUMBNAIL_WIDTH = 256;

    /** Field of view of the fixed three-quarter thumbnail, and the range the tight one is framed from. */
    private static final float DEFAULT_FOV = 45f;

    /** Slack left around the creation in a tightly framed thumbnail : enough not to clip a corner. */
    private static final float TIGHT_MARGIN = 1.04f;

    private final SimpleApplication app;
    private final Path png;
    private final ViewPort viewPort;
    private final int width;
    private final int height;
    private final Runnable onDone;

    private Renderer renderer;
    private ByteBuffer outBuf;
    private boolean done;

    private CreationPreview(SimpleApplication app, Path png, ViewPort viewPort, int width, int height,
                            Runnable onDone) {
        this.app = app;
        this.png = png;
        this.viewPort = viewPort;
        this.width = width;
        this.height = height;
        this.onDone = onDone;
    }

    /**
     * Renders {@code creationNode} (whose origin is the box lower corner) to {@code png}. {@code boxSize}
     * is the box extent in world units ({@code size * blockScale} per axis). The node is consumed (it is
     * attached to the throwaway preview scene).
     */
    public static void render(SimpleApplication app, Node creationNode, Vector3f boxSize, Path png) {
        render(app, creationNode, boxSize, png, null);
    }

    /**
     * Same as {@link #render(SimpleApplication, Node, Vector3f, Path)}, then runs {@code onDone} on the
     * render thread once the PNG has been written (always invoked, even if the render fails), so a caller
     * showing that thumbnail can safely refresh its UI from there.
     */
    public static void render(SimpleApplication app, Node creationNode, Vector3f boxSize, Path png,
                             Runnable onDone) {
        Vector3f center = boxSize.mult(0.5f);
        // An upper corner of the box (max X/Y/Z) ; the camera sits along the centre→corner ray at twice
        // the centre-to-corner distance, i.e. at  center + 2*(corner - center) = 2*corner - center.
        Vector3f corner = boxSize.clone();
        Vector3f position = corner.mult(2f).subtractLocal(center);

        int width = THUMBNAIL_WIDTH;
        int height = thumbnailHeight(app);
        float aspect = width / (float) height;

        Camera cam = new Camera(width, height);
        cam.setFrustumPerspective(DEFAULT_FOV, aspect, 0.05f, 10000f);
        cam.setLocation(position);
        cam.lookAt(center, Vector3f.UNIT_Y);

        renderScene(app, creationNode, cam, width, height, png, onDone);
    }

    /**
     * Renders the creation <b>as the 3D viewer is showing it</b> : the same orientation, and framed as tightly
     * as the thumbnail's shape allows.
     *
     * <p>Tight is the whole point of this variant — the player picked the angle, so the card should spend its
     * pixels on the creation rather than on the black around it. The camera keeps the distance a bounding
     * sphere needs (so no perspective surprise) and <b>narrows its field of view</b> until the box's eight
     * projected corners just fit. That is exact in one pass, where fitting by moving the camera closer is
     * not : how much closer it may come depends on which corner comes forward, which depends on the
     * distance.</p>
     *
     * @param app          the application
     * @param creationNode the meshed creation, origin at the box lower corner ; consumed
     * @param centre       the point of the node to centre the picture on, in node coordinates
     * @param size         the extent to frame, in world units — the blocks' own, not the grid's
     * @param rotation     the orientation to render it at (the viewer's)
     * @param png          where to write the thumbnail
     * @param onDone       run on the render thread once the PNG is written, always
     */
    public static void render(SimpleApplication app, Node creationNode, Vector3f centre, Vector3f size,
                              Quaternion rotation, Path png, Runnable onDone) {
        int width = THUMBNAIL_WIDTH;
        int height = thumbnailHeight(app);

        // Hung off a pivot that brings the framed centre to the origin, which is where the camera aims and
        // what the rotation turns about.
        Node pivot = new Node("creationPreviewPivot");
        creationNode.setLocalTranslation(centre.negate());
        pivot.attachChild(creationNode);
        pivot.setLocalRotation(rotation);
        // Before measuring : the fit reads the vertices in WORLD space, which is where the rotation is.
        pivot.updateGeometricState();

        renderScene(app, pivot, tightCamera(size, pivot, width, height), width, height, png, onDone);
    }

    /**
     * The camera that frames the rotated box tightly, looking down -Z at the origin.
     *
     * <p>Package-private so the framing can be checked without a GL context — a {@code Camera} and a
     * {@code Mesh} are both pure maths (see {@code CreationPreviewFramingTest}).</p>
     *
     * <p>The distance is the one a bounding sphere would need at {@link #DEFAULT_FOV} : any orientation is
     * safely in front of the camera at that range, whatever the box's shape. The half-angles are then reduced
     * to the largest the corners actually need, {@link #TIGHT_MARGIN} aside, which is what squeezes the black
     * border out. A narrower field of view also flattens the perspective, which suits a thumbnail.</p>
     */
    static Camera tightCamera(Vector3f size, Spatial rotatedContent, int width, int height) {
        float aspect = width / (float) height;
        float radius = size.length() * 0.5f;
        float distance = radius / FastMath.sin(DEFAULT_FOV * FastMath.DEG_TO_RAD * 0.5f);
        float near = Math.max(0.05f, distance - radius * 1.5f);
        float far = distance + radius * 3f;

        Camera cam = new Camera(width, height);
        // A fresh jME Camera is ORTHOGRAPHIC : parallelProjection starts true, and setFrustumPerspective is
        // the only method that clears it — which the off-axis frustum below does not go through. Without this
        // line the planes are read as world units instead of angles, and the creation comes out at some
        // unrelated scale, flat.
        cam.setParallelProjection(false);
        cam.setLocation(new Vector3f(0, 0, distance));
        cam.lookAt(Vector3f.ZERO, Vector3f.UNIT_Y);

        // Fitted on the MESH's own vertices, not on the corners of the box around them. A creation is rarely
        // box-shaped : an L-shaped house leaves the box's corners empty, and fitting those spends the card on
        // air the model never reaches. The vertices are its silhouette, exactly.
        float[] window = {Float.MAX_VALUE, -Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE};
        accumulateWindow(rotatedContent, distance, window);
        if (window[1] < window[0]) {
            // Nothing measurable (no vertex buffer to read) : frame the whole box, centred.
            cam.setFrustumPerspective(DEFAULT_FOV, aspect, near, far);
            return cam;
        }

        // An OFF-CENTRE frustum, not a field of view : it maps the measured window onto the picture exactly.
        // A symmetric one can only bound the largest offset from the axis, so a silhouette sitting off centre
        // — most of them, a tree carrying its mass at the top — would fill one side and leave black on the
        // other. Only the narrower axis is grown, and about its own centre, to reach the card's shape.
        float centreX = (window[0] + window[1]) * 0.5f;
        float centreY = (window[2] + window[3]) * 0.5f;
        float halfX = (window[1] - window[0]) * 0.5f * TIGHT_MARGIN;
        float halfY = (window[3] - window[2]) * 0.5f * TIGHT_MARGIN;
        if (halfX / halfY > aspect) {
            halfY = halfX / aspect;
        } else {
            halfX = halfY * aspect;
        }
        cam.setFrustum(near, far,
                (centreX - halfX) * near, (centreX + halfX) * near,
                (centreY + halfY) * near, (centreY - halfY) * near);
        return cam;
    }

    /**
     * Walks the geometry and records the angular window its vertices occupy, seen from {@code (0, 0, distance)}
     * looking down -Z : {@code {minX, maxX, minY, maxY}} as tangents.
     *
     * <p>Each vertex is divided by <b>its own</b> depth : a corner nearer the camera subtends more than a far
     * one, and using the centre's depth for all of them would crop the near ones. Positions are read in world
     * space (through each geometry's world transform), which is where the creation's orientation lives. A few
     * tens of thousands of vertices, once, on a button press.</p>
     *
     * <p>Hidden vertices count too — the window is a safe superset of what is drawn, never a crop.</p>
     */
    private static void accumulateWindow(Spatial spatial, float distance, float[] window) {
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                accumulateWindow(child, distance, window);
            }
            return;
        }
        if (!(spatial instanceof Geometry)) {
            return;
        }
        Mesh mesh = ((Geometry) spatial).getMesh();
        VertexBuffer positions = mesh == null ? null : mesh.getBuffer(VertexBuffer.Type.Position);
        if (positions == null || !(positions.getData() instanceof FloatBuffer)) {
            return;
        }
        FloatBuffer buffer = (FloatBuffer) positions.getData();
        Transform transform = spatial.getWorldTransform();
        Vector3f vertex = new Vector3f();
        int count = buffer.limit() / 3;
        for (int i = 0; i < count; i++) {
            vertex.set(buffer.get(i * 3), buffer.get(i * 3 + 1), buffer.get(i * 3 + 2));
            transform.transformVector(vertex, vertex);
            float depth = Math.max(0.001f, distance - vertex.z);
            float x = vertex.x / depth;
            float y = vertex.y / depth;
            window[0] = Math.min(window[0], x);
            window[1] = Math.max(window[1], x);
            window[2] = Math.min(window[2], y);
            window[3] = Math.max(window[3], y);
        }
    }

    /**
     * The thumbnail's height for the fixed {@link #THUMBNAIL_WIDTH} : the aspect ratio of the card's preview
     * panel (contentW 26vw x previewH 17vh in {@code CreationLibraryState.createCard}), or the render comes
     * out stretched on the card.
     */
    private static int thumbnailHeight(SimpleApplication app) {
        float aspect = (26f / 17f) * (app.getCamera().getWidth() / (float) app.getCamera().getHeight());
        return Math.max(1, Math.round(THUMBNAIL_WIDTH / aspect));
    }

    /** Lights the given node, renders one frame of it offscreen and writes it to {@code png}. */
    private static void renderScene(SimpleApplication app, Spatial content, Camera cam, int width, int height,
                                    Path png, Runnable onDone) {
        FrameBuffer fb = new FrameBuffer(width, height, 1);
        fb.setDepthBuffer(Image.Format.Depth);
        fb.setColorTexture(new Texture2D(width, height, Image.Format.RGBA8));

        Node scene = new Node("creationPreviewScene");
        scene.attachChild(content);
        scene.addLight(new AmbientLight(ColorRGBA.White.mult(0.55f)));
        scene.addLight(new DirectionalLight(
                new Vector3f(-0.6f, -1f, -0.8f).normalizeLocal(), ColorRGBA.White.mult(1.3f)));
        scene.updateGeometricState();

        ViewPort vp = app.getRenderManager().createPreView("creationPreview", cam);
        vp.setClearFlags(true, true, true);
        vp.setBackgroundColor(new ColorRGBA(0.08f, 0.09f, 0.12f, 1f));
        vp.setOutputFrameBuffer(fb);
        vp.attachScene(scene);
        // Water, glass and fire faces carry a layer only this comparator honours, and a private viewport is
        // not given the one the main viewport gets (IalonInitializer.setupViewPort).
        IalonInitializer.installLayerComparator(vp);
        vp.addProcessor(new CreationPreview(app, png, vp, width, height, onDone));
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        this.renderer = rm.getRenderer();
        this.outBuf = BufferUtils.createByteBuffer(width * height * 4);
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
        // Fixed-size offscreen target ; nothing to do.
    }

    @Override
    public boolean isInitialized() {
        return renderer != null;
    }

    @Override
    public void postFrame(FrameBuffer out) {
        if (done) {
            return;
        }
        done = true;
        try {
            Files.createDirectories(png.getParent());
            renderer.readFrameBuffer(out, outBuf);
            try (OutputStream os = Files.newOutputStream(png)) {
                JmeSystem.writeImageFile(os, "png", outBuf, width, height);
            }
            log.info("Saved creation preview ({}x{}) to {}", width, height, png.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to render creation preview {}: {}", png, e.getMessage());
        }
        // Tear the throwaway viewport down and run the continuation outside the render iteration.
        app.enqueue(() -> {
            app.getRenderManager().removePreView(viewPort);
            if (onDone != null) {
                onDone.run();
            }
        });
    }
}

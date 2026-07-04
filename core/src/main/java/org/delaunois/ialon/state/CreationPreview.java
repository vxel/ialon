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
import com.jme3.math.Vector3f;
import com.jme3.post.SceneProcessor;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.scene.Node;
import com.jme3.system.JmeSystem;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Image;
import com.jme3.texture.Texture2D;
import com.jme3.util.BufferUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
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

    private static final int WIDTH = 256;

    private final SimpleApplication app;
    private final Path png;
    private final ViewPort viewPort;
    private final int width;
    private final int height;

    private Renderer renderer;
    private ByteBuffer outBuf;
    private boolean done;

    private CreationPreview(SimpleApplication app, Path png, ViewPort viewPort, int width, int height) {
        this.app = app;
        this.png = png;
        this.viewPort = viewPort;
        this.width = width;
        this.height = height;
    }

    /**
     * Renders {@code creationNode} (whose origin is the box lower corner) to {@code png}. {@code boxSize}
     * is the box extent in world units ({@code size * blockScale} per axis). The node is consumed (it is
     * attached to the throwaway preview scene).
     */
    public static void render(SimpleApplication app, Node creationNode, Vector3f boxSize, Path png) {
        Vector3f center = boxSize.mult(0.5f);
        // An upper corner of the box (max X/Y/Z) ; the camera sits along the centre→corner ray at twice
        // the centre-to-corner distance, i.e. at  center + 2*(corner - center) = 2*corner - center.
        Vector3f corner = boxSize.clone();
        Vector3f position = corner.mult(2f).subtractLocal(center);

        // Render at the SAME aspect ratio as the card's preview panel (contentW 26vw x previewH 17vh in
        // CreationLibraryState.createCard), otherwise the square render is stretched when shown there.
        float aspect = (26f / 17f) * (app.getCamera().getWidth() / (float) app.getCamera().getHeight());
        int width = WIDTH;
        int height = Math.max(1, Math.round(WIDTH / aspect));

        Camera cam = new Camera(width, height);
        cam.setFrustumPerspective(45f, aspect, 0.05f, 10000f);
        cam.setLocation(position);
        cam.lookAt(center, Vector3f.UNIT_Y);

        FrameBuffer fb = new FrameBuffer(width, height, 1);
        fb.setDepthBuffer(Image.Format.Depth);
        fb.setColorTexture(new Texture2D(width, height, Image.Format.RGBA8));

        Node scene = new Node("creationPreviewScene");
        scene.attachChild(creationNode);
        scene.addLight(new AmbientLight(ColorRGBA.White.mult(0.55f)));
        scene.addLight(new DirectionalLight(
                new Vector3f(-0.6f, -1f, -0.8f).normalizeLocal(), ColorRGBA.White.mult(1.3f)));
        scene.updateGeometricState();

        ViewPort vp = app.getRenderManager().createPreView("creationPreview", cam);
        vp.setClearFlags(true, true, true);
        vp.setBackgroundColor(new ColorRGBA(0.08f, 0.09f, 0.12f, 1f));
        vp.setOutputFrameBuffer(fb);
        vp.attachScene(scene);
        vp.addProcessor(new CreationPreview(app, png, vp, width, height));
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
        // Tear the throwaway viewport down outside the render iteration.
        app.enqueue(() -> app.getRenderManager().removePreView(viewPort));
    }
}

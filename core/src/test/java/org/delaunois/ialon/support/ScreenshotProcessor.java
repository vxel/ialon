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

package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.system.JmeSystem;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One-shot {@link SceneProcessor} that, on its first {@code postFrame}, reads the full-resolution
 * framebuffer and writes it as a PNG, then removes itself and runs an {@code onDone} callback. Unlike
 * {@code state.WorldPreview} (which downscales to a 256px world-card thumbnail), this keeps the native
 * resolution — it's meant for inspecting a render, not for a UI card. Used by {@link ScreenshotHarness}.
 *
 * @author Cedric de Launois
 */
public class ScreenshotProcessor implements SceneProcessor {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotProcessor.class);

    private final SimpleApplication app;
    private final Path output;
    private final Runnable onDone;

    private ViewPort viewPort;
    private Renderer renderer;
    private ByteBuffer buf;
    private int width;
    private int height;
    private boolean done;

    public ScreenshotProcessor(SimpleApplication app, Path output, Runnable onDone) {
        this.app = app;
        this.output = output;
        this.onDone = onDone;
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        this.viewPort = vp;
        this.renderer = rm.getRenderer();
        reshape(vp, vp.getCamera().getWidth(), vp.getCamera().getHeight());
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
        this.width = w;
        this.height = h;
        this.buf = BufferUtils.createByteBuffer(w * h * 4);
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
            if (output.getParent() != null) {
                Files.createDirectories(output.getParent());
            }
            renderer.readFrameBuffer(out, buf);
            try (OutputStream os = Files.newOutputStream(output)) {
                JmeSystem.writeImageFile(os, "png", buf, width, height);
            }
            log.info("Saved screenshot ({}x{}) to {}", width, height, output.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to capture screenshot {}: {}", output, e.getMessage());
        }
        app.enqueue(() -> {
            viewPort.removeProcessor(this);
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    @Override
    public void preFrame(float tpf) {
        // Nothing to do
    }

    @Override
    public void postQueue(RenderQueue rq) {
        // Nothing to do
    }

    @Override
    public void cleanup() {
        // Nothing to do
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
        // Nothing to do
    }
}

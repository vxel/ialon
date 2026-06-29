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
import com.jme3.asset.AssetManager;
import com.jme3.asset.AssetNotFoundException;
import com.jme3.asset.TextureKey;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Renderer;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.system.JmeSystem;
import com.jme3.texture.FrameBuffer;
import com.jme3.texture.Texture;
import com.jme3.util.BufferUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;

import lombok.extern.slf4j.Slf4j;

/**
 * Captures and loads the per-world preview image (a screenshot shown on the world cards).
 *
 * <p>Capture is a one-shot {@link SceneProcessor} attached to the main 3D viewport : in
 * {@code postFrame} it reads the framebuffer and writes it as a PNG, then removes itself. Because the
 * 3D viewport renders before the GUI viewport, the screenshot contains only the world (no HUD, no
 * popup). The write path ({@link JmeSystem#writeImageFile}) is the same portable one used by
 * {@code TextureAtlasManager} and jME's {@code ScreenshotAppState}, so it works on desktop and
 * Android.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public class WorldPreview implements SceneProcessor {

    public static final String PREVIEW_FILENAME = "preview.png";
    // Previews are downscaled to this width before being written : a card never needs more, and it keeps
    // the PNG small and the loaded texture light (important on Android). Height keeps the aspect ratio.
    private static final int PREVIEW_MAX_WIDTH = 256;

    private final SimpleApplication app;
    private final Path pngFile;
    private final Runnable onDone;

    private ViewPort viewPort;
    private Renderer renderer;
    private ByteBuffer outBuf;
    private int width;
    private int height;
    private boolean done;

    private WorldPreview(SimpleApplication app, Path pngFile, Runnable onDone) {
        this.app = app;
        this.pngFile = pngFile;
        this.onDone = onDone;
    }

    /**
     * Captures the current 3D frame to {@code pngFile} (creating parent directories), then runs
     * {@code onDone} on the render thread. {@code onDone} is always invoked, even if the capture
     * fails, so callers can safely chain UI work after it.
     */
    public static void capture(SimpleApplication app, Path pngFile, Runnable onDone) {
        app.getViewPort().addProcessor(new WorldPreview(app, pngFile, onDone));
    }

    /**
     * Loads a world's preview as a texture, or returns {@code null} if none has been captured yet.
     * Requires the save root to be registered as a {@code FileLocator} on the asset manager (see
     * WorldMenuState). The cache entry is dropped first so a freshly recaptured preview is reloaded.
     */
    public static Texture load(AssetManager assetManager, String worldId) {
        // Let the asset manager cache the texture : the world menu rebuilds its grid on every card click,
        // and re-decoding/re-uploading the PNGs each time was the cause of the >1s lag. The cache is
        // invalidated only when a preview is actually recaptured (see dropFromCache).
        try {
            return assetManager.loadTexture(key(worldId));
        } catch (AssetNotFoundException e) {
            return null;
        }
    }

    public static void dropFromCache(AssetManager assetManager, String worldId) {
        assetManager.deleteFromCache(key(worldId));
    }

    /**
     * Loads any preview PNG by its asset-relative key (e.g. {@code "creations/foo.png"}), or {@code null}
     * if absent. Like {@link #load}, the save root must be registered as a {@code FileLocator}. Reused for
     * creation thumbnails, which live outside the per-world directory.
     */
    public static Texture loadFromKey(AssetManager assetManager, String relativeKey) {
        try {
            return assetManager.loadTexture(new TextureKey(relativeKey, true));
        } catch (AssetNotFoundException e) {
            return null;
        }
    }

    public static void dropFromCacheByKey(AssetManager assetManager, String relativeKey) {
        assetManager.deleteFromCache(new TextureKey(relativeKey, true));
    }

    // flipY = true (the jME default for UI textures) : the PNG is stored upright, so it must be flipped
    // into texture space to render the right way up on the Lemur quad.
    private static TextureKey key(String worldId) {
        return new TextureKey(relativeKey(worldId), true);
    }

    private static String relativeKey(String worldId) {
        return org.delaunois.ialon.IalonConfig.WORLDS_DIR + "/" + worldId + "/" + PREVIEW_FILENAME;
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
        this.outBuf = BufferUtils.createByteBuffer(w * h * 4);
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
            Files.createDirectories(pngFile.getParent());
            renderer.readFrameBuffer(out, outBuf);
            int targetW = Math.min(width, PREVIEW_MAX_WIDTH);
            int targetH = Math.max(1, Math.round((float) height * targetW / width));
            ByteBuffer small = downscale(outBuf, width, height, targetW, targetH);
            try (OutputStream os = Files.newOutputStream(pngFile)) {
                JmeSystem.writeImageFile(os, "png", small, targetW, targetH);
            }
            log.info("Saved world preview ({}x{}) to {}", targetW, targetH, pngFile.toAbsolutePath());
        } catch (IOException | RuntimeException e) {
            log.warn("Failed to capture world preview {}: {}", pngFile, e.getMessage());
        }
        // Remove this one-shot processor and run the continuation outside the render iteration.
        app.enqueue(() -> {
            viewPort.removeProcessor(this);
            if (onDone != null) {
                onDone.run();
            }
        });
    }

    /**
     * Nearest-neighbour downscale of a raw RGBA framebuffer read. Whole 4-byte pixels are copied, so the
     * byte order is preserved (independent of RGBA/BGRA), and rows keep their order, so the orientation
     * handled by {@link JmeSystem#writeImageFile} stays correct.
     */
    private static ByteBuffer downscale(ByteBuffer src, int w, int h, int tw, int th) {
        ByteBuffer dst = BufferUtils.createByteBuffer(tw * th * 4);
        for (int dy = 0; dy < th; dy++) {
            int sy = dy * h / th;
            for (int dx = 0; dx < tw; dx++) {
                int sx = dx * w / tw;
                int si = (sy * w + sx) * 4;
                int di = (dy * tw + dx) * 4;
                dst.put(di, src.get(si));
                dst.put(di + 1, src.get(si + 1));
                dst.put(di + 2, src.get(si + 2));
                dst.put(di + 3, src.get(si + 3));
            }
        }
        return dst;
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

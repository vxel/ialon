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
import com.jme3.app.state.AppState;
import com.jme3.math.ColorRGBA;
import com.jme3.system.AppSettings;

import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.ui.UiHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Headless-ish UI font preview : boots just the font part of the game's bootstrap (atlas manager →
 * atlas-remapped font → Lemur styling), lays out a few {@link Label}s at the font sizes the real
 * screens use, captures the GUI viewport to a PNG and exits.
 *
 * <p>{@link ScreenshotHarness} cannot be used for this : it captures the <b>3D</b> viewport and never
 * calls {@code setupGui}, so no UI text ever reaches its frames. This one skips the world entirely —
 * no chunks, no physics, no player — and screenshots {@code guiViewPort} instead, which is the
 * composited frame including the GUI bucket.
 *
 * <p>Run it through the Gradle task :
 * <pre>
 * ./gradlew :core:fontPreview                     # -&gt; core/build/screenshots/font-preview.png
 * ./gradlew :core:fontPreview --args="out.png"
 * </pre>
 *
 * @author Cedric de Launois
 */
public class FontPreview extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(FontPreview.class);

    // 1024 tall gives a 4*vh of ~41px, close to what the game shows at 1080p, with room for every row.
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 1024;

    /** Frames rendered before the capture, so the atlas upload and the text meshes have landed. */
    private static final int WARMUP_FRAMES = 10;

    private static final String[] SAMPLES = {
            "Ialon 0123456789",
            "New World / Settings / Creations",
            "The quick brown fox jumps over the lazy dog",
            "PORTLY GNOMES VEX BIG QUARTZ DWARF JACK",
            "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~",
            "^ v < > # (slider glyphs)",
            "AV To Y. W. LT AWAY (kerning)",
            "àâäçèéêëîïôöùûü ÀÂÄÇÈÉÊËÎÏÔÖÙÛÜ",
            "Où créer l'île ? À côté, ça brûle !"
    };

    private final Path output;
    private final Object lock = new Object();
    private volatile boolean finished;
    private int frames;
    private boolean capturing;

    public FontPreview(Path output) {
        super((AppState[]) null);
        this.output = output;
    }

    public static void main(String[] args) throws InterruptedException {
        Path output = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("core/build/screenshots/font-preview.png");

        FontPreview app = new FontPreview(output);
        AppSettings settings = new AppSettings(true);
        settings.setResolution(WIDTH, HEIGHT);
        settings.setGammaCorrection(true); // sRGB pipeline (matches the game)
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setTitle("FontPreview");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);

        app.start();
        long deadline = System.currentTimeMillis() + 60_000L;
        synchronized (app.lock) {
            while (!app.finished && System.currentTimeMillis() < deadline) {
                app.lock.wait(1000);
            }
        }
        if (!app.finished) {
            log.warn("Timed out before capturing the font preview");
        }
        app.stop(true);
    }

    @Override
    public void simpleInitApp() {
        IalonConfig config = new IalonConfig();
        config.getInputActionManager().setInputManager(inputManager);

        IalonInitializer.setupLogging();
        GuiGlobals.initialize(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupGui(this, config);

        // Dark, flat background : the UI font renders white, and a mid-grey shows both the glyph shapes
        // and any bilinear bleeding from the neighbouring atlas tiles.
        viewPort.setBackgroundColor(new ColorRGBA().setAsSrgb(0.12f, 0.13f, 0.16f, 1f));

        float vh = cam.getHeight() / 100f;
        float y = cam.getHeight() - 3 * vh;
        // 4*vh is the size the game uses nearly everywhere.
        for (String sample : SAMPLES) {
            y = addLine(sample, 4 * vh, y);
        }
        // 3*vh is the smallest the game uses (creation cards) — the legibility floor.
        y = addLine("Où créer l'île ? À côté, ça brûle ! 0123456789   [3vh]", 3 * vh, y);

        // The accented letters are synthesized at bake time (base letter + the face's own ASCII mark),
        // so blow two rows up : composition flaws hide at UI size.
        y = addLine("àâäçèéêëîïôöùûü", 7 * vh, y - vh);
        y = addLine("ÀÂÄÇÈÉÊËÎÏÔÖÙÛÜ", 7 * vh, y);

        // A UI icon next to the text : icons share the font's texture atlas and resolve their UVs by
        // key at build time, so this is the check that re-baking the font page (which moves every other
        // tile in the atlas) did not break the rest of the UI.
        UiHelper.IconButton gear = UiHelper.createTextureButton(config, "gear.png", 8 * vh, 0, 0);
        gear.getIcon().setLocalTranslation(cam.getWidth() - 12 * vh, cam.getHeight() - 2 * vh, 0);
        guiNode.attachChild(gear.getIcon());

        // The splashscreen is built before setupGui and loads the font itself, through jME's stock
        // BitmapFont loader and its own page texture — a different code path than everything above, so
        // exercise it here too.
        Label splash = new Label("Loading 42% - splash font, raw page   [4vh]");
        splash.setFont(assetManager.loadFont(IalonConfig.FONT_PATH));
        splash.setFontSize(4 * vh);
        splash.setLocalTranslation(2 * vh, y - 2 * vh, 0);
        guiNode.attachChild(splash);
        log.info("Font preview laid out at vh={} (4vh={}px, 3vh={}px)", vh, 4 * vh, 3 * vh);
    }

    private float addLine(String text, float fontSize, float y) {
        Label label = new Label(text);
        label.setFontSize(fontSize);
        label.setLocalTranslation(2 * (cam.getHeight() / 100f), y, 0);
        guiNode.attachChild(label);
        return y - label.getPreferredSize().y - 0.4f * fontSize;
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (capturing) {
            return;
        }
        if (++frames < WARMUP_FRAMES) {
            return;
        }
        capturing = true;
        // The GUI viewport renders last, so its postFrame sees the composited image.
        guiViewPort.addProcessor(new ScreenshotProcessor(this, output, () -> {
            synchronized (lock) {
                finished = true;
                lock.notifyAll();
            }
        }));
    }
}

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

import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.lemur.Button;

import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.AmbientSoundState;
import org.delaunois.ialon.state.CreationLibraryState;
import org.delaunois.ialon.state.CreationViewerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Films the creation viewer : boots the real {@link Ialon} application, opens the creation library, clicks
 * its "Preview" button on the first creation of the save, and captures the <b>GUI</b> viewport repeatedly
 * while the model turns.
 *
 * <p>A filmstrip rather than one still, because what this screen is for is <em>motion</em> : the viewer's
 * turntable turns the model on its own when nobody is dragging it, so consecutive frames show the creation
 * from different angles. It is also the only harness that can show the viewer at all — the model is rendered
 * offscreen and displayed as a GUI texture, so it lands in a GUI capture, and nothing else in the test suite
 * puts a live framebuffer on screen.</p>
 *
 * <pre>
 * ./gradlew :core:creationViewerPreview                     # -&gt; core/build/screenshots/creation-viewer-NNN.png
 * ./gradlew :core:creationViewerPreview -PsettleMs=35000     # slower machine : wait longer for the world
 * ./gradlew :core:creationViewerPreview -Pshots=12 -PshotMs=700
 * ./gradlew :core:creationViewerPreview -Pcreation=creation-5   # a given creation (a tree, say)
 * ./gradlew :core:creationViewerPreview -Preopens=2        # close and reopen it twice first (with a GC)
 * ./gradlew :core:creationViewerPreview -Pcreation=creation-6 -Palt=creation-1 -Preopens=4   # alternating
 * ./gradlew :core:creationViewerPreview -PsetImage=true    # press "Set image" : REWRITES the card PNG
 * ./gradlew :core:creationViewerPreview -Presize=true       # rebuild the render target while it is up
 * ./gradlew :core:creationViewerPreview -Pwidth=800 -Pheight=600
 * ./gradlew :core:creationViewerPreview -Pinteractive=true   # leave the window up and drag the model by hand
 * </pre>
 *
 * <p>What to check on the captures, all of it invisible to the compiler and to the unit tests :</p>
 * <ul>
 *   <li>the model is <b>the right way up</b> — the framebuffer texture is bound straight into the GUI here,
 *       where the card thumbnails go through a PNG and a {@code flipY} load ;</li>
 *   <li>it is <b>as bright as the same creation's card</b> (the popup shows both, one after the other) — a
 *       dark model would mean the offscreen target is being colour-managed differently ;</li>
 *   <li>the background inside the panel is <b>black</b>, and the panel's edges are clean ;</li>
 *   <li>the model <b>never touches the panel edge</b>, at any of the angles the filmstrip catches ;</li>
 *   <li>water / glass / fire faces, if the creation holds any, do not show through each other.</li>
 * </ul>
 *
 * @author Cedric de Launois
 */
public class CreationViewerPreview {

    private static final Logger log = LoggerFactory.getLogger(CreationViewerPreview.class);

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    private CreationViewerPreview() {
        // main only
    }

    public static void main(String[] args) throws InterruptedException {
        Path outputDir = Paths.get(args.length > 0 ? args[0] : "core/build/screenshots");
        long settleMs = Long.getLong("ialon.creationViewerPreview.settleMs", 25_000L);
        long shotMs = Long.getLong("ialon.creationViewerPreview.shotMs", 500L);
        int shots = Integer.getInteger("ialon.creationViewerPreview.shots", 8);
        boolean interactive = !System.getProperty("ialon.creationViewerPreview.interactive", "").isEmpty();

        int width = Integer.getInteger("ialon.creationViewerPreview.width", 1280);
        int height = Integer.getInteger("ialon.creationViewerPreview.height", 720);

        IalonConfig config = new IalonConfig();
        IalonConfigRepository.loadConfig(config);
        Ialon app = new Ialon(config);
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Ialon creation viewer preview");
        settings.setResolution(width, height);
        settings.setGammaCorrection(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();

        // The world comes up asynchronously on the render thread and there is no "ready" signal to wait on.
        Thread.sleep(settleMs);
        logAmbience(app, "in the world");

        // Through the library and its button, not straight to CreationViewerState.enter : the wiring — the
        // default selection, the load, the hand-over that closes the library — is half of what needs checking.
        log.info("Opening the creation library");
        app.enqueue(() -> {
            CreationLibraryState library = app.getStateManager().getState(CreationLibraryState.class);
            if (library == null) {
                log.error("No creation library state : nothing to preview");
                return;
            }
            library.showPopup();
            // Which creation : the first one unless asked otherwise. Worth choosing, because what a creation
            // is made of is what the viewer has to get right — a tree's alpha-tested leaves are a different
            // test from a house's opaque walls.
            String creationId = System.getProperty("ialon.creationViewerPreview.creation", "");
            if (!creationId.isEmpty()) {
                library.selectCard(creationId);
            }
        });
        Thread.sleep(1000L);

        // The library itself, for the button column the Preview entry joins : five rows is the tallest that
        // column has ever been, so it is worth seeing at the resolution being tested.
        log.info("Capturing the library");
        Path libraryShot = outputDir.resolve("creation-library.png");
        app.enqueue(() -> app.getGuiViewPort().addProcessor(new ScreenshotProcessor(app, libraryShot, () -> {
            // Nothing to do : the processor removes itself.
        })));
        Thread.sleep(500L);

        log.info("Pressing the Preview button");
        app.enqueue(() -> {
            if (!clickButton(app, "preview")) {
                log.error("No Preview button on screen : is there any creation in {} ?",
                        config.getSavePath().resolve("creations").toAbsolutePath());
            }
        });
        Thread.sleep(1000L);
        // A world-hiding popup silences the world too (Ialon.updateWorldPresence). Reported rather than
        // merely asserted : this harness is where the popup is actually up in a running game.
        logAmbience(app, "with the viewer open");

        if (interactive) {
            log.info("Interactive : drag the model with the mouse. Close the window when done.");
            return;
        }

        // Opening the viewer AGAIN is its own test, and a sharp one : the render target is torn down and
        // built anew, so a teardown that frees a GL object the wrong way only shows from the second viewing
        // on — and only once a collection has run, which is why one is forced here rather than waited for.
        int reopens = Integer.getInteger("ialon.creationViewerPreview.reopens", 0);
        // With -Palt, every other reopening shows a DIFFERENT creation : the render target is reused across
        // viewings, so a second creation is what proves the camera is re-aimed at the new box rather than
        // still framing the previous one.
        String alternate = System.getProperty("ialon.creationViewerPreview.alternate", "");
        for (int i = 0; i < reopens; i++) {
            log.info("Reopening the viewer ({})", i + 1);
            app.enqueue(() -> clickButton(app, "back"));
            Thread.sleep(400L);
            System.gc();
            if (!alternate.isEmpty()) {
                String pick = i % 2 == 0 ? alternate
                        : System.getProperty("ialon.creationViewerPreview.creation", "");
                app.enqueue(() -> {
                    CreationLibraryState library = app.getStateManager().getState(CreationLibraryState.class);
                    if (library != null && !pick.isEmpty()) {
                        library.selectCard(pick);
                    }
                });
            }
            Thread.sleep(400L);
            app.enqueue(() -> clickButton(app, "preview"));
            Thread.sleep(600L);
        }

        // The riskiest path in the viewer : a resolution change deletes the framebuffer and its texture and
        // builds both again while the popup is up. Driven straight through onResize (what ScreenState calls),
        // at the same size — the rebuild is what is being exercised, not the new geometry.
        if (!System.getProperty("ialon.creationViewerPreview.resize", "").isEmpty()) {
            log.info("Rebuilding the render target through onResize");
            app.enqueue(() -> {
                CreationViewerState viewer = app.getStateManager().getState(CreationViewerState.class);
                if (viewer != null) {
                    viewer.onResize(width, height);
                }
            });
            Thread.sleep(1000L);
        }

        for (int i = 0; i < shots; i++) {
            Thread.sleep(shotMs);
            Path output = outputDir.resolve(String.format("creation-viewer-%03d.png", i));
            // One processor per shot : ScreenshotProcessor removes itself once it has written its frame.
            app.enqueue(() -> app.getGuiViewPort().addProcessor(new ScreenshotProcessor(app, output, () -> {
                // Nothing to do : the next shot brings its own processor.
            })));
        }

        log.info("Captured {} frames to {}", shots, outputDir.toAbsolutePath());
        Thread.sleep(shotMs);

        // "Set image" : takes the card image from the angle on screen, so it has to be pressed WHILE the
        // viewer is up. The shot that follows catches the note it puts on screen ; the library shot further
        // down is where the new card image itself shows.
        if (!System.getProperty("ialon.creationViewerPreview.setImage", "").isEmpty()) {
            log.info("Pressing Set image");
            app.enqueue(() -> {
                if (!clickButton(app, "set image")) {
                    log.error("No Set image button on screen : is the viewer up ?");
                }
            });
            Thread.sleep(1200L);
            Path noteShot = outputDir.resolve("creation-viewer-setimage.png");
            app.enqueue(() -> app.getGuiViewPort().addProcessor(new ScreenshotProcessor(app, noteShot, () -> {
                // Nothing to do : the processor removes itself.
            })));
            Thread.sleep(700L);
        }

        // Back : tears the offscreen render down (the framebuffer and its texture are deleted there) and
        // reopens the library. Captured, so that the shot proves the library came back rather than the app
        // merely surviving — and, after a "Set image", that the card carries the new image.
        log.info("Pressing Back");
        app.enqueue(() -> clickButton(app, "back"));
        Thread.sleep(700L);
        Path backShot = outputDir.resolve("creation-library-back.png");
        app.enqueue(() -> app.getGuiViewPort().addProcessor(new ScreenshotProcessor(app, backShot, () -> {
            // Nothing to do : the processor removes itself.
        })));
        // Let the shot be taken before anything else is pressed : the processor captures the next frame, and
        // an enqueued click would otherwise be handled in that very frame — the capture would show what comes
        // after it rather than the library.
        Thread.sleep(700L);

        logAmbience(app, "back in the library");

        // Closing the last popup must bring the world back, sound included — the other half of what
        // Ialon.updateWorldPresence promises, and the half a suspend-only bug would leave broken.
        log.info("Pressing Close");
        app.enqueue(() -> clickButton(app, "close"));
        Thread.sleep(700L);
        logAmbience(app, "back in the world");

        Thread.sleep(shotMs);
        app.enqueue((Runnable) app::stop);
    }

    /** Reports whether the world's ambience is playing : it must stop while a world-hiding popup is up. */
    private static void logAmbience(Ialon app, String when) {
        AmbientSoundState ambience = app.getStateManager().getState(AmbientSoundState.class);
        log.info("Ambient sound {} : {}", when,
                ambience == null ? "no state" : (ambience.isEnabled() ? "PLAYING" : "silent"));
    }

    /** Clicks the first GUI button carrying that label, and reports whether there was one. */
    private static boolean clickButton(Ialon app, String text) {
        Button button = findButton(app.getGuiNode(), text);
        if (button == null) {
            return false;
        }
        button.click();
        return true;
    }

    /** The first Lemur button of the GUI whose label matches, ignoring case. */
    private static Button findButton(Spatial spatial, String text) {
        if (spatial instanceof Button && text.equalsIgnoreCase(((Button) spatial).getText())) {
            return (Button) spatial;
        }
        if (spatial instanceof Node) {
            for (Spatial child : ((Node) spatial).getChildren()) {
                Button found = findButton(child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}

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

import static org.delaunois.ialon.input.IalonKeyMapping.ACTION_RESET_CHUNK;

import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.lemur.Button;

import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.ialon.state.PlayerState;
import org.delaunois.ialon.state.RegionDeleteState;
import org.delaunois.ialon.state.RenderReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Screenshots the in-game HUD : boots the real {@link Ialon} application against the saved world, lets
 * it settle, and captures the <b>GUI</b> viewport to a PNG.
 *
 * <p>The other harnesses each miss half of it — {@link ScreenshotHarness} captures the 3D viewport and
 * never builds the HUD, {@link UiPreview} builds the theme chrome but not the play-screen buttons. This
 * one is the only way to see what the on-screen controls actually look like without a device : use it
 * whenever the HUD's geometry changes (positions, plates, batching), since a draw-call count proves the
 * geometry is submitted but not that it lands where it used to.
 *
 * <pre>
 * ./gradlew :core:hudPreview                                  # -&gt; core/build/screenshots/hud-preview.png
 * ./gradlew :core:hudPreview --args="out.png"                 # custom output
 * ./gradlew :core:hudPreview -PsettleMs=30000                 # wait longer before capturing
 * ./gradlew :core:hudPreview -PhudMode=regionDelete           # HUD as the region-delete selection shows it
 * ./gradlew :core:hudPreview -PhudMode=chunkReset             # the "Reset chunk" confirmation dialog
 * ./gradlew :core:hudPreview -PhudMode=chunkResetRun -PsavePath=/tmp/save-copy   # digs a hole, confirms the reset
 * </pre>
 *
 * <p><b>chunkResetRun EDITS THE SAVED WORLD</b> (that is the point : it digs, then resets a whole chunk
 * column, and a reset deletes the saved chunks). Point it at a copy with
 * {@code -PsavePath=/tmp/save-copy} rather than at {@code ./save}.</p>
 *
 * <pre>
 * </pre>
 *
 * @author Cedric de Launois
 */
public class HudPreview {

    private static final Logger log = LoggerFactory.getLogger(HudPreview.class);

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    private HudPreview() {
        // main only
    }

    public static void main(String[] args) throws InterruptedException {
        Path output = Paths.get(args.length > 0 ? args[0] : "core/build/screenshots/hud-preview.png");
        long settleMs = Long.getLong("ialon.hudPreview.settleMs", 25_000L);

        IalonConfig config = new IalonConfig();
        // A destructive mode (chunkResetRun) is best pointed at a copy of the world rather than at ./save.
        String savePath = System.getProperty("ialon.hudPreview.savePath", "");
        if (!savePath.isEmpty()) {
            config.setSavePath(Paths.get(savePath));
        }
        IalonConfigRepository.loadConfig(config);
        Ialon app = new Ialon(config);
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Ialon HUD preview");
        settings.setResolution(1280, 720);
        settings.setGammaCorrection(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();

        // The world (splash, atlas, block framework, chunk paging) comes up asynchronously on the render
        // thread ; there is no "ready" signal to wait on, so give it a fixed budget then capture.
        Thread.sleep(settleMs);

        // Optionally open an editing mode first : the HUD is not one screen but several (the region
        // selection hides the +/- buttons and offers "Reset chunk" instead), and those variants need
        // checking as much as the default one.
        String mode = System.getProperty("ialon.hudPreview.mode", "");
        if ("regionDelete".equals(mode) || "chunkReset".equals(mode)) {
            log.info("Entering the region-delete selection");
            app.enqueue(() -> {
                RegionDeleteState regionDelete = app.getStateManager().getState(RegionDeleteState.class);
                if (regionDelete != null) {
                    regionDelete.enter();
                }
            });
            Thread.sleep(500L);
        }
        if ("chunkReset".equals(mode) || "chunkResetRun".equals(mode)) {
            // Through the action, not straight to the state : that is the whole path the on-screen button
            // takes (drop the selection, then put the confirmation up).
            log.info("Pressing the reset-chunk button");
            app.enqueue(() -> config.getInputActionManager().triggerAction(ACTION_RESET_CHUNK, true));
            Thread.sleep(500L);
        }
        if ("chunkResetRun".equals(mode)) {
            runChunkReset(app, config);
        }

        // Draw-call breakdown of the settled HUD, logged before the shot. This is the only harness that
        // builds the play-screen controls, so it is the only place the GUI viewport's draw calls can be
        // counted without shipping a build to a device -- which is far too slow a loop to batch the HUD
        // by. Requested one-shot rather than via Ialon's -Dialon.renderreport periodic state, which would
        // spam the whole settle window. Its own frames come first (attach, read one frame's queues, log),
        // hence the pause before the capture stops the app.
        log.info("Requesting a render report");
        app.enqueue(() -> RenderReport.request(app));
        Thread.sleep(1000L);

        log.info("Capturing the GUI viewport to {}", output.toAbsolutePath());
        app.enqueue(() -> app.getGuiViewPort().addProcessor(
                new ScreenshotProcessor(app, output, app::stop)));
    }

    /**
     * Drives a whole chunk reset in the running game : digs a hole in the ground under the player, confirms
     * the dialog by clicking its real button, and reports whether the ground came back. The one way to
     * exercise the runtime path end to end (background regeneration, install on the update thread, re-mesh,
     * physics, minimap) without a device — the unit tests can only reach {@code WorldManager.resetColumn}.
     */
    private static void runChunkReset(Ialon app, IalonConfig config) throws InterruptedException {
        Vector3f player = config.getPlayerLocation();
        Vector3f dug = new Vector3f(player.x, player.y - 2, player.z);
        WorldManager worldManager = app.getStateManager().getState(PlayerState.class).getWorldManager();

        app.enqueue(() -> {
            for (int y = -4; y <= -2; y++) {
                for (int z = -2; z <= 2; z++) {
                    for (int x = -2; x <= 2; x++) {
                        worldManager.removeBlock(new Vector3f(player.x + x, player.y + y, player.z + z));
                    }
                }
            }
            log.info("Dug a hole under the player : block at {} is now {}", dug, worldManager.getBlock(dug));
        });
        Thread.sleep(1000L);

        app.enqueue(() -> {
            Button reset = findButton(app.getGuiNode(), "reset");
            if (reset == null) {
                log.error("No Reset button on screen : is the confirmation up ?");
                return;
            }
            log.info("Clicking the {} button", reset.getText());
            reset.click();
        });
        Thread.sleep(3000L);

        app.enqueue(() -> log.info("After the reset : block at {} is {}, player at {}",
                dug, worldManager.getBlock(dug), config.getPlayerLocation()));
        Thread.sleep(500L);
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

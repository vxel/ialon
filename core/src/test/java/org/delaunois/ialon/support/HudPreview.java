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

import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.serialize.IalonConfigRepository;
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
}

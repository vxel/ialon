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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Films the loading screen : boots the real {@link Ialon} application and captures the GUI viewport
 * repeatedly while the world loads, producing a filmstrip of PNGs.
 *
 * <p>The point is what a single screenshot cannot show — that the bar <em>advances</em> and that the
 * phase label follows the work. {@link HudPreview} captures the same viewport but only once the world is
 * up, by which time the splash is long gone, and {@link ScreenshotHarness} never renders the HUD at all.
 *
 * <pre>
 * ./gradlew :core:splashPreview                       # -&gt; core/build/screenshots/splash-NNN.png
 * ./gradlew :core:splashPreview -PshotMs=250          # capture more often
 * ./gradlew :core:splashPreview -Pshots=30            # capture more frames
 * </pre>
 *
 * @author Cedric de Launois
 */
public class SplashPreview {

    private static final Logger log = LoggerFactory.getLogger(SplashPreview.class);

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    private SplashPreview() {
        // main only
    }

    public static void main(String[] args) throws InterruptedException {
        Path outputDir = Paths.get(args.length > 0 ? args[0] : "core/build/screenshots");
        long shotMs = Long.getLong("ialon.splashPreview.shotMs", 400L);
        int shots = Integer.getInteger("ialon.splashPreview.shots", 20);

        IalonConfig config = new IalonConfig();
        IalonConfigRepository.loadConfig(config);
        Ialon app = new Ialon(config);
        AppSettings settings = new AppSettings(true);
        settings.setTitle("Ialon splash preview");
        settings.setResolution(1280, 720);
        settings.setGammaCorrection(true);
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);
        app.start();

        for (int i = 0; i < shots; i++) {
            Thread.sleep(shotMs);
            Path output = outputDir.resolve(String.format("splash-%03d.png", i));
            // One processor per shot : ScreenshotProcessor removes itself after writing its frame.
            app.enqueue(() -> app.getGuiViewPort().addProcessor(new ScreenshotProcessor(app, output, () -> {
                // Nothing to do : the processor removes itself, and the next shot brings its own.
            })));
        }

        log.info("Captured {} frames to {}", shots, outputDir.toAbsolutePath());
        Thread.sleep(shotMs);
        app.enqueue((Runnable) app::stop);
    }
}

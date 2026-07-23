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

package org.delaunois.ialon;

import com.jme3.system.AppSettings;
import com.jme3.system.NativeLibraryLoader;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.serialize.IalonConfigRepository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;


public class DesktopLauncher {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
        redirectWritesToUserDataDir();
    }

    /**
     * When running from a packaged/installed build (started with {@code -Dialon.packaged=true},
     * set by jpackage), the install directory is typically read-only. Redirect all runtime writes
     * to a per-user, writable data directory:
     * <ul>
     *   <li>Windows: {@code %APPDATA%\Ialon}</li>
     *   <li>Linux:   {@code $XDG_DATA_HOME/ialon} (or {@code ~/.local/share/ialon})</li>
     * </ul>
     * Setting {@code user.dir} makes relative paths resolve here — this covers the game saves
     * ({@code ./save} in IalonConfig) and Minie's native extraction ({@code new File(".")}).
     * {@code NativeLibraryLoader.setCustomExtractionFolder} does the same for jME's LWJGL/OpenAL
     * natives. When not packaged (development runs), nothing is changed: saves stay under the
     * working directory ({@code ./save}).
     */
    private static void redirectWritesToUserDataDir() {
        if (!Boolean.getBoolean("ialon.packaged")) {
            return;
        }
        try {
            Path dataDir = resolveUserDataDir();
            Files.createDirectories(dataDir);
            String absolute = dataDir.toAbsolutePath().toString();
            System.setProperty("user.dir", absolute);
            NativeLibraryLoader.setCustomExtractionFolder(absolute);
        } catch (Exception e) {
            // Fall back to the default working directory; startup logging will surface any
            // subsequent write failure.
            System.err.println("Ialon: could not set up the user data directory: " + e.getMessage());
        }
    }

    private static Path resolveUserDataDir() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            if (appData != null && !appData.isEmpty()) {
                return Paths.get(appData, "Ialon");
            }
            return Paths.get(System.getProperty("user.home"), "AppData", "Roaming", "Ialon");
        }
        String xdg = System.getenv("XDG_DATA_HOME");
        if (xdg != null && !xdg.isEmpty()) {
            return Paths.get(xdg, "ialon");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "share", "ialon");
    }

    public static void main(String[] args) {
        IalonConfig config = new IalonConfig();
        // BEFORE the load, not after : this is the desktop DEFAULT for a save that has never stored the
        // setting, not an override. Set afterwards, it silently discarded the player's own choice on every
        // launch — the debug overlays came back however often they were turned off in the settings.
        config.setDevMode(true);
        IalonConfigRepository.loadConfig(config);
        config.setGridRadiusMin(3);
        config.setGridRadiusMax(20);
        Ialon app = new Ialon(config);

        AppSettings settings = new AppSettings(false);
        settings.setFrameRate(config.getMaxFramerate());
        settings.setGammaCorrection(true);
        settings.setResizable(true);
        // Launch in fullscreen by default ; the last-used mode is restored from the persisted config
        // (game.yml) and updated live by the F2 toggle in ScreenState. -1x-1 lets jME pick the current
        // desktop resolution for fullscreen (same convention as the runtime toggle).
        if (config.isFullscreen()) {
            settings.setFullscreen(true);
            settings.setResolution(-1, -1);
        } else {
            settings.setFullscreen(false);
            settings.setResolution(config.getScreenWidth(), config.getScreenHeight());
        }
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setUseInput(true);
        // OpenAL, for the ambient sounds (AmbientSoundState). libopenal64.so sits at the repo root with
        // the other natives. The headless support harnesses still pass null : they must not open a
        // device. IalonInitializer.setupAudio() is what turns this into a working listener.
        //
        // Known desktop trap, and NOT a bug in this game : a Bluetooth headset left in the HFP/headset
        // profile makes the OS sink mono 16 kHz, OpenAL adopts that format, and its 2013 vintage (LWJGL2
        // ships OpenAL Soft 1.15.1) handles the path badly — a tick about twice a second. Confirmed to be
        // below us : a plain ffmpeg tone played through OpenAL ticks too, while the same file through VLC
        // or ffplay is clean.
        //
        // Not fixable from here — jME calls alc.createALC() with NO attributes, so neither the mixing
        // rate nor the buffer can be set by the application. It is an ~/.alsoftrc job, and the content
        // matters :
        //     [general]
        //     frequency=48000
        //     channels=stereo
        //     period_size=1024
        //     periods=4
        // Stopping OpenAL from adopting the sink's format is what cures it — PipeWire then does the
        // conversion, which it does well. Note that `periods` alone is READ AND IGNORED while OpenAL
        // follows the sink (the pulse backend forces its own 2-buffer model) ; it only takes effect once
        // the format is forced, which is why a config with just `periods=4` changes nothing.
        // Switching the headset to A2DP fixes it too, and is the only thing that restores 3D placement.
        settings.setAudioRenderer(AppSettings.LWJGL_OPENAL);
        settings.setVSync(true);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
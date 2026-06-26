/*
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

import android.app.ActivityManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;

import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.jme.AndroidHarness;

import java.util.logging.Level;
import java.util.logging.Logger;

public class MainActivity extends AndroidHarness {

    protected static final Logger logger = Logger.getLogger(MainActivity.class.getName());

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
        // PROFILING TOGGLE : enable the render-thread hitch profiler on mobile. Logs (to logcat, tag
        // HitchProfilerState) one line per frame slower than this many ms, splitting the spike into
        // work (state/spatial/render) vs gap (swap/vsync/driver), the top AppStep, GC and chunk churn.
        // Set very early (class load, before the GL app is constructed) so the TimingNodes get installed
        // too. Steady path is allocation-free, but remove/raise this for release builds. 20 ms catches
        // dropped frames at the 60 fps cap.
        //
        //System.setProperty("ialon.hitch", "40");
    }

    public MainActivity() {
        appClass = Ialon.class.getCanonicalName();
        mouseEventsEnabled = true;
        screenShowTitle = false;
        frameRate = IalonConfig.FPS_LIMIT_MOBILE;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // The GL surface (and the app) are created inside super.onCreate, which reads the harness
        // `frameRate` field. The full config is only loaded later in onStart, so preload just the
        // persisted frame-rate cap here ; otherwise it would only take effect after toggling it in-game.
        try {
            IalonConfig config = new IalonConfig();
            config.setSavePath(getApplicationContext().getFilesDir().toPath());
            IalonConfigRepository.loadConfig(config);
            this.frameRate = config.getMaxFramerate();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Could not preload frame-rate setting", e);
        }
        super.onCreate(savedInstanceState);
    }

    @SuppressWarnings("java:S1874")
    @Override
    protected void onResume() {
        super.onResume();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        } else {
            // Needed to hide android buttons again
            View decorView = getWindow().getDecorView();
            int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE;
            decorView.setSystemUiVisibility(uiOptions);
        }
    }

    @Override
    protected void onStart() {
        logger.log(Level.INFO, "Android Start Ialon");

        AppSettings settings = new AppSettings(true);
        settings.setAudioRenderer(null);
        settings.setFrameRate(IalonConfig.FPS_LIMIT_MOBILE);
        // Render through the sRGB pipeline (linear lighting, hardware sRGB framebuffer). This is what
        // keeps the colours consistent between desktop and Android : without it the GLES default
        // framebuffer made everything look noticeably darker than on the PC.
        settings.setGammaCorrection(true);
        app.setSettings(settings);

        IalonConfig config = new IalonConfig();
        config.setSavePath(getApplicationContext().getFilesDir().toPath());
        config.setSaveUserSettingsOnStop(false);
        IalonConfigRepository.loadConfig(config);
        config.setDevMode(false);
        // Cap the render distance to what the device's Java (ART) heap can hold. The grid loads
        // (2*gridRadius+1)^2 * gridHeight chunks, and each chunk keeps its blocks short[] + lightMap
        // byte[] on the heap : pushing gridRadius too high on a low-RAM device throws OutOfMemoryError.
        // largeHeap (AndroidManifest) raises that ceiling ; getLargeMemoryClass() reports the resulting
        // heap budget so we scale the max accordingly (a flagship keeps 7, an entry-level phone is
        // limited automatically). The runtime MemoryGuardState is the last-resort safety net.
        config.setGridRadiusMin(2);
        config.setGridRadiusMax(maxGridRadiusForDevice());
        // loadConfig() above clamped the persisted gridRadius against the default max (15) ; re-clamp it
        // now against the (lower) device-specific max so a value saved on a roomier session is brought down.
        config.setGridRadius(config.getGridRadius());
        config.setMaxUpdatePerFrame(2);
        // Apply the persisted frame-rate cap (toggled in the in-game settings). Keep the harness field
        // in sync as it is what the GL surface reads.
        this.frameRate = config.getMaxFramerate();
        settings.setFrameRate(config.getMaxFramerate());
        ((Ialon) app).setConfig(config);

        super.onStart();
    }

    /**
     * Maximum render distance (gridRadius) the device can afford, derived from its large-heap Java
     * budget ({@link ActivityManager#getLargeMemoryClass()}, in MB).
     */
    private int maxGridRadiusForDevice() {
        ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        int heapMb = am == null ? 0 : am.getLargeMemoryClass();
        logger.log(Level.INFO, "Device large heap class: {0} MB", heapMb);
        if (heapMb < 128) return 5;
        if (heapMb < 192) return 6;
        if (heapMb < 256) return 7;
        if (heapMb < 384) return 8;
        return 9;
    }

    @Override
    protected void onStop() {
        // User preferences cannot be written during onDestroy
        Ialon ialon = (Ialon) app;
        IalonConfigRepository.saveConfig(ialon, ialon.getConfig());
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        app.stop();
        super.onDestroy();
    }

}

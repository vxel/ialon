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
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.RequiresApi;

import com.jme3.input.event.TouchEvent;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.jme.AndroidHarness;

import java.util.logging.Level;
import java.util.logging.Logger;

public class MainActivity extends AndroidHarness {

    protected static final Logger logger = Logger.getLogger(MainActivity.class.getName());

    // The registered back-navigation callback (Android 13+), kept so it can be unregistered ; null on
    // older devices, which use the key-event routes instead. See registerBackCallback().
    private PredictiveBack predictiveBack;

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
        // PROFILING TOGGLE : set system property ialon.hitch to e.g. 40
        //System.setProperty("ialon.hitch", "25");
        //System.setProperty("ialon.rendercpu", "2");
        //System.setProperty("ialon.batchregion", "2");
        //System.setProperty("ialon.memreport", "20");
        //System.setProperty("ialon.noWater", "true");
        //System.setProperty("ialon.noRelief", "true");
        //System.setProperty("ialon.noEnclosure", "true");
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
        registerBackCallback();
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
        // OpenAL Soft (from jme3-android-native), for the ambient sounds (AmbientSoundState). This has
        // to be set HERE and not left to AndroidHarness.audioRendererType : these settings replace the
        // harness's wholesale. AndroidHarness.onPause()/onResume() already pause and resume the
        // renderer, so there is nothing else to wire for the activity lifecycle.
        settings.setAudioRenderer(AppSettings.ANDROID_OPENAL_SOFT);
        settings.setFrameRate(IalonConfig.FPS_LIMIT_MOBILE);
        // Render through the sRGB pipeline (linear lighting, hardware sRGB framebuffer). This is what
        // keeps the colours consistent between desktop and Android : without it the GLES default
        // framebuffer made everything look noticeably darker than on the PC.
        settings.setGammaCorrection(true);
        app.setSettings(settings);

        IalonConfig config = new IalonConfig();
        config.setSavePath(getApplicationContext().getFilesDir().toPath());
        config.setSaveUserSettingsOnStop(false);
        // BEFORE the load, not after : the Android DEFAULT for a save that has never stored the setting.
        // Set afterwards it would overrule what the player chose in the settings, every launch (see
        // DesktopLauncher, which had exactly that bug).
        config.setDevMode(false);
        IalonConfigRepository.loadConfig(config);
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
        Ialon ialon = (Ialon) app;
        ialon.setConfig(config);
        // Leaving the game = finishing the activity, and NOT stopping the application from inside it : the
        // app is stopped by onDestroy, at the point of the lifecycle where the harness expects it (see
        // Ialon.setExitHandler for the crash that taught us). On the UI thread because the confirmation is
        // answered on the update thread, and finish() belongs to the activity's own thread.
        ialon.setExitHandler(() -> runOnUiThread(this::finish));

        super.onStart();
    }

    /**
     * The player asked to go back — from any of the three routes below. Hands it to the game, which backs
     * out of whatever is open and otherwise puts the "Leave Ialon ?" confirmation up
     * ({@code Ialon.requestExit}), so the gesture behaves exactly as ESC does on desktop.
     *
     * <p>Deliberately does <b>not</b> let the activity finish : that is now the game's decision to make,
     * once the player has answered the dialog.</p>
     *
     * <p>Called from the system's UI/GL threads, while the states it reaches — the popups, the Lemur GUI —
     * are only ever touched on the update thread, hence the enqueue.</p>
     */
    private void onBackRequested() {
        Ialon ialon = (Ialon) app;
        if (ialon == null) {
            finish(); // no game to ask (already stopped) : back means back
            return;
        }
        ialon.enqueue(ialon::requestExit);
    }

    /**
     * Back navigation as Android 13+ delivers it, and the <b>only</b> route that fires on a current device.
     *
     * <p>Targeting SDK 36 opts the app into predictive back, under which the platform stops delivering back
     * the two old ways : {@code onBackPressed()} is no longer called and {@code KEYCODE_BACK} is no longer
     * dispatched, so neither the harness's {@code TouchEscape} mapping nor an {@code onBackPressed} override
     * ever sees the gesture — the system simply finished the activity, which is why the game appeared to be
     * put to sleep rather than asking anything. Intercepting it now means registering a callback with the
     * dispatcher, which this does for the whole life of the activity.</p>
     *
     * <p>{@code PRIORITY_DEFAULT} (rather than {@code PRIORITY_SYSTEM_NAVIGATION_OBSERVER}) is the point :
     * the observer priority only <em>watches</em> a back navigation the system still performs, and the whole
     * purpose here is to take the decision away from it. The cost is the predictive back-to-home animation,
     * which cannot run for a gesture the app may well answer by staying.</p>
     */
    private void registerBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            predictiveBack = PredictiveBack.register(this, this::onBackRequested);
        }
    }

    /**
     * Back navigation on a pre-13 device, where the gesture is still delivered as a key event : the GL
     * surface gets it first and jME turns it into the {@code TouchEscape} event the harness mapped (see
     * {@link AndroidHarness#initialize()}), consuming it — so {@link #onBackPressed()} does not also run.
     */
    @Override
    public void onTouch(String name, TouchEvent evt, float tpf) {
        if (ESCAPE_EVENT.equals(name) && evt.getType() == TouchEvent.Type.KEY_UP) {
            onBackRequested();
        }
    }

    /**
     * Back navigation on a pre-13 device whose key event did not reach the GL surface (it only gets keys
     * while focused). Dead code on Android 13+, which never calls it — hence
     * {@link #registerBackCallback()}.
     */
    @Override
    @SuppressWarnings({"java:S1874"})
    public void onBackPressed() {
        if (app == null) {
            super.onBackPressed();
            return;
        }
        onBackRequested();
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
        if (predictiveBack != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                predictiveBack.unregister(this);
            }
            predictiveBack = null;
        }
        app.stop();
        super.onDestroy();
    }

    /**
     * The Android 13+ back-navigation callback and its registration, held apart from the activity so that
     * the {@code android.window} types it names are only ever loaded on a device that has them : minSdk is
     * 26, and a class verifier meeting an unknown type can reject the whole enclosing method. Every entry
     * point is therefore behind the {@code SDK_INT} check in {@link #registerBackCallback()}.
     */
    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)
    private static final class PredictiveBack {

        private final OnBackInvokedCallback callback;

        private PredictiveBack(OnBackInvokedCallback callback) {
            this.callback = callback;
        }

        static PredictiveBack register(MainActivity activity, Runnable onBack) {
            OnBackInvokedCallback callback = onBack::run;
            activity.getOnBackInvokedDispatcher()
                    .registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            logger.log(Level.INFO, "Back navigation routed to the game's exit confirmation");
            return new PredictiveBack(callback);
        }

        void unregister(MainActivity activity) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(callback);
        }
    }

}

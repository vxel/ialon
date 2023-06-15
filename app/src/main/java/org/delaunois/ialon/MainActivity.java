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

import android.os.Build;
import android.view.View;

import com.jme3.system.AppSettings;

import org.delaunois.ialon.serialize.IalonConfigRepository;
import org.delaunois.jme.AndroidHarness;

import java.util.logging.Level;
import java.util.logging.Logger;

public class MainActivity extends AndroidHarness {

    protected static final Logger logger = Logger.getLogger(MainActivity.class.getName());

    public MainActivity() {
        appClass = Ialon.class.getCanonicalName();
        mouseEventsEnabled = true;
        screenShowTitle = false;
        frameRate = IalonConfig.FPS_LIMIT;
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
        settings.setFrameRate(IalonConfig.FPS_LIMIT);
        app.setSettings(settings);

        IalonConfig config = new IalonConfig();
        config.setSavePath(getApplicationContext().getFilesDir().toPath());
        config.setSaveUserSettingsOnStop(false);
        IalonConfigRepository.loadConfig(config);
        config.setGridRadiusMax(7);
        config.setGridRadiusMin(2);
        config.setMaxUpdatePerFrame(2);
        config.setGammaCorrection(1.2f);
        ((Ialon) app).setConfig(config);

        super.onStart();
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

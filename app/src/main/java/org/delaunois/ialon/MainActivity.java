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
import android.os.Bundle;
import android.view.View;

import com.jme3.system.AppSettings;

import org.delaunois.jme.AndroidHarness;

public class MainActivity extends AndroidHarness {
    public MainActivity() {
        appClass = Ialon.class.getCanonicalName();
        mouseEventsEnabled = true;
        screenShowTitle = false;
        frameRate = IalonConfig.getInstance().getFpsLimit();
        IalonConfig.getInstance().setGridRadiusMax(6);
        IalonConfig.getInstance().setMaxUpdatePerFrame(2);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        IalonConfig.getInstance().setSavePath(getApplicationContext().getFilesDir().toPath());
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
        super.onStart();
        AppSettings settings = new AppSettings(true);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        IalonConfig.getInstance().setSaveUserSettingsOnStop(false);
    }

    @Override
    protected void onStop() {
        // User preferences cannot be written during onDestroy
        ((Ialon) app).saveUserSettings();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        app.stop();
        super.onDestroy();
    }

}

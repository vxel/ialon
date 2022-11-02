package org.delaunois.ialon;

import android.os.Bundle;
import android.view.View;

import com.jme3.system.AppSettings;

public class MainActivity extends AndroidHarness {
    public MainActivity() {
        appClass = Ialon.class.getCanonicalName();
        exitDialogTitle = "Exit?";
        exitDialogMessage = "Are you sure you want to quit?";
        mouseEventsEnabled = true;
        screenShowTitle = false;
        frameRate = Config.FPS_LIMIT;
        Config.GRID_RADIUS_MAX = 6;
        Config.MAX_UPDATE_PER_FRAME = 2;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Ialon.setFilePath(getApplicationContext().getFilesDir().toPath());
        setTheme(R.style.Theme_Ialon);
        super.onCreate(savedInstanceState);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Needed to hide android buttons again
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppSettings settings = new AppSettings(true);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
        ((Ialon) app).setSaveUserPreferencesOnStop(false);
    }

    @Override
    protected void onStop() {
        // User preferences cannot be written during onDestroy
        ((Ialon) app).saveUserPreferences();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        app.stop();
        super.onDestroy();
    }

}

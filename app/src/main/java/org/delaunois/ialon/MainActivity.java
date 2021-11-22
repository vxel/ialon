package org.delaunois.ialon;

import android.os.Bundle;
import android.view.View;

import com.jme3.system.AppSettings;

import java.util.logging.Level;
import java.util.logging.LogManager;

import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

public class MainActivity extends AndroidHarness {
    public MainActivity() {
        appClass = Ialon.class.getCanonicalName();
        exitDialogTitle = "Exit?";
        exitDialogMessage = "Are you sure you want to quit?";
        mouseEventsEnabled = true;
        screenShowTitle=false;
        frameRate = 60;
        LogManager.getLogManager().getLogger("").setLevel(Level.INFO);
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Needed to hide android buttons again
        View decorView = getWindow().getDecorView();
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE;
        decorView.setSystemUiVisibility(uiOptions);
    }

    @Override
    protected void onStart() {
        super.onStart();
        AppSettings settings = new AppSettings(true);
        settings.setAudioRenderer(null);
        app.setSettings(settings);
    }

    @Override
    protected void onStop() {
        super.onStop();
        app.stop();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Ialon.setFilePath(getApplicationContext().getFilesDir().toPath());
        setTheme(R.style.Theme_Ialon);
        super.onCreate(savedInstanceState);
    }

}

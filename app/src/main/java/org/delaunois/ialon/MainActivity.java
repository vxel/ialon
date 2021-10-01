package org.delaunois.ialon;

import android.os.Bundle;

import java.util.logging.Level;
import java.util.logging.LogManager;

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
    public void onCreate(Bundle savedInstanceState) {
        Ialon.setFilePath(getApplicationContext().getFilesDir().toPath());
        setTheme(R.style.Theme_Ialon);
        super.onCreate(savedInstanceState);
    }

}

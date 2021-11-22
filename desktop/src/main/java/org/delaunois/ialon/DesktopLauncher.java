package org.delaunois.ialon;

import com.jme3.system.AppSettings;

import static org.delaunois.ialon.Config.FPS_LIMIT;

public class DesktopLauncher {
    public static void main(String[] args) {
        Ialon app = new Ialon();

        AppSettings settings = new AppSettings(false);
        settings.setFrameRate(FPS_LIMIT);
        settings.setResolution(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setUseInput(true);
        settings.setAudioRenderer(null);

        app.setSettings(settings);
        app.setShowSettings(false);
        app.start();
    }
}
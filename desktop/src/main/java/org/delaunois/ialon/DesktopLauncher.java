package org.delaunois.ialon;

import com.jme3.system.AppSettings;

import static org.delaunois.ialon.Config.FPS_LIMIT;

public class DesktopLauncher {
    public static void main(String[] args) {
        Ialon game = new Ialon();

        AppSettings settings = new AppSettings(false);
        settings.setFrameRate(FPS_LIMIT);
        settings.setResolution(Config.SCREEN_WIDTH, Config.SCREEN_HEIGHT);
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setUseInput(true);
        game.setSettings(settings);
        game.setShowSettings(false);
        game.start();
    }
}
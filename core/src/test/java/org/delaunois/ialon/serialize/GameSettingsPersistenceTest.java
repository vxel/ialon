package org.delaunois.ialon.serialize;

import org.delaunois.ialon.IalonConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global game settings must survive a restart — including the ones a launcher has an opinion about.
 *
 * <p>Debug mode is the awkward one, and the reason this class exists : it was written by nobody
 * ({@link GameSettingsDTO} did not carry it) and overwritten by everybody ({@code DesktopLauncher} forced
 * it on, {@code MainActivity} forced it off, both AFTER loading the save). Turning it off in the settings
 * therefore held until the next launch, on every platform.
 *
 * @author Cedric de Launois
 */
class GameSettingsPersistenceTest {

    @Test
    void devModeSurvivesARestart(@TempDir Path save) {
        assertFalse(reloadWithLauncherDefault(save, false, true),
                "debug mode turned OFF must stay off, even under a launcher that defaults it on");
        assertTrue(reloadWithLauncherDefault(save, true, false),
                "debug mode turned ON must stay on, even under a launcher that defaults it off");
    }

    @Test
    void aSaveWrittenBeforeTheSettingExistedKeepsTheLauncherDefault(@TempDir Path save) throws IOException {
        // game.yml as an older build wrote it : every other setting, no devMode key.
        Files.createDirectories(save);
        Files.write(save.resolve(IalonConfigRepository.GAME_SETTINGS_FILENAME),
                "---\ngridRadius: 6\nshowFps: true\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(load(save, true), "the desktop default must survive a save that predates the setting");
        assertFalse(load(save, false), "and so must the Android one");
    }

    /**
     * Plays out one full restart : the player leaves debug mode at {@code chosen} and quits (which is what
     * writes game.yml), then the game starts again under a launcher whose default is {@code launcher}.
     *
     * @return the debug mode the second run ends up with
     */
    private boolean reloadWithLauncherDefault(Path save, boolean chosen, boolean launcher) {
        IalonConfig session = config(save);
        session.setDevMode(chosen);
        IalonConfigRepository.saveGameSettings(session);

        return load(save, launcher);
    }

    /** Starts a run : the launcher's default first, then the save on top of it. */
    private boolean load(Path save, boolean launcherDefault) {
        IalonConfig config = config(save);
        config.setDevMode(launcherDefault);
        IalonConfigRepository.loadConfig(config);
        return config.isDevMode();
    }

    private IalonConfig config(Path save) {
        IalonConfig config = new IalonConfig();
        config.setSavePath(save);
        return config;
    }
}

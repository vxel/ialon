/**
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

package org.delaunois.ialon.serialize;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jme3.app.SimpleApplication;
import com.jme3.math.Quaternion;

import org.delaunois.ialon.IalonConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Builder
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class IalonConfigRepository {

    private static final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    public static final String PLAYER_STATE_FILENAME = "player.yml";
    public static final String GAME_SETTINGS_FILENAME = "game.yml";

    private IalonConfigRepository() {
        // Prevent instanciation
    }

    /**
     * Loads, into the live config, the global game settings (save/game.yml), then the parameters and
     * player state of the world to open (save/worlds/&lt;id&gt;/). Migrates a legacy flat save first, and
     * creates a default world if none exists, so the call is safe on a fresh install.
     */
    public static void loadConfig(IalonConfig config) {
        WorldRepository.migrateLegacySave(config.getSavePath());

        GameSettingsDTO gameSettings = loadGameSettings(config);
        if (gameSettings != null) {
            gameSettings.applyTo(config);
            config.setWorldId(gameSettings.getCurrentWorldId());
        }

        resolveCurrentWorld(config);

        WorldParams worldParams = WorldRepository.loadWorldParams(config.getSavePath(), config.getWorldId());
        if (worldParams != null) {
            worldParams.applyTo(config);
        }

        WorldEditOverlayRepository.load(config.getCurrentWorldPath(), config.getWorldEditOverlay());
        loadPlayerState(config);
    }

    /**
     * Loads only the per-world parameters and player state of the world currently selected in the config
     * (config.getWorldId()), without touching the global game settings. Used when switching worlds at
     * runtime, after the new world id has been set on the config.
     */
    public static void loadWorldState(IalonConfig config) {
        WorldEditOverlayRepository.load(config.getCurrentWorldPath(), config.getWorldEditOverlay());
        WorldParams worldParams = WorldRepository.loadWorldParams(config.getSavePath(), config.getWorldId());
        if (worldParams != null) {
            worldParams.applyTo(config);
        }
        loadPlayerState(config);
    }

    private static void loadPlayerState(IalonConfig config) {
        PlayerStateDTO playerStateDTO = loadPlayerStateDTO(config);
        if (playerStateDTO == null) {
            // No saved player state (newly created world) : spawn the player on this world's terrain.
            // The location is computed eagerly here (not left null) because the chunk pager reads it
            // synchronously when the world states are attached, before PlayerState#initialize runs.
            config.setPlayerLocation(config.computeSpawnLocation());
            config.setPlayerRotation(new Quaternion());
            config.setPlayerYaw(0);
            config.setPlayerPitch(0);
            return;
        }
        if (playerStateDTO.getLocation() != null) {
            // Finite world : bring a position saved far from the origin back into the canonical tile.
            // The terrain is periodic, so this lands on identical ground with no visible jump and
            // keeps coordinates bounded across sessions. No-op for the infinite world.
            config.setPlayerLocation(config.wrapToWorld(playerStateDTO.getLocation()));
        }
        if (playerStateDTO.getRotation() != null && playerStateDTO.getRotation().getRotationColumn(2).isUnitVector()) {
            config.setPlayerRotation(playerStateDTO.getRotation());
        }
        if (playerStateDTO.getSelectedBlockIndex() != null) {
            config.setSelectedBlockIndex(playerStateDTO.getSelectedBlockIndex());
        }
        if (playerStateDTO.getSelectedBlockName() != null) {
            config.setSelectedBlockName(playerStateDTO.getSelectedBlockName());
        }
        config.setPlayerStartFly(playerStateDTO.isFly());
        config.setTime(playerStateDTO.getTime());
        config.setPlayerYaw(playerStateDTO.getYaw());
        config.setPlayerPitch(playerStateDTO.getPitch());
    }

    /**
     * Ensures config.getWorldId() points at an existing world : keeps it if present, else falls back to
     * the first world found, else creates a default world from the (default) config.
     */
    private static void resolveCurrentWorld(IalonConfig config) {
        java.nio.file.Path savePath = config.getSavePath();
        if (WorldRepository.worldExists(savePath, config.getWorldId())) {
            return;
        }
        java.util.List<WorldParams> worlds = WorldRepository.listWorlds(savePath);
        if (!worlds.isEmpty()) {
            config.setWorldId(worlds.get(0).getId());
            return;
        }
        config.setWorldId(IalonConfig.DEFAULT_WORLD_ID);
        config.setWorldName("World 1");
        WorldRepository.createWorld(savePath, new WorldParams(config));
    }

    public static void saveConfig(SimpleApplication app, IalonConfig config) {
        if (app.getCamera() != null) {
            config.setPlayerRotation(app.getCamera().getRotation());
            float[] angles = app.getCamera().getRotation().toAngles(null);
            config.setPlayerPitch(angles[0]);
            config.setPlayerYaw(angles[1]);
        }
        saveConfig(config);
    }

    public static void saveConfig(IalonConfig config) {
        saveGameSettings(config);
        if (!savePlayerStateDTO(new PlayerStateDTO(config), config)) {
            log.error("Could not properly save User Settings");
        }
        WorldEditOverlayRepository.save(config.getCurrentWorldPath(), config.getWorldEditOverlay());
    }

    private static GameSettingsDTO loadGameSettings(IalonConfig config) {
        if (config.getSavePath() == null) {
            return null;
        }
        Path file = Paths.get(config.getSavePath().toAbsolutePath().toString(), GAME_SETTINGS_FILENAME);
        if (Files.notExists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), GameSettingsDTO.class);
        } catch (IOException e) {
            log.error("Unable to read game settings {}: {}", file.toAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Persists only the global game settings (game.yml), leaving the per-world player state untouched.
     * Used by the memory guard to durably record a lowered render distance without overwriting the live
     * player position with a possibly-stale config value (unlike {@link #saveConfig(IalonConfig)}).
     */
    public static void saveGameSettings(IalonConfig config) {
        Path savePath = config.getSavePath();
        if (savePath == null) {
            return;
        }
        try {
            Files.createDirectories(savePath);
            Path file = Paths.get(savePath.toAbsolutePath().toString(), GAME_SETTINGS_FILENAME);
            objectMapper.writeValue(file.toFile(), new GameSettingsDTO(config));
        } catch (IOException e) {
            log.error("Unable to write game settings in {}: {}", savePath.toAbsolutePath(), e.getMessage(), e);
        }
    }

    private static PlayerStateDTO loadPlayerStateDTO(IalonConfig config) {
        Path path = config.getCurrentWorldPath();

        // path doesn't exist
        if (path == null || Files.notExists(path)) {
            log.warn("Unable to load User Settings, file path {} doesn't exist.",
                    path == null ? "<null>" : path.toAbsolutePath());
            return null;
        }

        // path isn't a directory
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Invalid path specified: " + path.toAbsolutePath());
        }

        // player file does not exist
        Path filePath = getPlayerSavePath(config);
        if (Files.notExists(filePath)) {
            if (log.isTraceEnabled()) {
                log.trace("User Settings {} not found in repository", filePath);
            }
            return null;
        }

        return loadPlayerStateFromPath(filePath);
    }

    private static boolean savePlayerStateDTO(PlayerStateDTO playerStateDTO, IalonConfig config) {
        Path path = config.getCurrentWorldPath();
        if (path == null) {
            return false;
        }

        if (Files.notExists(path)) {
            try {
                Files.createDirectories(path);
                log.info("Created directory: {}", path.toAbsolutePath());
            } catch (IOException e) {
                log.error("Error while creating directory {}: {}", path.toAbsolutePath(), e.getMessage(), e);
                return false;
            }
        }

        return writePlayerStateToPath(playerStateDTO, getPlayerSavePath(config));
    }

    private static PlayerStateDTO loadPlayerStateFromPath(Path filePath) {
        if (log.isTraceEnabled()) {
            log.trace("Loading {}", filePath.toAbsolutePath());
        }

        if (!Files.exists(filePath)) {
            log.trace("Skipped loading player state from missing file {}", filePath.toAbsolutePath());
            return null;
        }

        long start = System.nanoTime();

        File file = filePath.toFile();
        try (InputStream in = new FileInputStream(file)) {
            PlayerStateDTO playerStateDTO = read(in);
            if (log.isTraceEnabled()) {
                log.trace("Loading player state took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }

            return playerStateDTO;
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    private static boolean writePlayerStateToPath(PlayerStateDTO playerStateDTO, Path path) {
        log.info("Saving player state to {}", path.toAbsolutePath());

        long start = System.nanoTime();

        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            write(playerStateDTO, fos);
            log.info("Saving player state took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            return true;

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    private static Path getPlayerSavePath(IalonConfig config) {
        return Paths.get(config.getCurrentWorldPath().toAbsolutePath().toString(), PLAYER_STATE_FILENAME);
    }

    private static PlayerStateDTO read(InputStream inputStream) {
        try {
            PlayerStateDTO playerStateDTO = objectMapper.readValue(inputStream, objectMapper.getTypeFactory().constructType(PlayerStateDTO.class));
            if (log.isTraceEnabled()) {
                log.trace("Loaded player state from inputstream.");
            }
            return playerStateDTO;
        } catch (IOException e) {
            log.error("Unable to read inputstream. Error: {}", e.getMessage(), e);
        }

        return new PlayerStateDTO();
    }

    private static void write(PlayerStateDTO playerStateDTO, OutputStream outputStream) {
        try {
            log.info("Saving player preferences");
            objectMapper.writeValue(outputStream, playerStateDTO);
            log.info("Saved player preferences");
        } catch (Exception e) {
            log.error("Unable to write outputstream. Error: {}", e.getMessage(), e);
        }
    }
}

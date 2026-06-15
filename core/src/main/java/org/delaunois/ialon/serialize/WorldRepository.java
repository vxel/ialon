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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import org.delaunois.ialon.IalonConfig;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.extern.slf4j.Slf4j;

/**
 * World-level persistence : the {@code save/worlds/} directory holds one sub-directory per world,
 * each with a {@code world.yml} (its {@link WorldParams}), a {@code player.yml} and its chunk files.
 * This class lists, creates, loads and deletes those world directories, and migrates a legacy flat
 * {@code save/} (single world stored directly under save/) into {@code save/worlds/default/}.
 *
 * @author Cedric de Launois
 */
@Slf4j
public final class WorldRepository {

    public static final String WORLD_FILENAME = "world.yml";

    private static final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    private WorldRepository() {
        // Prevent instanciation
    }

    public static Path worldsDir(Path savePath) {
        return savePath.resolve(IalonConfig.WORLDS_DIR);
    }

    public static Path worldDir(Path savePath, String worldId) {
        return worldsDir(savePath).resolve(worldId);
    }

    private static Path worldFile(Path savePath, String worldId) {
        return worldDir(savePath, worldId).resolve(WORLD_FILENAME);
    }

    /**
     * Lists the worlds present under {@code save/worlds/}, ordered by id. Returns an empty list when no
     * worlds directory exists yet. World directories without a readable world.yml are skipped.
     */
    public static List<WorldParams> listWorlds(Path savePath) {
        Path worlds = worldsDir(savePath);
        if (!Files.isDirectory(worlds)) {
            return new ArrayList<>();
        }
        try (Stream<Path> dirs = Files.list(worlds)) {
            return dirs.filter(Files::isDirectory)
                    .map(dir -> loadWorldParams(savePath, dir.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(WorldParams::getId))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("Unable to list worlds in {}: {}", worlds.toAbsolutePath(), e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public static boolean worldExists(Path savePath, String worldId) {
        return Files.exists(worldFile(savePath, worldId));
    }

    /**
     * Epoch millis of the world's last save : the modification time of its player.yml (rewritten on
     * every save), falling back to world.yml (creation time) for a world never played, and to 0 if
     * neither exists.
     */
    public static long lastModifiedMillis(Path savePath, String worldId) {
        Path player = worldDir(savePath, worldId).resolve(IalonConfigRepository.PLAYER_STATE_FILENAME);
        Path descriptor = worldFile(savePath, worldId);
        Path target = Files.exists(player) ? player : descriptor;
        try {
            return Files.exists(target) ? Files.getLastModifiedTime(target).toMillis() : 0L;
        } catch (IOException e) {
            log.warn("Unable to read save time of world {}: {}", worldId, e.getMessage());
            return 0L;
        }
    }

    public static WorldParams loadWorldParams(Path savePath, String worldId) {
        Path file = worldFile(savePath, worldId);
        if (Files.notExists(file)) {
            return null;
        }
        try {
            return objectMapper.readValue(file.toFile(), WorldParams.class);
        } catch (IOException e) {
            log.error("Unable to read world descriptor {}: {}", file.toAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Creates the world directory and writes its world.yml. No-op on the chunk files / player.yml, which
     * the game creates lazily when the world is first played. Returns the directory of the new world.
     */
    public static Path createWorld(Path savePath, WorldParams params) {
        Path dir = worldDir(savePath, params.getId());
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to create world directory " + dir.toAbsolutePath(), e);
        }
        saveWorldParams(savePath, params);
        log.info("Created world '{}' ({}) at {}", params.getName(), params.getId(), dir.toAbsolutePath());
        return dir;
    }

    public static void saveWorldParams(Path savePath, WorldParams params) {
        Path file = worldFile(savePath, params.getId());
        try {
            Files.createDirectories(file.getParent());
            objectMapper.writeValue(file.toFile(), params);
        } catch (IOException e) {
            log.error("Unable to write world descriptor {}: {}", file.toAbsolutePath(), e.getMessage(), e);
        }
    }

    /**
     * Recursively deletes the world directory (chunks, player.yml and world.yml). Does nothing for an
     * unknown world id.
     */
    public static void deleteWorld(Path savePath, String worldId) {
        Path dir = worldDir(savePath, worldId);
        if (Files.notExists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(WorldRepository::deleteQuietly);
            log.info("Deleted world '{}' at {}", worldId, dir.toAbsolutePath());
        } catch (IOException e) {
            log.error("Unable to delete world {}: {}", dir.toAbsolutePath(), e.getMessage(), e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            log.warn("Unable to delete {}: {}", path.toAbsolutePath(), e.getMessage());
        }
    }

    /**
     * Migrates a legacy single-world save (chunks and player.yml stored directly under {@code save/})
     * into {@code save/worlds/default/}, writing a default {@code world.yml} that reproduces the
     * original generation parameters. Idempotent : a no-op once {@code save/worlds/} exists, and a no-op
     * for a fresh install with nothing to migrate.
     */
    public static void migrateLegacySave(Path savePath) {
        if (savePath == null) {
            return;
        }
        Path worlds = worldsDir(savePath);
        if (Files.exists(worlds)) {
            return; // already migrated (or a fresh multi-world layout)
        }
        if (!hasLegacyContent(savePath)) {
            return; // fresh install : nothing to migrate, the default world will be created on load
        }

        Path defaultDir = worldDir(savePath, IalonConfig.DEFAULT_WORLD_ID);
        try {
            Files.createDirectories(defaultDir);
        } catch (IOException e) {
            log.error("Unable to create default world directory {}: {}", defaultDir.toAbsolutePath(), e.getMessage(), e);
            return;
        }

        List<Path> toMove = new ArrayList<>();
        try (Stream<Path> entries = Files.list(savePath)) {
            entries.filter(Files::isRegularFile)
                    .filter(WorldRepository::isLegacyWorldFile)
                    .forEach(toMove::add);
        } catch (IOException e) {
            log.error("Unable to scan legacy save {}: {}", savePath.toAbsolutePath(), e.getMessage(), e);
            return;
        }

        for (Path src : toMove) {
            Path dst = defaultDir.resolve(src.getFileName());
            try {
                Files.move(src, dst);
            } catch (IOException e) {
                log.error("Unable to move {} to {}: {}", src.toAbsolutePath(), dst.toAbsolutePath(), e.getMessage(), e);
            }
        }

        // Default world descriptor : the no-arg WorldParams defaults reproduce the original world exactly.
        saveWorldParams(savePath, new WorldParams());
        log.info("Migrated legacy save into {} ({} files moved)", defaultDir.toAbsolutePath(), toMove.size());
    }

    private static boolean hasLegacyContent(Path savePath) {
        if (!Files.isDirectory(savePath)) {
            return false;
        }
        try (Stream<Path> entries = Files.list(savePath)) {
            return entries.filter(Files::isRegularFile).anyMatch(WorldRepository::isLegacyWorldFile);
        } catch (IOException e) {
            log.error("Unable to scan save {}: {}", savePath.toAbsolutePath(), e.getMessage(), e);
            return false;
        }
    }

    private static boolean isLegacyWorldFile(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".zblock") || IalonConfigRepository.PLAYER_STATE_FILENAME.equals(name);
    }
}

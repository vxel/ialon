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

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Slf4j
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlayerStateRepository {

    private static final ObjectMapper objectMapper = new ObjectMapper(new YAMLFactory());

    public static final String PLAYER_STATE_FILENAME = "player.yml";

    /**
     * The path to save the player state
     */
    private Path path;

    public PlayerStateDTO load() {
        // path doesn't exist
        if (path == null || Files.notExists(path)) {
            log.warn("Unable to load player state, file path {} doesn't exist.",
                    path == null ? "<null>" : path.toAbsolutePath());
            return null;
        }

        // path isn't a directory
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Invalid path specified: " + path.toAbsolutePath());
        }

        // player file does not exist
        Path filePath = getPath();
        if (Files.notExists(filePath)) {
            if (log.isTraceEnabled()) {
                log.trace("Player state {} not found in repository", filePath);
            }
            return null;
        }

        return loadPlayerStateFromPath(filePath);
    }

    public boolean save(PlayerStateDTO playerStateDTO) {
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

        return writePlayerStateToPath(playerStateDTO, getPath());
    }

    private PlayerStateDTO loadPlayerStateFromPath(Path filePath) {
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

    private boolean writePlayerStateToPath(PlayerStateDTO playerStateDTO, Path path) {
        log.info("Saving player state to {}", path.toAbsolutePath());

        long start = System.nanoTime();

        try (FileOutputStream fos = new FileOutputStream(path.toFile())) {
            write(playerStateDTO, fos);
            log.info("Saving player state took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    public Path getPath() {
        return path != null ? Paths.get(path.toAbsolutePath().toString(), PLAYER_STATE_FILENAME) : null;
    }

    private PlayerStateDTO read(InputStream inputStream) {
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

    private void write(PlayerStateDTO playerStateDTO, OutputStream outputStream) {
        try {
            log.info("Saving player preferences");
            objectMapper.writeValue(outputStream, playerStateDTO);
            log.info("Saved player preferences");
        } catch (Exception e) {
            log.error("Unable to write outputstream. Error: {}", e.getMessage(), e);
        }
    }
}

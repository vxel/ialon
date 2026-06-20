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

package org.delaunois.ialon.serialize;

import org.delaunois.ialon.blocks.WorldEditOverlay;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Loads and saves the per-world {@link WorldEditOverlay} (felled trees + relief height overrides)
 * as a small binary file ({@value #FILENAME}) inside the world directory, next to the chunk files.
 *
 * <p>The format is a tiny self-describing stream : a magic + version header, then the removed-tree
 * keys, then the height overrides. It is deliberately compact (a few longs/floats per edit) and
 * append-free — the whole overlay is rewritten on save, like the chunks are full-saved.
 *
 * @author Cedric de Launois
 */
@Slf4j
public final class WorldEditOverlayRepository {

    public static final String FILENAME = "edits.dat";

    private static final int MAGIC = 0x49414c45; // "IALE"
    // v2 : the relief overrides are now keyed by far-terrain SAMPLE and measured at the sample (v1
    // stored the edited column's height and slammed it onto the nearest sample -> water on slopes, and
    // a failed scan wrote height 0 = deep water). v1 files are intentionally discarded on load.
    private static final int FORMAT_VERSION = 2;

    private WorldEditOverlayRepository() {
    }

    /**
     * Replaces the overlay's content with what is stored in {@code worldPath/edits.dat}. Clears the
     * overlay first, so switching worlds never carries edits over. A missing file is a normal
     * (newly-created or never-edited) world : the overlay is simply left empty.
     */
    public static synchronized void load(Path worldPath, WorldEditOverlay overlay) {
        if (worldPath == null || overlay == null) {
            return;
        }
        overlay.clear();
        Path file = worldPath.resolve(FILENAME);
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != FORMAT_VERSION) {
                log.warn("Ignoring unrecognised world-edit overlay file {}", file);
                return;
            }
            int trees = in.readInt();
            for (int i = 0; i < trees; i++) {
                overlay.removeTree(in.readLong());
            }
            int heights = in.readInt();
            for (int i = 0; i < heights; i++) {
                long key = in.readLong();
                overlay.putHeight(key, in.readFloat());
            }
            overlay.clearDirty(); // freshly loaded == in sync with disk
            log.info("Loaded world-edit overlay : {} felled trees, {} height overrides", trees, heights);
        } catch (IOException e) {
            log.warn("Could not load world-edit overlay {}", file, e);
        }
    }

    /**
     * Writes the overlay to {@code worldPath/edits.dat} only when it has unsaved changes.
     * {@code synchronized} (with {@link #load}) so a live save on the chunk-saver thread and a
     * checkpoint save on the main thread never write the file concurrently and tear it.
     */
    public static synchronized void save(Path worldPath, WorldEditOverlay overlay) {
        if (worldPath == null || overlay == null || !overlay.isDirty()) {
            return;
        }
        Path file = worldPath.resolve(FILENAME);
        try {
            Files.createDirectories(worldPath);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                out.writeInt(MAGIC);
                out.writeInt(FORMAT_VERSION);
                out.writeInt(overlay.getRemovedTrees().size());
                for (long key : overlay.getRemovedTrees()) {
                    out.writeLong(key);
                }
                out.writeInt(overlay.getHeightOverrides().size());
                for (Map.Entry<Long, Float> e : overlay.getHeightOverrides().entrySet()) {
                    out.writeLong(e.getKey());
                    out.writeFloat(e.getValue());
                }
            }
            overlay.clearDirty();
        } catch (IOException e) {
            log.error("Could not save world-edit overlay {}", file, e);
        }
    }
}

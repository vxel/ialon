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

import org.delaunois.ialon.blocks.protobuf.BlocksProtos;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import lombok.extern.slf4j.Slf4j;

/**
 * Global persistence for {@link Creation}s. Unlike worlds ({@link WorldRepository}), creations are NOT
 * scoped to a world — they live in {@code save/creations/} so a creation captured in one world can be
 * placed into any other.
 *
 * <p>Each creation is one {@code <id>.zcreation} file, stored in the SAME compact format as chunks (see
 * {@code ZipFileRepository}) : a ZIP holding a Protobuf {@code ChunkProto} ({@code data} entry : size +
 * row-major block names) plus a small text {@code meta} entry (display name + dimensions). ZIP deflate
 * collapses the long runs of repeated block names, so the file stays small without any hand-rolled
 * encoding. Listing reads only the cheap {@code meta} entry, so the (potentially large) block grid is
 * parsed only when a creation is actually loaded.</p>
 *
 * @author Cedric de Launois
 */
@Slf4j
public final class CreationRepository {

    public static final String CREATIONS_DIR = "creations";
    public static final String EXTENSION = ".zcreation";

    private static final String ENTRY_META = "meta";
    private static final String ENTRY_DATA = "data";
    private static final Pattern NAME_PATTERN = Pattern.compile("Creation (\\d+)");

    private CreationRepository() {
        // Prevent instanciation
    }

    public static Path creationsDir(Path savePath) {
        return savePath.resolve(CREATIONS_DIR);
    }

    private static Path creationFile(Path savePath, String id) {
        return creationsDir(savePath).resolve(id + EXTENSION);
    }

    /** Filesystem path of a creation's preview thumbnail (a sibling PNG of its {@code .zcreation}). */
    public static Path previewPath(Path savePath, String id) {
        return creationsDir(savePath).resolve(id + ".png");
    }

    /** Asset-manager key of a creation's preview, relative to the (FileLocator-registered) save root. */
    public static String previewAssetKey(String id) {
        return CREATIONS_DIR + "/" + id + ".png";
    }

    /**
     * Lists the creations present under {@code save/creations/}, ordered by id, reading only their
     * metadata (no block grid). Returns an empty list when the directory does not exist yet.
     */
    public static List<Creation> listCreations(Path savePath) {
        Path dir = creationsDir(savePath);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(EXTENSION))
                    .map(p -> loadMeta(savePath, stripExtension(p.getFileName().toString())))
                    .filter(java.util.Objects::nonNull)
                    .sorted(Comparator.comparing(Creation::getId))
                    .collect(java.util.stream.Collectors.toList());
        } catch (IOException e) {
            log.error("Unable to list creations in {}: {}", dir.toAbsolutePath(), e.getMessage(), e);
            return new ArrayList<>();
        }
    }

    public static boolean exists(Path savePath, String id) {
        return Files.exists(creationFile(savePath, id));
    }

    /** Loads only a creation's metadata (id, name, dimensions) — {@code blocks} stays {@code null}. */
    public static Creation loadMeta(Path savePath, String id) {
        Path file = creationFile(savePath, id);
        if (Files.notExists(file)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Creation creation = readMeta(zip);
            if (creation != null) {
                creation.setId(id);
            }
            return creation;
        } catch (IOException e) {
            log.error("Unable to read creation meta {}: {}", file.toAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }

    /** Loads a creation fully, including its block grid. */
    public static Creation load(Path savePath, String id) {
        Path file = creationFile(savePath, id);
        if (Files.notExists(file)) {
            return null;
        }
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Creation creation = readMeta(zip);
            if (creation == null) {
                return null;
            }
            creation.setId(id);
            ZipEntry data = zip.getEntry(ENTRY_DATA);
            if (data == null) {
                log.error("Missing data entry in creation {}", file.toAbsolutePath());
                return null;
            }
            try (InputStream in = zip.getInputStream(data)) {
                BlocksProtos.ChunkProto proto = BlocksProtos.ChunkProto.newBuilder().mergeFrom(in).build();
                creation.setBlocks(proto.getBlocksList().toArray(new String[0]));
            }
            return creation;
        } catch (IOException e) {
            log.error("Unable to read creation {}: {}", file.toAbsolutePath(), e.getMessage(), e);
            return null;
        }
    }

    public static void save(Path savePath, Creation creation) {
        Path file = creationFile(savePath, creation.getId());
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            log.error("Unable to create creations directory {}: {}", file.getParent(), e.getMessage(), e);
            return;
        }

        BlocksProtos.ChunkProto proto = BlocksProtos.ChunkProto.newBuilder()
                .addAllSize(Arrays.asList(creation.getSizeX(), creation.getSizeY(), creation.getSizeZ()))
                .addAllBlocks(creation.getBlocks() == null ? new ArrayList<>() : Arrays.asList(creation.getBlocks()))
                .build();

        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(ENTRY_META));
            writeMeta(zip, creation);
            zip.closeEntry();

            zip.putNextEntry(new ZipEntry(ENTRY_DATA));
            proto.writeTo(zip);
            zip.closeEntry();

            log.info("Saved creation '{}' ({}) : {}x{}x{} at {}", creation.getName(), creation.getId(),
                    creation.getSizeX(), creation.getSizeY(), creation.getSizeZ(), file.toAbsolutePath());
        } catch (IOException e) {
            log.error("Unable to write creation {}: {}", file.toAbsolutePath(), e.getMessage(), e);
        }
    }

    public static void delete(Path savePath, String id) {
        Path file = creationFile(savePath, id);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.warn("Unable to delete creation {}: {}", file.toAbsolutePath(), e.getMessage());
        }
    }

    /** Returns "Creation N" where N is the smallest positive integer not already used by a creation. */
    public static String nextCreationName(Path savePath) {
        Set<Integer> used = new HashSet<>();
        for (Creation c : listCreations(savePath)) {
            Matcher m = NAME_PATTERN.matcher(c.getName() == null ? "" : c.getName());
            if (m.matches()) {
                used.add(Integer.parseInt(m.group(1)));
            }
        }
        int n = 1;
        while (used.contains(n)) {
            n++;
        }
        return "Creation " + n;
    }

    /** Derives a filesystem-safe, unique id from a name (e.g. "Creation 1" -> "creation-1"). */
    public static String generateUniqueId(Path savePath, String name) {
        String base = trimDashes(name.toLowerCase(Locale.ENGLISH).replaceAll("[^a-z0-9]+", "-"));
        if (base.isEmpty()) {
            base = "creation";
        }
        if (!exists(savePath, base)) {
            return base;
        }
        int suffix = 2;
        while (exists(savePath, base + "-" + suffix)) {
            suffix++;
        }
        return base + "-" + suffix;
    }

    /** Strips leading and trailing '-' characters (linear scan ; avoids a backtracking-prone trim regex). */
    private static String trimDashes(String s) {
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '-') {
            start++;
        }
        while (end > start && s.charAt(end - 1) == '-') {
            end--;
        }
        return s.substring(start, end);
    }

    // --- Metadata entry : "name\nsizeX sizeY sizeZ" -------------------------------------------------

    private static void writeMeta(OutputStream out, Creation creation) throws IOException {
        String meta = (creation.getName() == null ? "" : creation.getName()) + "\n"
                + creation.getSizeX() + " " + creation.getSizeY() + " " + creation.getSizeZ();
        out.write(meta.getBytes(StandardCharsets.UTF_8));
    }

    private static Creation readMeta(ZipFile zip) throws IOException {
        ZipEntry meta = zip.getEntry(ENTRY_META);
        if (meta == null) {
            return null;
        }
        byte[] bytes;
        try (InputStream in = zip.getInputStream(meta)) {
            bytes = readAll(in);
        }
        String[] lines = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        Creation creation = new Creation();
        creation.setName(lines.length > 0 ? lines[0] : "");
        if (lines.length > 1) {
            String[] dims = lines[1].trim().split("\\s+");
            if (dims.length == 3) {
                creation.setSizeX(parseInt(dims[0]));
                creation.setSizeY(parseInt(dims[1]));
                creation.setSizeZ(parseInt(dims[2]));
            }
        }
        return creation;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int n;
        while ((n = in.read(chunk)) != -1) {
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    private static String stripExtension(String fileName) {
        return fileName.endsWith(EXTENSION) ? fileName.substring(0, fileName.length() - EXTENSION.length()) : fileName;
    }
}

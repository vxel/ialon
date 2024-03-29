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

package org.delaunois.ialon;

import com.google.protobuf.ByteString;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkRepository;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.protobuf.BlocksProtos;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A File repository implementation for loading and storing chunks using the Protocol Buffers method
 * and compressed using ZIP.
 * Each chunk is stored in a separate file.
 *
 * @author Cedric de Launois
 * @author rvandoosselaer
 */
@Slf4j
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ZipFileRepository implements ChunkRepository {

    public static final String EXTENSION = ".zblock";
    public static final String ZIP_ENTRY_NAME = "chunk";

    /**
     * The path to save chunks to and load chunks from.
     */
    private Path path;

    @Override
    public Chunk load(Vec3i location) {
        if (location == null) {
            return null;
        }

        Path chunkPath = getChunkPath(location);
        if (chunkPath == null) {
            return null;
        }

        return loadChunkFromPath(chunkPath);
    }

    public Chunk load(String filename) {
        if (filename == null) {
            return null;
        }

        return load(getChunkPath(filename));
    }

    public Chunk load(Path chunkPath) {
        // path doesn't exist
        if (path == null) {
            log.warn("Unable to load chunk {}, file path is null.", chunkPath.getFileName());
            return null;
        }

        if (Files.notExists(path)) {
            log.warn("Unable to load chunk {}, file path {} doesn't exist.", chunkPath.getFileName(), path.toAbsolutePath());
            return null;
        }

        // path isn't a directory
        if (!Files.isDirectory(path)) {
            throw new IllegalArgumentException("Invalid path specified: " + path.toAbsolutePath());
        }

        // chunk doesn't exist
        if (Files.notExists(chunkPath)) {
            if (log.isTraceEnabled()) {
                log.trace("Chunk {} not found in repository", chunkPath);
            }
            return null;
        }

        return loadChunkFromPath(chunkPath);
    }

    @Override
    public boolean save(Chunk chunk) {
        if (chunk == null) {
            return false;
        }

        return save(chunk, getChunkPath(chunk));
    }

    public boolean save(Chunk chunk, String filename) {
        if (chunk == null || filename == null) {
            return false;
        }

        return save(chunk, getChunkPath(filename + EXTENSION));
    }

    private boolean save(Chunk chunk, Path chunkPath) {
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

        return writeChunkToPath(chunk, chunkPath);
    }

    public Path getChunkPath(@NonNull Chunk chunk) {
        return path != null ? Paths.get(path.toAbsolutePath().toString(), getChunkFilename(chunk)) : null;
    }

    public static String getChunkFilename(@NonNull Chunk chunk) {
        return getChunkFilename(chunk.getLocation());
    }

    private Path getChunkPath(String filename) {
        return path.resolve(filename);
    }

    private Chunk loadChunkFromPath(Path chunkPath) {
        if (log.isTraceEnabled()) {
            log.trace("Loading {}", chunkPath.toAbsolutePath());
        }

        if (!Files.exists(chunkPath)) {
            log.trace("Skipped loading chunk from missing file {}", chunkPath.toAbsolutePath());
            return null;
        }

        long start = System.nanoTime();

        ZipEntry entry;
        try (ZipFile zfile = new ZipFile(chunkPath.toFile())) {
            entry = zfile.getEntry(ZIP_ENTRY_NAME);
            if (entry == null) {
                log.error("Missing entry in file {}", chunkPath.toAbsolutePath());
                return null;
            }

            Chunk chunk = loadChunkFromPath(zfile, entry);
            if (log.isTraceEnabled()) {
                log.trace("Loading {} took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
            return chunk;

        } catch (IOException e) {
            log.error(e.getMessage(), e);
            return null;
        }
    }

    private Chunk loadChunkFromPath(ZipFile zfile, ZipEntry entry) {
        try (InputStream in = zfile.getInputStream(entry)) {
            BlocksProtos.ChunkProto chunkProto = BlocksProtos.ChunkProto.newBuilder()
                    .mergeFrom(in)
                    .build();
            return chunkProtoToChunk(chunkProto);
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
        return null;
    }

    private boolean writeChunkToPath(Chunk chunk, Path chunkPath) {
        if (log.isTraceEnabled()) {
            log.trace("Saving {} to {}", chunk, chunkPath.toAbsolutePath());
        }

        long start = System.nanoTime();
        BlocksProtos.ChunkProto chunkProto = chunkToChunkProto(chunk);
        if (chunkProto == null) {
            if (Files.exists(chunkPath)) {
                // Empty chunk, remove the file
                try {
                    Files.delete(chunkPath);
                    if (log.isTraceEnabled()) {
                        log.trace("Removed empty {} took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                    }
                } catch (IOException e) {
                    log.error(e.getMessage(), e);
                }
            }
            return true;
        }

        try (FileOutputStream fos = new FileOutputStream(chunkPath.toFile());
             ZipOutputStream zipOut = new ZipOutputStream(fos)) {

            ZipEntry zipEntry = new ZipEntry(ZIP_ENTRY_NAME);
            zipOut.putNextEntry(zipEntry);

            chunkProto.writeTo(zipOut);
            if (log.isTraceEnabled()) {
                log.trace("Saving {} took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
            return true;

        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    private Path getChunkPath(@NonNull Vec3i location) {
        return path != null ? Paths.get(path.toAbsolutePath().toString(), getChunkFilename(location)) : null;
    }

    private static String getChunkFilename(@NonNull Vec3i location) {
        return "chunk_" + location.x + "_" + location.y + "_" + location.z + EXTENSION;
    }

    private static Chunk chunkProtoToChunk(@NonNull BlocksProtos.ChunkProto chunkProto) {
        Vec3i location = getVector(chunkProto.getLocationList());
        if (location == null) {
            return null;
        }
        Vec3i size = getVector(chunkProto.getSizeList());
        if (size == null) {
            log.error("Null chunk size");
            return null;
        }

        int expectedSize = size.x * size.y * size.z;

        if (expectedSize != chunkProto.getBlocksCount()) {
            throw new IllegalStateException("Invalid block data specified! Expected " + expectedSize + " blocks, but found " + chunkProto.getBlocksCount() + " blocks.");
        }

        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        short[] blocks = new short[chunkProto.getBlocksList().size()];
        int i = 0;
        for (String blockName : chunkProto.getBlocksList()) {
            Block block = blockRegistry.get(blockName);
            blocks[i] = block != null ? block.getId() : 0;
            i += 1;
        }

        byte[] lightMap = chunkProto.getLightmap().toByteArray();

        Chunk chunk = Chunk.createAt(location);
        chunk.setBlocks(blocks);
        chunk.setLightMap(lightMap);
        chunk.update();

        return chunk;
    }

    private static BlocksProtos.ChunkProto chunkToChunkProto(@NonNull Chunk chunk) {
        Vec3i size = BlocksConfig.getInstance().getChunkSize();
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        short[] blocks = chunk.getBlocks();
        if (blocks == null) {
            return null;
        }

        List<String> blockList = new ArrayList<>(blocks.length);
        for (short block : blocks) {
            blockList.add(block == 0 ? BlockIds.NONE : blockRegistry.get(block).getName());
        }

        return BlocksProtos.ChunkProto.newBuilder()
                // location
                .addLocation(chunk.getLocation().x)
                .addLocation(chunk.getLocation().y)
                .addLocation(chunk.getLocation().z)
                // size
                .addSize(size.x)
                .addSize(size.y)
                .addSize(size.z)
                .addAllBlocks(blockList)
                .setLightmap(ByteString.copyFrom(chunk.getLightMap()))
                .build();
    }

    private static Vec3i getVector(@NonNull List<Integer> integers) {
        if (integers.size() != 3) {
            return null;
        }

        return new Vec3i(integers.get(0), integers.get(1), integers.get(2));
    }

}

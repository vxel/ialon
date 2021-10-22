package org.delaunois.ialon;

import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages the torch and sun lights computation for the blocks.
 * Based on the Fast Flood Fill Lighting article :
 * https://www.seedofandromeda.com/blogs/29-fast-flood-fill-lighting-in-a-blocky-voxel-game-pt-1
 *
 * @author Cedric de Launois
 */
@Slf4j
public class ChunkLightManager {

    @Setter
    private ChunkManager chunkManager;

    private final Set<String> cubeShapes = new HashSet<>();

    public ChunkLightManager(ChunkManager chunkManager) {
        this.chunkManager = chunkManager;
        cubeShapes.add(ShapeIds.CUBE);
        cubeShapes.add(ShapeIds.SQUARE_CUBOID_NINE_TENTHS);
    }

    /**
     * Add a torchlight to the given world location
     * @param location the (world) location of the torch
     * @param intensity the intensity level of the light (integer between 0 and 15)
     * @return the set of chunk locations whose meshes must be updated due to the light propagation
     */
    public Set<Vec3i> addTorchlight(Vector3f location, int intensity) {
        if (log.isDebugEnabled()) {
            log.debug("Adding torchlight at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        LightRunningContext context = new LightRunningContext();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        chunkManager.getChunk(chunkLocation).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            chunk.setTorchlight(blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, intensity);
            context.lightBfsQueue.offer(new LightNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z));
        });

        return updateTorchLight(context);
    }

    /**
     * Remove a torchlight from the given world location
     * @param location the (world) location of the torch
     * @return the set of chunk locations whose meshes must be updated due to the light propagation
     */
    public Set<Vec3i> removeTorchlight(Vector3f location) {
        if (log.isDebugEnabled()) {
            log.debug("Removing torchlight at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        LightRunningContext context = new LightRunningContext();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        chunkManager.getChunk(chunkLocation).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            int intensity = chunk.getTorchlight(blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z);
            context.lightRemovalBfsQueue.offer(new LightRemovalNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, intensity));
            chunk.setTorchlight(blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, 0);
        });

        return updateTorchLight(context);
    }

    /**
     * Restores the sunlight starting at the given world location.
     * To be called when a block is removed at this location.
     * @param location the (world) start location (e.g. the location of the removed block)
     * @return the set of chunk locations whose meshes must be updated due to the light propagation
     */
    public Set<Vec3i> restoreSunlight(Vector3f location) {
        if (log.isDebugEnabled()) {
            log.debug("Restoring sunlight at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        LightRunningContext context = new LightRunningContext();
        Set<Vector3f> neighborBlockLocations = new HashSet<>();
        for (float x = location.x - 1; x <= location.x + 1; x++) {
            for (float y = location.y - 1; y <= location.y + 1; y++) {
                for (float z = location.z - 1; z <= location.z + 1; z++) {
                    neighborBlockLocations.add(new Vector3f(x, y, z));
                }
            }
        }

        neighborBlockLocations.forEach(loc ->
                chunkManager.getChunk(ChunkManager.getChunkLocation(loc)).ifPresent(chunk -> {
                    Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(loc)));
                    context.sunlightBfsQueue.offer(new LightNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z));
                }));

        return updateSunlight(context);
    }

    /**
     * Removes the sunlight at the given world location.
     * To be called when a block is added at this location.
     * @param location the (world) start location (e.g. the location of the added block)
     * @return the set of chunk locations whose meshes must be updated due to the light propagation
     */
    public Set<Vec3i> removeSunlight(Vector3f location) {
        if (log.isDebugEnabled()) {
            log.debug("Removing sunlight at ({}, {}, {}) in chunk {}", location.x, location.y, location.z, this);
        }

        LightRunningContext context = new LightRunningContext();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        chunkManager.getChunk(chunkLocation).ifPresent(chunk -> {
            Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
            int intensity = chunk.getSunlight(blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z);
            context.sunlightRemovalBfsQueue.offer(new LightRemovalNode(chunk, blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, intensity));
            chunk.setSunlight(blockLocationInsideChunk.x, blockLocationInsideChunk.y, blockLocationInsideChunk.z, 0);
        });

        return updateSunlight(context);
    }

    private Set<Vec3i> updateTorchLight(LightRunningContext context) {
        log.debug("Updating torchlight");

        while (!context.lightRemovalBfsQueue.isEmpty()) {
            LightRemovalNode node = context.lightRemovalBfsQueue.poll();
            propagateRemovedTorchlight(node.chunk, node.x - 1, node.y, node.z, node.intensity, context);
            propagateRemovedTorchlight(node.chunk, node.x + 1, node.y, node.z, node.intensity, context);
            propagateRemovedTorchlight(node.chunk, node.x, node.y - 1, node.z, node.intensity, context);
            propagateRemovedTorchlight(node.chunk, node.x, node.y + 1, node.z, node.intensity, context);
            propagateRemovedTorchlight(node.chunk, node.x, node.y, node.z - 1, node.intensity, context);
            propagateRemovedTorchlight(node.chunk, node.x, node.y, node.z + 1, node.intensity, context);
        }

        while (!context.lightBfsQueue.isEmpty()) {
            LightNode node = context.lightBfsQueue.poll();
            int lightLevel = node.chunk.getTorchlight(node.x, node.y, node.z);
            propagateAddedTorchlight(node.chunk, node.x - 1, node.y, node.z, lightLevel, context);
            propagateAddedTorchlight(node.chunk, node.x + 1, node.y, node.z, lightLevel, context);
            propagateAddedTorchlight(node.chunk, node.x, node.y - 1, node.z, lightLevel, context);
            propagateAddedTorchlight(node.chunk, node.x, node.y + 1, node.z, lightLevel, context);
            propagateAddedTorchlight(node.chunk, node.x, node.y, node.z - 1, lightLevel, context);
            propagateAddedTorchlight(node.chunk, node.x, node.y, node.z + 1, lightLevel, context);
        }

        return context.chunkMeshUpdateRequests;
    }

    private Set<Vec3i> updateSunlight(LightRunningContext context) {
        log.debug("Updating sunlight");

        while (!context.sunlightRemovalBfsQueue.isEmpty()) {
            LightRemovalNode node = context.sunlightRemovalBfsQueue.poll();
            if (log.isDebugEnabled()) {
                log.debug("Propagating light removal node({}, {}, {})", node.x, node.y, node.z);
            }
            propagateRemovedSunlight(node.chunk, node.x - 1, node.y, node.z, node.intensity, false, context);
            propagateRemovedSunlight(node.chunk, node.x + 1, node.y, node.z, node.intensity, false, context);
            propagateRemovedSunlight(node.chunk, node.x, node.y - 1, node.z, node.intensity, true, context);
            propagateRemovedSunlight(node.chunk, node.x, node.y + 1, node.z, node.intensity, false, context);
            propagateRemovedSunlight(node.chunk, node.x, node.y, node.z - 1, node.intensity, false, context);
            propagateRemovedSunlight(node.chunk, node.x, node.y, node.z + 1, node.intensity, false, context);
        }

        while (!context.sunlightBfsQueue.isEmpty()) {
            LightNode node = context.sunlightBfsQueue.poll();
            if (log.isDebugEnabled()) {
                log.debug("Propagating light add node({}, {}, {})", node.x, node.y, node.z);
            }
            int lightLevel = node.chunk.getSunlight(node.x, node.y, node.z);
            propagateAddedSunlight(node.chunk ,node.x - 1, node.y, node.z, lightLevel, true, context);
            propagateAddedSunlight(node.chunk, node.x + 1, node.y, node.z, lightLevel, true, context);
            propagateAddedSunlight(node.chunk, node.x, node.y - 1, node.z, lightLevel, false, context);
            propagateAddedSunlight(node.chunk, node.x, node.y + 1, node.z, lightLevel, true, context);
            propagateAddedSunlight(node.chunk, node.x, node.y, node.z - 1, lightLevel, true, context);
            propagateAddedSunlight(node.chunk, node.x, node.y, node.z + 1, lightLevel, true, context);
        }

        return context.chunkMeshUpdateRequests;
    }

    private void propagateAddedTorchlight(Chunk c, int x, int y, int z, int lightLevel, LightRunningContext context) {
        Chunk chunk = c;

        //log.debug("Computing light at ({}, {}, {}) level={}", x, y, z, lightLevel);
        if (isOutsideChunk(x, y, z)) {
            if (c.getChunkResolver() != null) {
                Vec3i location = new Vec3i(x, y, z);
                Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
                chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
                Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
                x = neighbourBlockLocation.x;
                y = neighbourBlockLocation.y;
                z = neighbourBlockLocation.z;
            }
        }

        if (chunk == null) {
            return;
        }

        Block block = chunk.getBlock(x, y, z);
        if (block != null && !block.isTransparent() && cubeShapes.contains(block.getShape())) {
            return;
        }

        int blockLightLevel = chunk.getTorchlight(x, y, z);
        if (blockLightLevel + 2 <= lightLevel) {
            chunk.setTorchlight(x, y, z, lightLevel - 1);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            context.lightBfsQueue.offer(new LightNode(chunk, x, y, z));
        }
    }

    private void propagateRemovedTorchlight(Chunk c, int x, int y, int z, int lightLevel, LightRunningContext context) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z)) {
            if (c.getChunkResolver() != null) {
                Vec3i location = new Vec3i(x, y, z);
                Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
                chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
                Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
                x = neighbourBlockLocation.x;
                y = neighbourBlockLocation.y;
                z = neighbourBlockLocation.z;
            }
        }

        if (chunk == null) {
            return;
        }

        int neighborLevel = chunk.getTorchlight(x, y, z);

        if (neighborLevel != 0 && neighborLevel < lightLevel) {
            // Set its light level
            chunk.setTorchlight(x, y, z, 0);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            context.lightRemovalBfsQueue.offer(new LightRemovalNode(chunk, x, y, z, neighborLevel));

        } else if (neighborLevel >= lightLevel) {
            // Add it to the update queue, so it can propagate to fill in the gaps
            // left behind by this removal. We should update the lightBfsQueue after
            // the lightRemovalBfsQueue is empty.
            context.lightBfsQueue.offer(new LightNode(chunk, x, y, z));
        }
    }

    private void propagateAddedSunlight(Chunk c, int x, int y, int z, int lightLevel, boolean dimLight, LightRunningContext context) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z)) {
            if (c.getChunkResolver() != null) {
                Vec3i location = new Vec3i(x, y, z);
                Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
                chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
                Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
                x = neighbourBlockLocation.x;
                y = neighbourBlockLocation.y;
                z = neighbourBlockLocation.z;
            }
        }

        if (chunk == null) {
            if (log.isDebugEnabled()) {
                log.debug("PAS1 - Chunk is null for light ({}, {}, {})", x, y, z);
            }
            return;
        }

        Block block = chunk.getBlock(x, y, z);
        if (block != null) {
            if (block.isTransparent()) {
                if (log.isDebugEnabled()) {
                    log.debug("PAS2.0 - Dimming light through transparent block at ({}, {}, {})", x, y, z);
                }
                dimLight = true;

            } else if (cubeShapes.contains(block.getShape())) {
                if (log.isDebugEnabled()) {
                    log.debug("PAS2.1 - Light blocked at ({}, {}, {})", x, y, z);
                }
                return;
            }
        }

        if (!dimLight && lightLevel == 15) {
            if (log.isDebugEnabled()) {
                log.debug("PAS3 - Setting light ({}, {}, {}) to {}", x, y, z, lightLevel);
            }

            chunk.setSunlight(x, y, z, lightLevel);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            context.sunlightBfsQueue.offer(new LightNode(chunk, x, y, z));
            return;
        }

        int blockLightLevel = chunk.getSunlight(x, y, z);
        if (blockLightLevel + 2 <= lightLevel) {
            if (log.isDebugEnabled()) {
                log.debug("PAS4 - Setting light ({}, {}, {}) to {}. BL={} LL={}", x, y, z, lightLevel - 1, blockLightLevel, lightLevel);
            }

            chunk.setSunlight(x, y, z, lightLevel - 1);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            context.sunlightBfsQueue.offer(new LightNode(chunk, x, y, z));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("PAS5 - Leaving light ({}, {}, {}) at {}. BL={} LL={}", x, y, z, lightLevel, blockLightLevel, lightLevel);
            }
        }
    }

    private void propagateRemovedSunlight(Chunk c, int x, int y, int z, int lightLevel, boolean dimLight, LightRunningContext context) {
        Chunk chunk = c;

        if (isOutsideChunk(x, y, z)) {
            if (c.getChunkResolver() != null) {
                Vec3i location = new Vec3i(x, y, z);
                Vec3i chunkLocation = calculateNeighbourChunkLocation(c, location);
                chunk = c.getChunkResolver().get(chunkLocation).orElse(null);
                Vec3i neighbourBlockLocation = calculateNeighbourChunkBlockLocation(location);
                x = neighbourBlockLocation.x;
                y = neighbourBlockLocation.y;
                z = neighbourBlockLocation.z;
            }
        }

        if (chunk == null) {
            if (log.isDebugEnabled()) {
                log.debug("PRS1 - Chunk is null for light ({}, {}, {})", x, y, z);
            }
            return;
        }

        int neighborLevel = chunk.getSunlight(x, y, z);

        if ((dimLight && neighborLevel == 15) || (neighborLevel != 0 && neighborLevel < lightLevel)) {
            if (log.isDebugEnabled()) {
                log.debug("PRS2 - Setting light ({}, {}, {}) to {}. NL={} LL={} D={}", x, y, z, 0, neighborLevel, lightLevel, dimLight);
            }
            chunk.setSunlight(x, y, z, 0);
            updateChunkMeshUpdateRequests(chunk, x, y, z, context);
            context.sunlightRemovalBfsQueue.offer(new LightRemovalNode(chunk, x, y, z, neighborLevel));

        } else if (neighborLevel >= lightLevel) {
            if (log.isDebugEnabled()) {
                log.debug("PRS3 - Enqueuing light ({}, {}, {}). NL={} LL={} D={}", x, y, z, neighborLevel, lightLevel, dimLight);
            }
            // Add it to the update queue, so it can propagate to fill in the gaps
            // left behind by this removal. We should update the lightBfsQueue after
            // the lightRemovalBfsQueue is empty.
            context.sunlightBfsQueue.offer(new LightNode(chunk, x, y, z));

        } else {
            if (log.isDebugEnabled()) {
                log.debug("PRS3 - Leaving light ({}, {}, {}) at {}. NL={} LL={} D={}", x, y, z, neighborLevel, neighborLevel, lightLevel, dimLight);
            }
        }
    }

    /**
     * Updates the set of chunks to be updated due to the light propagation.
     * This method adds the current chunks and the neighbour chunks if the light is adjacent
     * to them.
     * @param chunk the chunk where the light is updated
     * @param x the x location of the light
     * @param y the y location of the light
     * @param z the z location of the light
     * @param context the processing context
     */
    private void updateChunkMeshUpdateRequests(Chunk chunk, int x, int y, int z, LightRunningContext context) {
        context.chunkMeshUpdateRequests.add(chunk.getLocation());

        Vec3i size = BlocksConfig.getInstance().getChunkSize();

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        if (x == size.x - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(1, 0, 0));
        }
        if (x == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (y == size.y - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 1, 0));
        }
        if (y == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, -1, 0));
        }
        if (z == size.z - 1) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 0, 1));
        }
        if (z == 0) {
            context.chunkMeshUpdateRequests.add(chunk.getLocation().add(0, 0, -1));
        }

    }

    /**
     * Checks if the given block coordinate is inside the chunk.
     *
     * @param x coordinate of the block in this chunk
     * @param y coordinate of the block in this chunk
     * @param z coordinate of the block in this chunk
     * @return true if the coordinate of the block is inside the chunk, false otherwise.
     */
    private static boolean isOutsideChunk(int x, int y, int z) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        return x < 0 || x >= chunkSize.x || y < 0 || y >= chunkSize.y || z < 0 || z >= chunkSize.z;
    }

    private static Vec3i calculateNeighbourChunkLocation(Chunk chunk, Vec3i blockLocation) {
        Vec3i chunkLocation = new Vec3i(chunk.getLocation());
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();

        if (blockLocation.x < 0) {
            chunkLocation.addLocal(-1, 0, 0);
        }
        if (blockLocation.x >= chunkSize.x) {
            chunkLocation.addLocal(1, 0, 0);
        }
        if (blockLocation.y < 0) {
            chunkLocation.addLocal(0, -1, 0);
        }
        if (blockLocation.y >= chunkSize.y) {
            chunkLocation.addLocal(0, 1, 0);
        }
        if (blockLocation.z < 0) {
            chunkLocation.addLocal(0, 0, -1);
        }
        if (blockLocation.z >= chunkSize.z) {
            chunkLocation.addLocal(0, 0, 1);
        }
        return chunkLocation;
    }

    private static Vec3i calculateNeighbourChunkBlockLocation(Vec3i blockLocation) {
        Vec3i toReturn = new Vec3i(blockLocation);
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        if (blockLocation.x < 0) {
            toReturn.x = chunkSize.x - 1;
        }
        if (blockLocation.x >= chunkSize.x) {
            toReturn.x = 0;
        }
        if (blockLocation.y < 0) {
            toReturn.y = chunkSize.y - 1;
        }
        if (blockLocation.y >= chunkSize.y) {
            toReturn.y = 0;
        }
        if (blockLocation.z < 0) {
            toReturn.z = chunkSize.z - 1;
        }
        if (blockLocation.z >= chunkSize.z) {
            toReturn.z = 0;
        }
        return toReturn;
    }

    private static Vec3i toVec3i(Vector3f location) {
        return new Vec3i((int) Math.floor(location.x), (int) Math.floor(location.y), (int) Math.floor(location.z));
    }

    private static Vector3f getScaledBlockLocation(Vector3f location) {
        return location.mult(1f / BlocksConfig.getInstance().getBlockScale());
    }

    @AllArgsConstructor
    private static class LightNode {
        Chunk chunk;
        int x;
        int y;
        int z;
    }

    @AllArgsConstructor
    private static class LightRemovalNode {
        Chunk chunk;
        int x;
        int y;
        int z;
        int intensity;
    }

    private static class LightRunningContext {
        final Queue<LightNode> lightBfsQueue = new LinkedList<>();
        final Queue<LightRemovalNode> lightRemovalBfsQueue = new LinkedList<>();
        final Queue<LightNode> sunlightBfsQueue = new LinkedList<>();
        final Queue<LightRemovalNode> sunlightRemovalBfsQueue = new LinkedList<>();
        final Set<Vec3i> chunkMeshUpdateRequests = new HashSet<>();
    }
}

package org.delaunois.ialon;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jme3.collision.CollisionResult;
import com.jme3.math.Vector3f;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkCache;
import com.rvandoosselaer.blocks.ChunkGenerator;
import com.rvandoosselaer.blocks.ChunkManagerListener;
import com.rvandoosselaer.blocks.ChunkMeshGenerator;
import com.rvandoosselaer.blocks.ChunkRepository;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.mathd.Vec3i;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;
import static com.rvandoosselaer.blocks.shapes.Liquid.LEVEL_MAX;

@Slf4j
public class ChunkManager {

    private boolean initialized = false;
    private ChunkCache cache;
    private ChunkMeshGenerator meshGenerator;
    private ExecutorService requestExecutor;

    private final int poolSize;
    private final ChunkRepository repository;
    private final ChunkGenerator generator;
    private final List<ChunkManagerListener> listeners = new CopyOnWriteArrayList<>();

    @Getter
    @Setter
    private ChunkLightManager chunkLightManager;

    @Getter
    @Setter
    private ChunkLiquidManager chunkLiquidManager;

    @Builder
    private ChunkManager(ChunkRepository repository, int repositoryPoolSize, ChunkGenerator generator, int poolSize) {
        this.repository = repository;
        this.generator = generator;
        this.poolSize = poolSize;
        this.chunkLightManager = new ChunkLightManager(this);
        this.chunkLiquidManager = new ChunkLiquidManager(this);
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} - initialize", getClass().getSimpleName());
        }

        cache = new ChunkCache();
        meshGenerator = BlocksConfig.getInstance().getChunkMeshGenerator();
        requestExecutor = Executors.newFixedThreadPool(poolSize, new ThreadFactoryBuilder().setNameFormat("chunk-generator-%d").build());
        initialized = true;
    }

    /**
     * Calculate the location of the chunk that contains the given location.
     *
     * @param location the world location
     * @return location of the chunk
     */
    public static Vec3i getChunkLocation(@NonNull Vector3f location) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        Vector3f scaledLocation = getScaledBlockLocation(location);
        // Math.floor() rounds the decimal part down; 4.13 => 4.0, 4.98 => 4.0, -7.82 => -8.0
        // downcasting double to int removes the decimal part
        return new Vec3i((int) Math.floor(scaledLocation.x / chunkSize.x), (int) Math.floor(scaledLocation.y / chunkSize.y), (int) Math.floor(scaledLocation.z / chunkSize.z));
    }

    public static Vec3i getBlockLocation(@NonNull Vector3f location) {
        return toVec3i(getScaledBlockLocation(location));
    }

    public static Vec3i getBlockLocation(@NonNull CollisionResult collisionResult) {
        return toVec3i(getAdjustedContactPoint(collisionResult.getContactPoint(), collisionResult.getContactNormal()));
    }

    public static Vec3i getNeighbourBlockLocation(@NonNull CollisionResult collisionResult) {
        return toVec3i(getNeighbourBlockLocation(collisionResult.getContactPoint(), collisionResult.getContactNormal()));
    }

    public Optional<Chunk> getChunk(Vec3i location) {
        assertInitialized();

        return location == null ? Optional.empty() : cache.get(location);
    }

    public void requestChunks(Collection<Vec3i> locationsToGenerate, Collection<Vec3i> locationsToMesh) {
        assertInitialized();

        Set<Future<Chunk>> results = requestGenerateChunks(locationsToGenerate);
        // We need to wait for all chunks to be generated before starting to mesh them
        // This is required because the mesh of a chunk depends on the blocks of the mesh AND the
        // blocks of its neighbours
        waitForTasks(results);
        if (!locationsToMesh.isEmpty()) {
            results = requestMeshChunks(locationsToMesh);
            waitForTasks(results);
        }
    }

    public Set<Future<Chunk>> requestGenerateChunks(Collection<Vec3i> locations) {
        assertInitialized();

        Set<Future<Chunk>> results = new HashSet<>();
        if (locations.size() == 0)
            return results;

        log.info("Generating chunks for {} locations", locations.size());
        locations.forEach(location ->
                results.add(requestExecutor.submit(() -> generateChunk(location)))
        );
        return results;
    }

    private Chunk generateChunk(Vec3i location) {
        try {
            Chunk chunk = cache.unsafeFastGet(location);
            if (chunk == null) {
                chunk = repository.load(location);
                if (chunk == null) {
                    chunk = generator.generate(location);
                    chunk.update();
                    chunk.setGenerated(true);
                }
                addToCache(chunk);
            }
            triggerListenerChunkFetched(chunk);
            return chunk;
        } catch (Exception e) {
            log.error("Exception while generating chunk", e);
            return null;
        }
    }

    public Set<Future<Chunk>> requestMeshChunks(Collection<Vec3i> locations) {
        return requestMeshChunks(locations, true);
    }

    public void requestOrderedMeshChunks(Collection<Vec3i> locations) {
        Set<Future<Chunk>> results = requestMeshChunks(locations, false);
        results.forEach(result -> {
            try {
                triggerListenerChunkAvailable(result.get());
            } catch (Exception e) {
                log.warn("Got exception while getting task result", e);
            }
        });
    }

    private Set<Future<Chunk>> requestMeshChunks(Collection<Vec3i> locations, boolean triggers) {
        assertInitialized();

        Set<Future<Chunk>> results = new LinkedHashSet<>();
        if (locations.size() == 0) {
            log.warn("No location given for requestMeshChunks");
            return results;
        }

        final int[] saved = {0};
        // First generate partially-filled chunks (usually visible)
        // Next full chunks (usually not visible because underground)
        Collection<Chunk> fullChunks = new LinkedList<>();
        locations.forEach(location -> {
            Chunk chunk = cache.unsafeFastGet(location);
            // All chunks should be loaded into cache, chunk is never null here
            if (chunk.isEmpty()) {
                saved[0]++;
                chunk.setNode(new EmptyNode());
                if (triggers) {
                    triggerListenerChunkAvailable(chunk);
                }

            } else if (chunk.isFull()) {
                Chunk up = cache.unsafeFastGet(location.add(0, 1, 0));
                Chunk down = cache.unsafeFastGet(location.add(0, -1, 0));
                Chunk a = cache.unsafeFastGet(location.add(1, 0, 0));
                Chunk b = cache.unsafeFastGet(location.add(0, 0, 1));
                Chunk c = cache.unsafeFastGet(location.add(-1, 0, 0));
                Chunk d = cache.unsafeFastGet(location.add(0, 0, -1));
                if ((up == null || up.isFull()) && (down == null || down.isFull()) && a.isFull() && b.isFull() && c.isFull() && d.isFull()) {
                    saved[0]++;
                    chunk.setNode(new EmptyNode());
                    if (triggers) {
                        triggerListenerChunkAvailable(chunk);
                    }

                } else {
                    // Defer full chunks
                    fullChunks.add(chunk);
                }

            } else {
                // Generate mesh for partially-filled chunks
                requestMeshChunk(results, chunk, triggers);
            }
        });

        // Generate mesh for full chunks
        fullChunks.forEach(chunk -> requestMeshChunk(results, chunk, triggers));

        log.info("{} locations generated and {} empty locations", locations.size() - saved[0], saved[0]);

        return results;
    }

    private void requestMeshChunk(Set<Future<Chunk>> results, Chunk chunk, boolean triggers) {
        results.add(
                requestExecutor.submit(() -> {
                    meshGenerator.createAndSetNodeAndCollisionMesh(chunk);
                    if (triggers) {
                        triggerListenerChunkAvailable(chunk);
                    }
                    return chunk;
                })
        );
    }

    /**
     * @param location of the chunk
     * @see #removeChunk(Chunk)
     */
    public void removeChunk(Vec3i location) {
        assertInitialized();

        getChunk(location).ifPresent(this::removeChunk);
    }

    public void fastRemoveChunk(Vec3i location) {
        cache.evict(location);
    }

    /**
     * Inform the ChunkManager that you no longer need access to the chunk and it can perform cleanup operations on it.
     *
     * @param chunk the chunk
     */
    public void removeChunk(Chunk chunk) {
        assertInitialized();

        if (chunk != null) {
            cache.evict(chunk.getLocation());
        }
    }

    public Set<Vec3i> addBlock(Vector3f location, Block block) {
        assertInitialized();

        // Preserves the order of the location to generate
        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = cache.unsafeFastGet(chunkLocation);
        if (chunk == null) {
            return chunks;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));

        Block previousBlock = chunk.getBlock(blockLocationInsideChunk);
        if (Objects.equals(previousBlock, block)) {
            // 1. Adding the same block : nothing to do
            log.info("Previous block at location {} was already {}", location, previousBlock);
            return chunks;
        }

        if (previousBlock == null) {
            // 2. Adding a block on empty non-water location : add the block
            chunk.addBlock(blockLocationInsideChunk, block);
            if (WATER_SOURCE.equals(block.getName())) {
                chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);
            }

        } else if (previousBlock.getLiquidLevel() > 0) {
            // 3. Adding block in water
            if (WATER_SOURCE.equals(block.getName())) {
                // 3.1 Adding a source where there is already non-source water
                if (!TypeIds.WATER.equals(previousBlock.getType())) {
                    // 3.1.1 If a non water block exists there, add source water to it
                    String blockName = BlockIds.getName(previousBlock.getType(), previousBlock.getShape(), Block.LIQUID_SOURCE);
                    block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
                    if (block == null) {
                        log.warn("Block {} not found", blockName);
                    }
                }
                chunk.addBlock(blockLocationInsideChunk, block);
                chunkLiquidManager.addSource(chunk, blockLocationInsideChunk);

            } else if (WATER_SOURCE.equals(previousBlock.getName())) {
                // 3.2 Adding a block on a water source removes the source if the block is a cube
                if (ShapeIds.CUBE.equals(block.getShape())) {
                    chunk.addBlock(blockLocationInsideChunk, block);
                    chunkLiquidManager.removeSource(chunk, blockLocationInsideChunk, previousBlock.getLiquidLevel());
                } else {
                    String blockName = BlockIds.getName(block.getType(), block.getShape(), Block.LIQUID_SOURCE);
                    block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
                    if (block == null) {
                        log.warn("Block {} not found", blockName);
                    } else {
                        chunk.addBlock(blockLocationInsideChunk, block);
                    }
                }

            } else if (TypeIds.WATER.equals(previousBlock.getType())) {
                // 3.3 Adding a regular block where there is already some water
                // Optimisation : if adding a block in water, apply directly the water level
                // of the block location
                String blockName = BlockIds.getName(block.getType(), block.getShape(), previousBlock.getLiquidLevel());
                block = BlocksConfig.getInstance().getBlockRegistry().get(blockName);
                if (block == null) {
                    log.warn("Block {} not found", blockName);
                } else if (ShapeIds.CUBE.equals(block.getShape())) {
                    chunk.addBlock(blockLocationInsideChunk, block);
                    chunkLiquidManager.removeSource(location);
                    chunkLiquidManager.flowLiquid(location);
                } else {
                    chunk.addBlock(blockLocationInsideChunk, block);
                }
            } else {
                // There is already a block at this location
                log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
                return chunks;
            }

        } else {
            // 4. Adding block on an existing non-water block is not allowed
            log.info("Existing block {} at location {} prevents adding the new block", previousBlock, location);
            return chunks;
        }

        // When adding a block, redraw chunk of added block first to avoid
        // seeing holes in adjacent chunks when the chunks are added to the world in different
        // frames. This requires the set keeping the order.
        chunks.add(chunk.getLocation());
        Vec3i size = BlocksConfig.getInstance().getChunkSize();

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        if (blockLocationInsideChunk.x == size.x - 1) {
            chunks.add(chunk.getLocation().add(1, 0, 0));
        }
        if (blockLocationInsideChunk.x == 0) {
            chunks.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (blockLocationInsideChunk.y == size.y - 1) {
            chunks.add(chunk.getLocation().add(0, 1, 0));
        }
        if (blockLocationInsideChunk.y == 0) {
            chunks.add(chunk.getLocation().add(0, -1, 0));
        }
        if (blockLocationInsideChunk.z == size.z - 1) {
            chunks.add(chunk.getLocation().add(0, 0, 1));
        }
        if (blockLocationInsideChunk.z == 0) {
            chunks.add(chunk.getLocation().add(0, 0, -1));
        }

        if (chunkLightManager != null) {
            chunks.addAll(chunkLightManager.removeSunlight(location));
            chunks.addAll(chunkLightManager.removeTorchlight(location));
        }

        return chunks;
    }

    public Set<Vec3i> removeBlock(Vector3f location) {
        assertInitialized();

        // Preserves the order of the location to generate
        Set<Vec3i> chunks = new LinkedHashSet<>();
        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        Chunk chunk = cache.unsafeFastGet(chunkLocation);
        if (chunk == null) {
            return chunks;
        }

        Vec3i blockLocationInsideChunk = chunk.toLocalLocation(toVec3i(getScaledBlockLocation(location)));
        Block previousBlock = chunk.removeBlock(blockLocationInsideChunk);
        if (previousBlock == null) {
            log.warn("No block found at location {}", location);
            return chunks;
        }

        int lvl = previousBlock.getLiquidLevel();
        if (lvl > 0) {
            // Optimisation : if removing a block in water, apply directly the water level
            // of the block location
            String shapeId = ShapeIds.LIQUID;

            if (lvl < LEVEL_MAX) {
                // Partial liquid
                shapeId = shapeId + "_" + lvl;
            }

            chunk.addBlock(blockLocationInsideChunk,
                    BlocksConfig.getInstance().getBlockRegistry()
                            .get(BlockIds.getName(TypeIds.WATER, shapeId)));

        } else if (chunkLiquidManager != null) {
            Vector3f above = location.add(0, 1, 0);
            chunkLiquidManager.removeSource(above);
            chunkLiquidManager.flowLiquid(above);
        }

        Vec3i size = BlocksConfig.getInstance().getChunkSize();

        // Request chunk updates of neighbour blocks only if block is at the border of the chunk
        if (blockLocationInsideChunk.x == size.x - 1) {
            chunks.add(chunk.getLocation().add(1, 0, 0));
        }
        if (blockLocationInsideChunk.x == 0) {
            chunks.add(chunk.getLocation().add(-1, 0, 0));
        }
        if (blockLocationInsideChunk.y == size.y - 1) {
            chunks.add(chunk.getLocation().add(0, 1, 0));
        }
        if (blockLocationInsideChunk.y == 0) {
            chunks.add(chunk.getLocation().add(0, -1, 0));
        }
        if (blockLocationInsideChunk.z == size.z - 1) {
            chunks.add(chunk.getLocation().add(0, 0, 1));
        }
        if (blockLocationInsideChunk.z == 0) {
            chunks.add(chunk.getLocation().add(0, 0, -1));
        }

        // When removing a block, redraw chunk of removed block last to avoid
        // seeing holes in adjacent chunks when the chunks are added to the world in different
        // frames. This requires the set keeping the order.
        chunks.add(chunk.getLocation());

        if (chunkLightManager != null) {
            chunks.addAll(chunkLightManager.removeTorchlight(location));
            chunks.addAll(chunkLightManager.restoreSunlight(location));
        }

        return chunks;
    }

    public Set<Vec3i> addTorchlight(Vector3f location, int intensity) {
        return chunkLightManager.addTorchlight(location, intensity);
    }

    public int getSunlightLevel(Vector3f location) {
        assertInitialized();

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return getChunk(chunkLocation).map(chunk -> {
            Vec3i loc = chunk.toLocalLocation(toVec3i(location));
            return chunk.getSunlight(loc.x, loc.y, loc.z);
        }).orElse(15);
    }

    public int getTorchlightLevel(Vector3f location) {
        assertInitialized();

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return getChunk(chunkLocation).map(chunk -> {
            Vec3i loc = chunk.toLocalLocation(toVec3i(location));
            return chunk.getTorchlight(loc.x, loc.y, loc.z);
        }).orElse(0);
    }

    public Optional<Block> getBlock(Vector3f location) {
        assertInitialized();

        if (location == null) {
            return Optional.empty();
        }

        Vec3i chunkLocation = ChunkManager.getChunkLocation(location);
        return getChunk(chunkLocation).map(chunk -> {
            Vec3i localBlockLocation = chunk.toLocalLocation(toVec3i(location));
            return chunk.getBlock(localBlockLocation);
        });
    }

    public Optional<Block> getBlock(CollisionResult collisionResult) {
        assertInitialized();

        if (collisionResult == null) {
            return Optional.empty();
        }

        Vector3f adjustedContactPoint = getAdjustedContactPoint(collisionResult.getContactPoint(), collisionResult.getContactNormal());
        return getBlock(adjustedContactPoint);
    }

    public void update() {
    }

    public void cleanup() {
        assertInitialized();

        if (log.isTraceEnabled()) {
            log.trace("{} - cleanup", getClass().getSimpleName());
        }

        requestExecutor.shutdownNow();
        cache.evictAll();
        initialized = false;
    }

    public void addListener(@NonNull ChunkManagerListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@NonNull ChunkManagerListener listener) {
        listeners.remove(listener);
    }

    private <T> void waitForTasks(Set<Future<T>> tasks) {
        tasks.forEach(result -> {
            try {
                result.get();
            } catch (Exception e) {
                log.warn("Got exception while getting task result", e);
            }
        });
    }

    private static Vec3i toVec3i(Vector3f location) {
        return new Vec3i((int) Math.floor(location.x), (int) Math.floor(location.y), (int) Math.floor(location.z));
    }

    private static Vector3f getScaledBlockLocation(Vector3f location) {
        return location.mult(1f / BlocksConfig.getInstance().getBlockScale());
    }

    private static Vector3f getAdjustedContactPoint(Vector3f contactPoint, Vector3f contactNormal) {
        // add a small offset to the contact point, so we point a bit more 'inward' into the block
        Vector3f adjustedContactPoint = contactPoint.add(contactNormal.negate().multLocal(0.01f));
        return getScaledBlockLocation(adjustedContactPoint);
    }

    private static Vector3f getNeighbourBlockLocation(Vector3f location, Vector3f normal) {
        Vector3f neighbourDirection = normal.mult(0.99f);
        return getScaledBlockLocation(location).add(neighbourDirection);
    }

    private void triggerListenerChunkAvailable(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkAvailable(chunk));
    }

    private void triggerListenerChunkFetched(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkFetched(chunk));
    }

    private void addToCache(Chunk chunk) {
        cache.put(chunk);
        chunk.setChunkResolver(cache);
    }

    private void assertInitialized() {
        if (!initialized) {
            throw new IllegalStateException(getClass().getSimpleName() + " is not initialized.");
        }
    }

}

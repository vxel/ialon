package org.delaunois.ialon.blocks;

import com.jme3.collision.CollisionResult;
import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.generator.EmptyGenerator;

@Slf4j
public class ChunkManager {

    private boolean initialized = false;
    private ChunkCache cache;
    private ChunkMeshGenerator meshGenerator;
    private ExecutorService requestExecutor;
    // Dedicated single-thread executor for interactive edits (add/remove/toggle block). Kept separate
    // from requestExecutor so an edit's mesh regeneration runs immediately instead of queueing behind
    // the (often busy) chunk generation/meshing tasks of the pager — the render thread blocks on the
    // edit's mesh via requestOrderedMeshChunks, so any wait behind paging directly stalls the frame.
    private ExecutorService editExecutor;

    private final int poolSize;
    private final ChunkRepository repository;
    private final ChunkGenerator generator;
    private final List<ChunkManagerListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Set when a chunk generation/meshing task hits an {@link OutOfMemoryError}. Chunk work runs on
     * worker threads, so the error cannot propagate to the main loop : instead we swallow it, drop the
     * offending chunk, and raise this flag. The memory guard (main thread) polls it via
     * {@link #consumeMemoryPressure()} and reacts by lowering the render distance before the heap is
     * exhausted again.
     */
    private final AtomicBoolean memoryPressure = new AtomicBoolean(false);

    /**
     * Locations whose <b>paging</b> mesh task has been submitted and has not finished yet.
     *
     * <p>The pager decides what needs meshing by diffing the grid box against the pages it has
     * <b>attached</b>, so a chunk currently being meshed still looks missing to it. Without this guard, a
     * pager that falls behind on attaching (its per-frame budget saturated, or a frame-rate dip)
     * re-requests every in-flight chunk on the player's next chunk crossing — and the duplicated meshing
     * makes it fall further behind still. That feedback loop is not theoretical : measured with
     * {@code ChunkPagingBenchmark} at 30 fps, it wasted 92% of all meshing work, re-meshed single chunks up
     * to 17 times, and left the whole grid unfilled. Deduplicating keeps a stall degrading gracefully.
     *
     * <p>Edits deliberately bypass this (see the {@code dedupe} flag) : a block the player just placed
     * MUST be re-meshed even over an in-flight paging mesh, or it would not appear.
     */
    private final Set<Vec3i> meshInFlight = ConcurrentHashMap.newKeySet();

    @Builder
    private ChunkManager(ChunkRepository repository, ChunkGenerator generator, int poolSize) {
        this.repository = repository;
        this.generator = generator == null ? new EmptyGenerator() : generator;
        this.poolSize = poolSize;
    }

    public void initialize() {
        if (log.isTraceEnabled()) {
            log.trace("{} - initialize", getClass().getSimpleName());
        }

        cache = new ChunkCache();
        meshGenerator = BlocksConfig.getInstance().getChunkMeshGenerator();
        // Priorities, not just names : see ChunkThreads for why the generation pool must be scheduled
        // behind the render thread and why edits must not be.
        requestExecutor = Executors.newFixedThreadPool(poolSize,
                ChunkThreads.factory("chunk-generator-%d", ChunkThreads.GENERATION_PRIORITY));
        editExecutor = Executors.newSingleThreadExecutor(
                ChunkThreads.factory("chunk-editor-%d", ChunkThreads.EDIT_PRIORITY));
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

    /** The chunk cache, exposed for debug/measurement (cache size and chunk-data memory). */
    public ChunkCache getCache() {
        return cache;
    }

    /**
     * The pager's request : generate the given locations, then mesh the given ones.
     *
     * <p>Returns as soon as the mesh tasks are <b>submitted</b>, not when they complete. Nothing waits on
     * the meshes here — each one notifies the listeners from its worker thread, which is how the pager
     * learns about it. Blocking until the last mesh of the batch was done only delayed the pager's next
     * pass : the player can cross another chunk boundary meanwhile, and the fetch/unfetch/detach queues for
     * that new position could not be computed until the previous batch had fully drained.
     */
    public void requestChunks(Collection<Vec3i> locationsToGenerate, Collection<Vec3i> locationsToMesh) {
        requestChunks(locationsToGenerate, locationsToMesh, location -> true);
    }

    /**
     * Same, with a last-moment veto on the mesh list.
     *
     * <p>{@code stillNeeded} is applied <b>after</b> the generation wait below, not before : the caller
     * chose its locations before that wait, which lasts as long as generating the whole batch takes, and
     * a mesh that completes meanwhile is no longer tracked by {@link #meshInFlight} — it has become the
     * caller's problem (the pager's pages-pending-attach). Re-submitting it duplicates a chunk of work
     * for nothing. The predicate runs on this thread and must be cheap and thread-safe.
     */
    public void requestChunks(Collection<Vec3i> locationsToGenerate, Collection<Vec3i> locationsToMesh,
                              Predicate<Vec3i> stillNeeded) {
        assertInitialized();

        // We need to wait for all chunks to be generated before starting to mesh them
        // This is required because the mesh of a chunk depends on the blocks of the mesh AND the
        // blocks of its neighbours
        waitForTasks(requestGenerateChunks(locationsToGenerate));
        locationsToMesh.removeIf(location -> !stillNeeded.test(location));
        if (!locationsToMesh.isEmpty()) {
            requestMeshChunks(locationsToMesh);
        }
    }

    public Set<Future<Chunk>> requestGenerateChunks(Collection<Vec3i> locations) {
        assertInitialized();

        Set<Future<Chunk>> results = new HashSet<>();
        if (locations.isEmpty())
            return results;

        if (log.isDebugEnabled()) {
            log.debug("Generating chunks for {} locations", locations.size());
        }
        locations.forEach(location ->
                results.add(requestExecutor.submit(() -> generateChunk(location)))
        );
        return results;
    }

    public Chunk generateChunk(Vec3i location) {
        try {
            Chunk chunk = cache.unsafeFastGet(location);
            if (chunk == null) {
                if (repository != null) {
                    chunk = repository.load(location);
                }
                if (chunk == null) {
                    chunk = generator.generate(location);
                    chunk.update();
                    chunk.setGenerated(true);
                }
                addToCache(chunk);
            }
            triggerListenerChunkFetched(chunk);
            return chunk;
        } catch (OutOfMemoryError e) {
            memoryPressure.set(true);
            log.warn("Out of memory while generating chunk at {} - dropping it", location);
            return null;
        } catch (Exception e) {
            log.error("Exception while generating chunk", e);
            return null;
        }
    }

    /**
     * Generates a chunk exactly as the world generator produces it, WITHOUT touching the cache or the
     * repository : the pristine terrain of that location, free of the player edits the cached (and saved)
     * chunk carries. The counterpart of {@link #generateChunk(Vec3i)}, which is a cache/repository lookup
     * first and a generation only as a last resort.
     *
     * <p>Pure generator work, hence thread-safe (the generation pool already runs it concurrently) : a
     * chunk reset generates the whole column on a background thread and only installs the result on the
     * update thread, see {@link #resetChunkData}. Ten chunks of noise are a good fraction of a second on
     * a phone, which is not something a frame can absorb.
     */
    public Chunk generatePristineChunk(Vec3i location) {
        Chunk chunk = generator.generate(location);
        chunk.update();
        chunk.setGenerated(true);
        return chunk;
    }

    /**
     * Installs pristine chunk data (see {@link #generatePristineChunk}) into the cached chunk at the same
     * location, <b>in place</b> : the cached {@link Chunk} instance is kept and only its block and light
     * arrays are swapped.
     *
     * <p>Keeping the instance is the point. The chunk pager, the physics pager, the mesh batchers and the
     * cache's own {@code ChunkResolver} wiring all hold the instance they were handed for a location ; a
     * replacement object would leave them reading the old data until the chunk is paged out and back in.
     * This is exactly how a block edit behaves — it mutates the cached chunk — so the reset is just a
     * wholesale version of one, and must run on the same thread as the other edits (the update thread).
     *
     * @param pristine the freshly generated chunk, whose location says which cached chunk to overwrite
     * @return {@code true} if that chunk was cached and has been reset, {@code false} if it is not paged in
     */
    public boolean resetChunkData(Chunk pristine) {
        assertInitialized();

        Chunk chunk = cache.unsafeFastGet(pristine.getLocation());
        if (chunk == null) {
            return false;
        }
        chunk.setLightMap(pristine.getLightMap());
        chunk.setBlocks(pristine.getBlocks()); // also recomputes the empty / full / opaque flags
        chunk.setGenerated(true);
        chunk.setDirty(false);
        return true;
    }

    /**
     * Returns and clears the "a chunk task ran out of memory" flag (see {@link #memoryPressure}).
     * Polled by the memory guard on the main thread.
     */
    public boolean consumeMemoryPressure() {
        return memoryPressure.getAndSet(false);
    }

    /** Paging path : meshes the given locations, skipping any whose mesh is already in flight. */
    public Set<Future<Chunk>> requestMeshChunks(Collection<Vec3i> locations) {
        return requestMeshChunks(locations, true, false, requestExecutor, true);
    }

    /**
     * Re-meshes chunks WITHOUT blocking the caller : each chunk notifies the listeners from the meshing
     * thread as soon as it is done, so the pager attaches the new mesh on a later frame.
     *
     * <p>This is the variant for machine-driven edits — the liquid simulation above all. A liquid tick
     * can touch dozens of chunks, and {@link #requestOrderedMeshChunks} would make the render thread
     * wait for every one of them (at roughly a millisecond each, on a single thread) : that was tens of
     * milliseconds of frame time on a spreading waterfall. Nothing is waiting on the result here — a
     * flowing liquid appearing a frame or two later is invisible, unlike a block the player just placed.
     *
     * <p>It keeps the dedicated {@code editExecutor} rather than the pager's pool, so liquid re-meshes
     * still never queue behind chunk generation.
     */
    public void requestMeshChunksNoWait(Collection<Vec3i> locations) {
        requestMeshChunksNoWait(locations, true);
    }

    /**
     * Same, with a say on persistence.
     *
     * @param notifyUpdated {@code false} to re-mesh the chunks without flagging them as updated, i.e.
     *                      without the persistence layer saving them (see
     *                      {@link ChunkManagerListener#onChunkUpdated}). Used by the chunk reset, which
     *                      <b>deletes</b> the saved chunks it just reverted : a save queued behind the
     *                      re-mesh would write them straight back.
     */
    public void requestMeshChunksNoWait(Collection<Vec3i> locations, boolean notifyUpdated) {
        requestMeshChunks(locations, true, notifyUpdated, editExecutor, false);
    }

    public void requestOrderedMeshChunks(Collection<Vec3i> locations) {
        // Interactive edits mesh on the dedicated single-thread editExecutor so they never queue behind
        // the pager's generation/meshing work : the render thread blocks on these futures below.
        Set<Future<Chunk>> results = requestMeshChunks(locations, false, false, editExecutor, false);
        results.forEach(result -> {
            try {
                triggerListenerChunkAvailable(result.get());
                triggerListenerChunkUpdated(result.get());
            } catch (ExecutionException e) {
                log.warn("Got exception while getting task result", e);
            } catch (InterruptedException e) {
                log.warn("Interrupted !", e);
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * @param triggers       whether the listeners are notified from the meshing thread at all
     * @param notifyUpdated  whether they also get {@code onChunkUpdated} (which drives persistence), as
     *                       opposed to only {@code onChunkAvailable} (which drives attaching). Paging a
     *                       chunk in is not an update — flagging it as one would re-save the whole world
     *                       as the player walks.
     * @param dedupe         whether locations whose mesh is already in flight are skipped. True for the
     *                       pager (see {@link #meshInFlight}), false for edits and liquid flow, which must
     *                       re-mesh whatever is already running.
     */
    private Set<Future<Chunk>> requestMeshChunks(Collection<Vec3i> locations, boolean triggers,
                                                 boolean notifyUpdated, ExecutorService executor,
                                                 boolean dedupe) {
        assertInitialized();

        Set<Future<Chunk>> results = new LinkedHashSet<>();
        if (locations.isEmpty()) {
            log.warn("No location given for requestMeshChunks");
            return results;
        }

        final int[] saved = {0};
        final int[] skipped = {0};
        // First generate partially-filled chunks (usually visible)
        // Next full chunks (usually not visible because underground)
        Collection<Chunk> fullChunks = new LinkedList<>();
        locations.forEach(location -> {
            Chunk chunk = cache.unsafeFastGet(location);
            if (chunk == null) {
                // Border chunk...
                return;
            }

            if (dedupe && meshInFlight.contains(location)) {
                // Already being meshed : re-submitting would only duplicate the work.
                skipped[0]++;
                return;
            }

            // All chunks should be loaded into cache, chunk is never null here
            if (chunk.isEmpty()) {
                saved[0]++;
                // Nothing to draw, but sight passes straight through : keep it traversable for the
                // cave-culling BFS.
                chunk.setFaceConnectivity(Chunk.CONNECT_ALL);
                chunk.setNode(EmptyNode.withConnectivity(Chunk.CONNECT_ALL));
                triggerListenerChunkAvailable(chunk, triggers);

            } else if (chunk.isFull()) {
                // Skip only a fully-OPAQUE chunk fully enclosed by fully-opaque neighbours : it has no
                // visible face. A full chunk that mixes opaque + transparent blocks (e.g. a lake floor :
                // dirt/rock under water) DOES have visible internal faces and must be meshed, even
                // though it is "full" (no air).
                if (chunk.isFullyOpaque() && isSurroundedByOpaqueChunks(location)) {
                    saved[0]++;
                    // Skipped, but it still blocks sight : a chunk of solid opaque rock connects none
                    // of its faces (no flood-fill needed, that is what fully opaque means). Feeding
                    // that to the visibility graph is what lets the cave-culling BFS stop at the rock
                    // instead of leaking through the whole underground — where nearly every chunk is
                    // skipped, so without it the BFS marks hundreds of buried chunks "visible".
                    chunk.setFaceConnectivity((short) 0);
                    chunk.setNode(EmptyNode.withConnectivity((short) 0));
                    triggerListenerChunkAvailable(chunk, triggers);

                } else {
                    // Defer full chunks
                    fullChunks.add(chunk);
                }

            } else {
                // Generate mesh for partially-filled chunks
                requestMeshChunk(results, chunk, triggers, notifyUpdated, executor, dedupe);
            }
        });

        // Generate mesh for full chunks
        fullChunks.forEach(chunk -> requestMeshChunk(results, chunk, triggers, notifyUpdated, executor, dedupe));

        if (log.isDebugEnabled()) {
            log.debug("{} locations meshed, {} empty/hidden, {} already in flight",
                    locations.size() - saved[0] - skipped[0], saved[0], skipped[0]);
        }

        return results;
    }

    /**
     * Whether the chunk at the given location is fully hidden by its 6 neighbours (so it can be
     * skipped). An OPAQUE full chunk is hidden only by fully-opaque neighbours (a transparent
     * neighbour like water would reveal its faces). A transparent full chunk (e.g. full of water)
     * is hidden by any full neighbour (opaque or water both occlude its faces).
     */
    private boolean isSurroundedByOpaqueChunks(Vec3i location) {
        return occludes(cache.unsafeFastGet(location.add(0, 1, 0)))
                && occludes(cache.unsafeFastGet(location.add(0, -1, 0)))
                && occludes(cache.unsafeFastGet(location.add(1, 0, 0)))
                && occludes(cache.unsafeFastGet(location.add(0, 0, 1)))
                && occludes(cache.unsafeFastGet(location.add(-1, 0, 0)))
                && occludes(cache.unsafeFastGet(location.add(0, 0, -1)));
    }

    private static boolean occludes(Chunk neighbour) {
        // A neighbour hides the shared face only if it is full of opaque blocks. Unloaded neighbours
        // are assumed to occlude (as the previous implementation did for up/down).
        return neighbour == null || neighbour.isFullyOpaque();
    }

    private void requestMeshChunk(Set<Future<Chunk>> results, Chunk chunk, boolean triggers,
                                  boolean notifyUpdated, ExecutorService executor, boolean dedupe) {
        final Vec3i location = chunk.getLocation();
        if (dedupe) {
            meshInFlight.add(location);
        }
        results.add(
                executor.submit(() -> {
                    try {
                        // The player may have walked far enough for this chunk to have been unfetched
                        // while the task sat in the queue. Meshing it would cost a full chunk of work for
                        // geometry nobody will attach, and would starve the chunks that ARE still needed.
                        if (dedupe && cache.unsafeFastGet(location) == null) {
                            return chunk;
                        }
                        meshGenerator.createAndSetNodeAndCollisionMesh(chunk);
                        if (triggers) {
                            triggerListenerChunkAvailable(chunk);
                            if (notifyUpdated) {
                                triggerListenerChunkUpdated(chunk);
                            }
                        }
                    } catch (OutOfMemoryError e) {
                        memoryPressure.set(true);
                        log.warn("Out of memory while meshing chunk at {} - dropping its mesh", location);
                    } finally {
                        // In a finally so a dropped mesh (OOM, stale chunk) cannot leave the location
                        // permanently marked in flight, which would make it a permanent hole in the grid.
                        if (dedupe) {
                            meshInFlight.remove(location);
                        }
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

    public void cleanup() {
        cleanup(0);
    }

    public void cleanup(long timeout) {
        assertInitialized();

        if (log.isTraceEnabled()) {
            log.trace("{} - cleanup", getClass().getSimpleName());
        }

        requestExecutor.shutdown();
        editExecutor.shutdown();
        try {
            if (!requestExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS) && timeout > 0) {
                log.warn("Executors did not terminate properly within {}ms timeout", timeout);
            }
            if (!editExecutor.awaitTermination(timeout, TimeUnit.MILLISECONDS) && timeout > 0) {
                log.warn("Edit executor did not terminate properly within {}ms timeout", timeout);
            }
        } catch (InterruptedException e) {
            log.error("Interrupted while cleaning up {}", getClass().getSimpleName());
            Thread.currentThread().interrupt();
        }
        cache.evictAll();
        meshInFlight.clear();
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
            } catch (ExecutionException e) {
                log.warn("Got exception while getting task result", e);
            } catch (InterruptedException e) {
                log.warn("Interrupted !", e);
                Thread.currentThread().interrupt();
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

    private void triggerListenerChunkAvailable(Chunk chunk, boolean triggers) {
        if (triggers) {
            triggerListenerChunkAvailable(chunk);
        }
    }

    private void triggerListenerChunkAvailable(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkAvailable(chunk));
    }

    private void triggerListenerChunkUpdated(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkUpdated(chunk));
    }

    private void triggerListenerChunkFetched(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkFetched(chunk));
    }

    /** Notifies listeners that a chunk is about to be unfetched (still cached). Called by the pager. */
    void triggerListenerChunkUnfetched(Chunk chunk) {
        listeners.forEach(listener -> listener.onChunkUnfetched(chunk));
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

package org.delaunois.ialon.support;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkManagerListener;
import org.delaunois.ialon.blocks.ChunkPager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manual (interactive) benchmark for chunk <b>streaming</b> — how well the {@link ChunkPager} keeps up
 * with a moving player — NOT part of the automated suite. Run its {@link #main(String[])}.
 *
 * <p>{@link WorldGenBenchmark} measures the raw cost of generating/meshing a static grid.
 * This one measures the dynamic behaviour that the player actually perceives : after the grid is fully
 * loaded, the player walks in a straight line and we watch, frame by frame,
 * <ul>
 *   <li><b>deficit</b> : how many locations of the current grid box have no page attached yet (the
 *       "hole" at the horizon the player sees) ;</li>
 *   <li><b>redundant meshing</b> : how many {@code onChunkAvailable} notifications land for a location
 *       that was already meshed and is still waiting to be attached — pure wasted CPU ;</li>
 *   <li><b>attach backlog</b> : pages meshed and queued but held back by {@code maxUpdatePerFrame}.</li>
 * </ul>
 *
 * <p>Args : {@code [gridRadius] [speedUnitsPerSecond] [seconds] [fps] [maxUpdatePerFrame]}. Since the
 * desktop is several times faster than the target Android device, raising the speed is the simplest way
 * to emulate a slower machine : walking at 3 u/s on a box 6x faster than the phone stresses the chunk
 * pipeline like 18 u/s does there. The frame rate matters independently, because the paging budget is
 * spent <b>per frame</b> : a phone at 30 fps gets half the page ops per second that a 60 fps desktop
 * does. Defaults : radius 8, 18 u/s, 20 s, 60 fps, the configured maxUpdatePerFrame.
 */
public class ChunkPagingBenchmark {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    public static void main(String[] args) throws InterruptedException {
        int gridRadius = args.length > 0 ? Integer.parseInt(args[0]) : 8;
        float speed = args.length > 1 ? Float.parseFloat(args[1]) : 18f;
        float seconds = args.length > 2 ? Float.parseFloat(args[2]) : 20f;
        int fps = args.length > 3 ? Integer.parseInt(args[3]) : 60;
        final long frameNanos = 1_000_000_000L / fps;

        IalonConfig config = new IalonConfig();
        config.setGridRadius(gridRadius);
        config.setSavePath(java.nio.file.Paths.get("build/tmp/paging-bench")); // never load/save real chunks
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        ChunkManager chunkManager = config.getChunkManager();
        chunkManager.initialize();

        Stats stats = new Stats();
        chunkManager.addListener(stats);

        Node chunkNode = new Node("chunks");
        ChunkPager pager = new ChunkPager(chunkNode, chunkManager);
        pager.setGridLowerBounds(config.getGridLowerBound());
        pager.setGridUpperBounds(config.getGridUpperBound());
        pager.setCaveCullingEnabled(config.isChunkCaveCulling());
        pager.initialize();
        pager.setMaxUpdatePerFrame(100);

        int box = config.getGridSize() * config.getGridSize() * config.getGridHeight();
        System.out.printf("Grid radius %d -> box %d x %d x %d = %d chunks, pool size %d%n",
                gridRadius, config.getGridSize(), config.getGridHeight(), config.getGridSize(), box,
                config.getChunkPoolsize());

        // ---- initial load, as WorldBuilderState does (maxUpdatePerFrame = 100) ----
        Vector3f location = new Vector3f(0, 40, 0);
        pager.setLocation(location);
        long start = System.nanoTime();
        while (pager.getAttachedPages().size() < box && System.nanoTime() - start < 120_000_000_000L) {
            pager.update();
            Thread.sleep(4);
        }
        System.out.printf("Initial load : %d/%d pages in %.1f s%n",
                pager.getAttachedPages().size(), box, (System.nanoTime() - start) / 1e9f);

        // ---- steady-state walk, with the in-game per-frame cap ----
        int cap = args.length > 4 ? Integer.parseInt(args[4]) : config.getMaxUpdatePerFrame();
        pager.setMaxUpdatePerFrame(cap);
        System.out.printf("Walking at %.1f u/s, %d fps, maxUpdatePerFrame %d%n", speed, fps, cap);
        stats.reset();
        int frames = (int) (seconds * fps);
        int worstDeficit = 0;
        long deficitSum = 0;
        Vec3i firstCenter = ChunkManager.getChunkLocation(location);

        long next = System.nanoTime();
        for (int f = 0; f < frames; f++) {
            location.x += speed / fps;
            pager.setLocation(location);
            pager.update();

            int deficit = deficit(pager, config);
            worstDeficit = Math.max(worstDeficit, deficit);
            deficitSum += deficit;

            next += frameNanos;
            long sleep = next - System.nanoTime();
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
            }
        }

        Vec3i lastCenter = ChunkManager.getChunkLocation(location);
        int crossings = Math.abs(lastCenter.x - firstCenter.x);
        int notifications = stats.available.get();
        int unique = stats.perLocation.size();
        int redundant = notifications - unique;

        System.out.println();
        System.out.printf("Walked %.0f units at %.1f u/s over %.0f s -> %d chunk crossings%n",
                speed * seconds, speed, seconds, crossings);
        System.out.printf("Generated chunks             : %d%n", stats.fetched.get());
        System.out.printf("Mesh notifications           : %d (%d unique locations)%n", notifications, unique);
        System.out.printf("  redundant re-meshing       : %d (%.0f%% of the work was wasted)%n",
                redundant, 100f * redundant / Math.max(1, notifications));
        System.out.printf("  worst single location      : %d times%n", stats.maxPerLocation());
        System.out.printf("Expected per crossing        : %d locations (1 x %d x %d slab)%n",
                config.getGridSize() * config.getGridHeight(), config.getGridHeight(), config.getGridSize());
        System.out.printf("Actual   per crossing        : %.0f notifications%n",
                (float) notifications / Math.max(1, crossings));
        System.out.println();
        System.out.printf("Grid deficit (unattached box locations) : mean %.0f, worst %d%n",
                (float) deficitSum / frames, worstDeficit);
        System.out.printf("Page ops (attach + detach)             : %d%n", pager.getPageOps());

        pager.cleanup(1000);
        chunkManager.cleanup(1000);
        System.exit(0);
    }

    /** Locations inside the current grid box that have no page attached : the visible hole. */
    private static int deficit(ChunkPager pager, IalonConfig config) {
        Vec3i center = pager.getCenterPage();
        if (center == null) {
            return 0;
        }
        int half = config.getGridRadius();
        int missing = 0;
        Vec3i probe = new Vec3i();
        for (int x = center.x - half; x <= center.x + half; x++) {
            for (int y = 0; y < config.getGridHeight(); y++) {
                for (int z = center.z - half; z <= center.z + half; z++) {
                    probe.set(x, y, z);
                    if (!pager.getAttachedPages().containsKey(probe)) {
                        missing++;
                    }
                }
            }
        }
        return missing;
    }

    private static class Stats implements ChunkManagerListener {

        final AtomicInteger available = new AtomicInteger();
        final AtomicInteger fetched = new AtomicInteger();
        final Map<Vec3i, AtomicInteger> perLocation = new ConcurrentHashMap<>();

        void reset() {
            available.set(0);
            fetched.set(0);
            perLocation.clear();
        }

        int maxPerLocation() {
            int max = 0;
            for (AtomicInteger c : perLocation.values()) {
                max = Math.max(max, c.get());
            }
            return max;
        }

        @Override
        public void onChunkAvailable(Chunk chunk) {
            available.incrementAndGet();
            perLocation.computeIfAbsent(chunk.getLocation().clone(), k -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public void onChunkFetched(Chunk chunk) {
            fetched.incrementAndGet();
        }

        @Override
        public void onChunkUpdated(Chunk chunk) {
            // irrelevant here : paging never flags an update
        }
    }

    private ChunkPagingBenchmark() {
        // benchmark entry point only
    }
}

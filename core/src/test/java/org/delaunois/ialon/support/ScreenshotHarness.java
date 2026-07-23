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

package org.delaunois.ialon.support;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AppState;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.jme3.system.AppSettings;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.ChunkLightManager;
import org.delaunois.ialon.blocks.ChunkLiquidManager;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.state.FarTerrainState;
import org.delaunois.ialon.state.LightingState;
import org.delaunois.ialon.state.RenderReport;
import org.delaunois.ialon.state.SkyState;
import org.delaunois.ialon.state.SunState;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Headless, scriptable screenshot harness : boots the real Ialon rendering stack (block framework,
 * chunk paging, and the actual sky / sun / lighting states), positions a fixed free camera, waits for
 * the chunks around the camera to finish paging in, captures the 3D viewport to a PNG and exits.
 *
 * <p>Unlike the interactive {@code FarTerrainTest}/{@code FarTreeTest}/{@code SceneryTestBuilder}
 * harnesses (which open a window and rely on the fly-cam), this one needs NO interaction : give it a
 * camera pose and an output path, run it, and read the PNG. It deliberately skips {@code PlayerState}
 * (and physics/input), so the camera is whatever you set — no character controller fighting you.
 *
 * <p>It runs both ways :
 * <ul>
 *   <li>from a JUnit test via {@link #capture(Options)} (Gradle forks the test, so it inherits the
 *       display and auto-extracts the LWJGL natives — no classpath/native juggling) ; and</li>
 *   <li>from the command line via {@link #main(String[])} for a quick manual shot.</li>
 * </ul>
 *
 * <p>The screenshot is taken on the main 3D viewport, which renders before the GUI viewport, so it
 * contains only the world — no HUD, no stats, no splash. This mirrors {@code state.WorldPreview}.
 *
 * @author Cedric de Launois
 */
public class ScreenshotHarness extends SimpleApplication {

    private static final Logger log = LoggerFactory.getLogger(ScreenshotHarness.class);

    static {
        // Same allocator the other support harnesses use : avoids the JDK's reflective buffer cleaner
        // (which warns/fails on recent JDKs) since jME's PrimitiveAllocator does no native free.
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION,
                PrimitiveAllocator.class.getName());
    }

    /** Everything the harness needs, with sensible defaults. Mutate fields then pass to {@link #capture}. */
    public static class Options {
        /** The game config (terrain generator, water height, world size, day colours…). */
        public IalonConfig config = new IalonConfig();
        /** Where the PNG is written. Parent dirs are created. */
        public Path output = Paths.get("core/build/screenshots/shot.png");
        /** Camera world position. */
        public Vector3f camLocation = new Vector3f(24, 60, 24);
        /** Point the camera looks at. Ignored when {@link #camRotation} is set. */
        public Vector3f camTarget = new Vector3f(0, 30, 0);
        /**
         * Exact camera orientation, overriding {@link #camTarget}. Set this to replay a pose taken from a
         * device save : {@code player.yml} stores the camera quaternion verbatim (IalonConfigRepository
         * saves {@code camera.getRotation()}), so feeding it back reproduces the reporter's frame without
         * guessing at yaw/pitch conventions — the reason a look-at target cannot do the job.
         */
        public com.jme3.math.Quaternion camRotation = null;
        /** Output resolution. */
        public int width = 1280;
        public int height = 720;
        /** Far frustum — push it out to see the horizon / far terrain. */
        public float frustumFar = 8000f;
        /** Time of day fed to SunState (config.time). HALF_PI ≈ noon ; 0 / PI ≈ horizon ; below 0 ≈ night. */
        public Float time = null;
        /** Attach the distant-horizon {@link FarTerrainState}. */
        public boolean farTerrain = false;
        /**
         * Simple, robust lighting : a directional sun + ambient (as the working FarTree/FarTerrain
         * harnesses use) so the block Lighting material reveals relief. The viewport background is set
         * to {@code config.skyColor}. Turn off only for an unlit/diagnostic look.
         */
        public boolean lighting = true;
        /**
         * Attach the game's real {@code LightingState}/{@code SunState}/{@code SkyState} (animated sky
         * dome + day/night sun). Heavier and assumes more of the bootstrap ; use it specifically to test
         * the sky/sun, not as general lighting. Overrides {@link #lighting} when true.
         */
        public boolean realSky = false;
        /**
         * Optional scene setup, run on the render thread once the framework is up and BEFORE paging
         * settles. Use it to place blocks via {@link #getWorldManager()} (pair with an EmptyGenerator).
         */
        public Consumer<ScreenshotHarness> scene = null;
        /**
         * Optional per-frame hook, run each {@code simpleUpdate} on the render thread. Use it to move the
         * camera/pager (e.g. simulate strafing) — call {@code h.getCamera().setLocation(p)} and
         * {@code h.getChunkPager().setLocation(p)}. Receives the harness and the elapsed milliseconds.
         */
        public java.util.function.BiConsumer<ScreenshotHarness, Long> onUpdate = null;
        /**
         * Run-and-quit mode : if &gt; 0, the harness renders for this long (driving paging and {@link
         * #onUpdate}) and then stops WITHOUT capturing a screenshot. Used for movement/profiling probes.
         */
        public long runMillis = 0;
        /** Install {@code TimingNode}s for root/gui so HitchProfilerState can split the SpatialUpdate step. */
        public boolean timingNodes = false;
        /** Consider paging "settled" once the attached-page count is unchanged for this many frames. */
        public int settleFrames = 20;
        /**
         * Wait at least this long before capturing, even if the page count looks stable early. Chunk
         * generation is async (a background pool), so an empty grid can look "stable" before any chunk
         * has arrived — this floor keeps a fast, lighting-light frame loop from shooting too early.
         */
        public long minWaitMs = 3000;
        /** Capture anyway after this long, settled or not (guards a never-settling / empty grid). */
        public long maxWaitMs = 40_000;
        /** Extra frames rendered after settling, so late meshes/lighting land before the shot. */
        public int extraFrames = 5;
        /**
         * Log a {@link RenderReport} for the captured frame — the per-subtree draw call and triangle
         * breakdown, on the very frame the PNG shows. On by default : it is what makes a screenshot an
         * A/B measurement rather than just a picture, and it costs one frame's queue read.
         *
         * <p>Without it, counting draws means shipping a build to a device and reading logcat, which is
         * far too slow a loop to tune anything by (far-terrain LOD, chunk batching…). The device stays
         * the authority on <i>timings</i> — desktop GL and ANGLE do not cost the same — but draw and
         * triangle counts are a property of the scene, so measuring them here is sound.
         */
        public boolean renderReport = true;
    }

    private final Options options;

    private ChunkPager chunkPager;
    private WorldManager worldManager;

    public ChunkPager getChunkPager() {
        return chunkPager;
    }

    public WorldManager getWorldManager() {
        return worldManager;
    }

    private int lastCount = -1;
    private int stableFrames;
    private int frames;
    private long startMs;
    private boolean capturing;
    // Set once the PNG is on disk. When a RenderReport was requested we do NOT stop the app there : the
    // report needs its own frames to run (attach -> read one frame's queues -> log), and capture() calls
    // stop(true) the moment we signal. lingerFrames is that grace period.
    private volatile boolean shotWritten;
    private int lingerFrames = 4;

    public ScreenshotHarness(Options options) {
        super((AppState[]) null);
        this.options = options;
        if (options.timingNodes) {
            // Must happen before start()/initialize() attaches the nodes to the viewports.
            rootNode = new org.delaunois.ialon.state.TimingNode("Root Node");
            guiNode = new org.delaunois.ialon.state.TimingNode("Gui Node");
        }
    }

    /**
     * Boots the harness, blocks until the PNG is written (or it times out), then stops the app.
     * Safe to call from a JUnit test.
     */
    public static Path capture(Options options) throws InterruptedException {
        ScreenshotHarness app = new ScreenshotHarness(options);

        AppSettings settings = new AppSettings(true);
        settings.setResolution(options.width, options.height);
        settings.setGammaCorrection(true); // sRGB pipeline (matches the game)
        settings.setRenderer(AppSettings.LWJGL_OPENGL32);
        settings.setAudioRenderer(null);
        settings.setVSync(false);
        settings.setTitle("ScreenshotHarness");
        app.setSettings(settings);
        app.setShowSettings(false);
        app.setPauseOnLostFocus(false);

        app.start();
        long deadline = System.currentTimeMillis() + 120_000L;
        synchronized (app.lock) {
            while (!app.finished && System.currentTimeMillis() < deadline) {
                app.lock.wait(2000);
            }
        }
        if (!app.finished) {
            log.warn("Harness timed out before capturing (frames={}, pages={})",
                    app.frames, app.chunkPager == null ? -1 : app.chunkPager.getAttachedPages().size());
        }
        app.stop(true);
        return options.output;
    }

    private final Object lock = new Object();
    private volatile boolean finished;

    @Override
    public void simpleInitApp() {
        if (options.time != null) {
            options.config.setTime(options.time);
        }

        IalonConfig config = options.config;
        config.getInputActionManager().setInputManager(inputManager);

        IalonInitializer.setupLogging();
        IalonInitializer.setupViewPort(this);
        IalonInitializer.setupAtlasManager(this, config);
        IalonInitializer.setupAtlasFont(this, config);
        IalonInitializer.setupBlockFramework(this, config); // creates the CHUNK_NODE and configures BlocksConfig

        // Free, fixed camera. We pass no default app states (super(null)), so there is no FlyCamAppState
        // and the camera is ours to drive directly — nothing fights the pose we set below.
        cam.setFrustumPerspective(45f, (float) options.width / options.height, 0.1f, options.frustumFar);
        cam.setLocation(options.camLocation);
        if (options.camRotation != null) {
            cam.setRotation(options.camRotation);
        } else {
            cam.lookAt(options.camTarget, Vector3f.UNIT_Y);
        }

        // Chunk manager + a hand-built pager centred on the camera (setupChunkPager needs PlayerState).
        ChunkManager chunkManager = config.getChunkManager();
        stateManager.attach(IalonInitializer.setupChunkManager(config)); // initialises the manager

        Node chunkNode = (Node) rootNode.getChild(IalonConfig.CHUNK_NODE_NAME);
        chunkPager = new ChunkPager(chunkNode, chunkManager);
        chunkPager.setLocation(options.camLocation);
        chunkPager.setGridLowerBounds(config.getGridLowerBound());
        chunkPager.setGridUpperBounds(config.getGridUpperBound());
        chunkPager.setMaxUpdatePerFrame(100);
        chunkPager.initialize();

        // Liquid simulation, wired exactly as the game does it : the state owns the manager and ticks it,
        // and the WorldManager edits through that same instance (otherwise a placed source would be
        // queued in one manager and stepped in the other). Lets a harness reproduce a flowing waterfall.
        org.delaunois.ialon.state.ChunkLiquidManagerState liquidState =
                new org.delaunois.ialon.state.ChunkLiquidManagerState(config);
        stateManager.attach(liquidState);
        worldManager = new WorldManager(chunkManager,
                new ChunkLightManager(config), liquidState.getChunkLiquidManager());

        // Same cross-chunk merging as the game (one draw call per mesh type instead of one per chunk),
        // so a screenshot shows what the game actually renders.
        int batchRegion = org.delaunois.ialon.state.ChunkRegionBatchState.regionSizeFrom(config);
        if (batchRegion > 1) {
            stateManager.attach(new org.delaunois.ialon.state.ChunkRegionBatchState(
                    FacesMeshGenerator.CHUNK_MESH_TYPE_GENERIC, batchRegion,
                    config.getChunkBatchRegionsPerFrame(), chunkPager));
        }
        stateManager.attach(new org.delaunois.ialon.state.ChunkMeshBatchState(
                FacesMeshGenerator.CHUNK_MESH_TYPE_WATER_CALM, null, chunkPager));
        stateManager.attach(new org.delaunois.ialon.state.ChunkMeshBatchState(
                FacesMeshGenerator.CHUNK_MESH_TYPE_FIRE, null, chunkPager));
        stateManager.attach(new org.delaunois.ialon.state.ChunkMeshBatchState(
                org.delaunois.ialon.blocks.TypeIds.WATER,
                FacesMeshGenerator.CHUNK_MESH_TYPE_WATER_INSIDE, chunkPager));

        if (options.realSky) {
            // Game's animated sky/sun. Dependency order : Sky needs Sun, Sun needs Lighting. All drive
            // off app.getCamera(). Heavier and assumes more bootstrap — used to inspect the sky itself.
            stateManager.attach(new LightingState(config));
            stateManager.attach(new SunState(config));
            stateManager.attach(new org.delaunois.ialon.state.MoonState(config));
            stateManager.attach(new SkyState(config));
            stateManager.attach(new org.delaunois.ialon.state.StarState(config));
        } else if (options.lighting) {
            // Simple, robust lighting (matches FarTreeTest/FarTerrainTest) : a directional sun reveals
            // the block relief, ambient lifts the shadows. The block material is Lighting-based, so
            // WITHOUT a light the terrain renders black/invisible.
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-0.5f, -0.7f, -0.5f).normalizeLocal());
            sun.setColor(ColorRGBA.White.mult(1.1f));
            rootNode.addLight(sun);
            AmbientLight ambient = new AmbientLight();
            ambient.setColor(ColorRGBA.White.mult(0.5f));
            rootNode.addLight(ambient);
        }
        if (options.farTerrain) {
            stateManager.attach(new FarTerrainState(config));
        }
        viewPort.setBackgroundColor(config.getSkyColor());

        if (options.scene != null) {
            options.scene.accept(this);
        }
        startMs = System.currentTimeMillis();
    }

    @Override
    public void simpleUpdate(float tpf) {
        if (capturing) {
            if (shotWritten && lingerFrames-- <= 0) {
                signalFinished();
            }
            return;
        }
        chunkPager.update();
        frames++;

        long elapsedMs = System.currentTimeMillis() - startMs;
        if (options.onUpdate != null) {
            options.onUpdate.accept(this, elapsedMs);
        }
        if (options.runMillis > 0) {
            // Run-and-quit mode : keep paging + onUpdate going, then stop without capturing.
            if (elapsedMs >= options.runMillis) {
                synchronized (lock) {
                    finished = true;
                    lock.notifyAll();
                }
                capturing = true; // stop further work this run
            }
            return;
        }

        int count = chunkPager.getAttachedPages().size();
        if (count == lastCount) {
            stableFrames++;
        } else {
            stableFrames = 0;
            lastCount = count;
        }

        long elapsed = System.currentTimeMillis() - startMs;
        // Settled = pages present AND count steady for a while AND we waited the minimum (async paging).
        boolean settled = count > 0 && stableFrames >= options.settleFrames && elapsed >= options.minWaitMs;
        if (settled || elapsed >= options.maxWaitMs) {
            capturing = true;
            log.info("Capturing after {} frames / {} ms ({} pages attached, settled={})",
                    frames, elapsed, count, settled);
            // A few extra frames let any just-queued mesh/lighting work flush before we read the buffer.
            enqueue(() -> {
                for (int i = 0; i < options.extraFrames; i++) {
                    chunkPager.update();
                }
            });
            if (options.renderReport) {
                // Requested BEFORE the shot so the report's collectors hook the very frame being captured :
                // the breakdown then describes exactly the image on disk, not a later one.
                RenderReport.request(this);
            }
            viewPort.addProcessor(new ScreenshotProcessor(this, options.output, () -> {
                if (options.renderReport) {
                    shotWritten = true; // let the report finish logging before capture() stops the app
                } else {
                    signalFinished();
                }
            }));
        }
    }

    /** Wakes {@link #capture(Options)}, which then stops the app. */
    private void signalFinished() {
        synchronized (lock) {
            finished = true;
            lock.notifyAll();
        }
    }

    @Override
    public void destroy() {
        if (chunkPager != null) {
            chunkPager.cleanup();
        }
        super.destroy();
    }

    /**
     * Quick manual shot : {@code main [outputPng]} renders the seed-2 island from a high vantage with
     * the distant horizon, then writes the PNG. Run it via the Gradle task : {@code ./gradlew :core:screenshot}.
     */
    /**
     * Drops a water source on the best ledge near the vantage centre : the cell whose ground stands
     * highest above its surroundings, so the water actually cascades instead of pooling on the flat.
     * The drop is what stresses the simulation — a spreading cascade wets far more chunks per tick than
     * a puddle, which is the shape that used to run the frame time away.
     */
    private static void placeWaterfallSource(ScreenshotHarness h) {
        int bestX = 0;
        int bestZ = 0;
        int bestGround = Integer.MIN_VALUE;
        int bestDrop = -1;
        for (int x = 80; x <= 100; x++) {
            for (int z = 80; z <= 100; z++) {
                int ground = groundHeight(h, x, z);
                if (ground == Integer.MIN_VALUE) {
                    continue;
                }
                // How far the ground falls away within a few blocks : the bigger, the better the ledge.
                int drop = 0;
                for (int d = 2; d <= 4; d++) {
                    drop = Math.max(drop, ground - groundHeight(h, x + d, z));
                    drop = Math.max(drop, ground - groundHeight(h, x - d, z));
                    drop = Math.max(drop, ground - groundHeight(h, x, z + d));
                    drop = Math.max(drop, ground - groundHeight(h, x, z - d));
                }
                if (drop > bestDrop || (drop == bestDrop && ground > bestGround)) {
                    bestDrop = drop;
                    bestGround = ground;
                    bestX = x;
                    bestZ = z;
                }
            }
        }
        if (bestDrop < 0) {
            log.warn("Waterfall vantage : no ground found around the vantage centre");
            return;
        }
        h.getWorldManager().addBlock(new Vector3f(bestX, bestGround + 1f, bestZ),
                BlocksConfig.getInstance().getBlockRegistry().get(BlockIds.WATER_SOURCE));
        // Frame the ledge the search actually picked, rather than a pose hard-coded for one terrain.
        h.getCamera().setLocation(new Vector3f(bestX + 20f, bestGround + 14f, bestZ + 34f));
        h.getCamera().lookAt(new Vector3f(bestX, bestGround - 8f, bestZ), Vector3f.UNIT_Y);
        log.info("Waterfall source placed at ({}, {}, {}), ledge drop {} blocks",
                bestX, bestGround + 1, bestZ, bestDrop);
    }

    /** Height of the topmost solid block of a column, or MIN_VALUE if the column is not loaded/empty. */
    private static int groundHeight(ScreenshotHarness h, int x, int z) {
        for (int y = 120; y > 20; y--) {
            if (h.getWorldManager().getBlock(new Vector3f(x, y, z)) != null) {
                return y;
            }
        }
        return Integer.MIN_VALUE;
    }

    public static void main(String[] args) throws InterruptedException {
        Options o = new Options();
        o.output = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("core/build/screenshots/island.png");
        o.config.setTerrainGenerator(new NoiseTerrainGenerator(
                2, o.config.getWaterHeight(), o.config.getMaxy(), o.config.getWorldSize()));
        o.config.setGridRadius(6);
        o.farTerrain = true;
        o.realSky = true;                 // TEMP
        // TEMP vantage selector : main [outputPng] [island|cave|tunnel|up|ocean]
        String shot = args.length > 1 ? args[1] : "island";
        final float tx = -758;   // open sea : floor 23.4, water surface 30.8
        final float tz = -758;
        final float eyeY = 21;   // inside a tunnel just under the seabed
        o.camLocation = new Vector3f(90, 110, 90);
        o.camTarget = new Vector3f(90, 100, -400);
        if ("cave".equals(shot)) {
            // Inside rock BELOW the water level (30) : far terrain hidden.
            o.camLocation = new Vector3f(90, 12, 90);
            o.camTarget = new Vector3f(90, 12, -400);
        } else if ("hillcave".equals(shot)) {
            // Inside rock but ABOVE the water level : under the surface, yet the far terrain must stay --
            // this is the cave-mouth-on-a-mountainside case.
            o.camLocation = new Vector3f(90, 38, 90);
            o.camTarget = new Vector3f(90, 38, -400);
        } else if ("up".equals(shot)) {
            // Same underground spot, looking steeply UP : must still show sky through the top cap.
            o.camLocation = new Vector3f(90, 12, 90);
            o.camTarget = new Vector3f(90, 400, 60);
        } else if ("tunnel".equals(shot)) {
            o.camLocation = new Vector3f(tx, eyeY, tz);
            o.camTarget = new Vector3f(tx, eyeY, tz - 400);
        } else if ("ocean".equals(shot)) {
            o.camLocation = new Vector3f(tx, 32, tz);
            o.camTarget = new Vector3f(tx, 31.8f, tz + 400);
        } else if ("waterfall".equals(shot)) {
            // Looks at a hillside above the shore : onUpdate drops a water source on the crest, which
            // cascades down. Reproduces the case that made the liquid simulation run away (the flow's own
            // BFS refills its queue, so an unbounded tick grows with the wetted area) AND exercises the
            // non-blocking liquid re-mesh end to end : if the async path did not reach the pager, the
            // cascade would simply not appear in the shot.
            o.camLocation = new Vector3f(90, 74, 128);
            o.camTarget = new Vector3f(90, 54, 90);
            if (o.config.getTerrainGenerator() instanceof NoiseTerrainGenerator) {
                NoiseTerrainGenerator gen = (NoiseTerrainGenerator) o.config.getTerrainGenerator();
                // No trees : the point of the shot is the water, and a canopy seen from above hides the
                // whole hillside.
                gen.setTreeMaxProb(0f);
                // Steep relief : a real drop is what makes the water cascade over many chunks instead of
                // pooling, which is the shape that used to run the simulation away.
                gen.setReliefAmplitude(2.5f);
                gen.setReliefFrequency(1.6f);
            }
        } else if ("oceanup".equals(shot)) {
            // Above the open sea, MOVING, then tilted up : the case that catches a merged calm-water
            // geometry whose world bound went stale (it is rebuilt as chunks page in, and jME only
            // refreshes the bound when the geometry is flagged dirty). With a stale bound the whole sea
            // surface is frustum-culled the moment the camera pitches up. See onUpdate below.
            o.camLocation = new Vector3f(tx, 34, tz);
            o.camTarget = new Vector3f(tx, 33.8f, tz + 400);
        } else if ("shore".equals(shot)) {
            // Elevated view DOWN onto a coastline : the one geometry that shows the far relief crossing the
            // water line over its full width, which a grazing view at eye level (ocean) compresses to
            // nothing. This is where farTerrainWaterlineClearance's "grazing band" lives -- the clearance
            // pushes every sample clear of the water plane, so the crossing collapses into a single
            // heightmap cell whose WIDTH is the heightmap step. Any change to HEIGHTMAP_SIZE resizes that
            // band, so this vantage is the A/B that catches it.
            //
            // Steep relief and a HIGH water line on purpose : that floods the valleys, so the frame is full
            // of coast instead of one distant strip, and it makes the crossings as steep as the terrain
            // ever gets -- the worst case for the band, and the shape the artifact was reported on.
            o.config.setWaterHeight(60);
            o.config.setTerrainGenerator(new NoiseTerrainGenerator(
                    2, o.config.getWaterHeight(), o.config.getMaxy(), o.config.getWorldSize()));
            NoiseTerrainGenerator shoreGen = (NoiseTerrainGenerator) o.config.getTerrainGenerator();
            shoreGen.setReliefAmplitude(2.5f);
            shoreGen.setReliefFrequency(1.6f);
            shoreGen.setTreeMaxProb(0f); // a canopy seen from above hides the very shoreline we came for
            o.camLocation = new Vector3f(90, 150, 200);
            o.camTarget = new Vector3f(60, 60, -100);
        } else if ("lake".equals(shot)) {
            // Steep PLUNGING view onto an inland body of water, the geometry a reported far-terrain
            // shoreline artifact shows up in : a dark, smooth, sharply creased fold on the bank, dipping
            // under the water line. The 'shore' vantage misses it -- it looks out to the open sea at a
            // shallow angle, which foreshortens the very band the fold lives in. Same flooded high relief
            // (that is what makes inland lakes at all), but the camera looks DOWN at the near bank.
            o.config.setWaterHeight(60);
            o.config.setTerrainGenerator(new NoiseTerrainGenerator(
                    2, o.config.getWaterHeight(), o.config.getMaxy(), o.config.getWorldSize()));
            NoiseTerrainGenerator lakeGen = (NoiseTerrainGenerator) o.config.getTerrainGenerator();
            lakeGen.setReliefAmplitude(2.5f);
            lakeGen.setReliefFrequency(1.6f);
            lakeGen.setTreeMaxProb(0f);
            o.camLocation = new Vector3f(90, 160, 260);
            o.camTarget = new Vector3f(70, 55, 0);
        } else if ("userlake".equals(shot)) {
            // Byte-for-byte replay of the device world and pose a far-terrain shoreline artifact was
            // reported from (local/debug/world.yml + player.yml). Every synthetic vantage failed to show
            // the fold, which is exactly why this one is transcribed rather than invented : the defect
            // depends on the relief AT the coast, so it needs the reporter's seed, water line and angle.
            o.config.setSeed(7443);
            o.config.setWorldSizeChunks(256);
            o.config.setFiniteWorld(true);
            o.config.setWaterHeight(40.630997f);
            o.config.setReliefAmplitude(2.5f);
            o.config.setReliefFrequency(2.0f);
            o.config.setTreeDensity(0.7f);
            o.config.setForestPatchSize(1.0f);
            o.config.setTerrainGenerator(new NoiseTerrainGenerator(
                    o.config.getSeed(), o.config.getWaterHeight(), o.config.getMaxy(), o.config.getWorldSize()));
            NoiseTerrainGenerator userGen = (NoiseTerrainGenerator) o.config.getTerrainGenerator();
            userGen.setReliefAmplitude(o.config.getReliefAmplitude());
            userGen.setReliefFrequency(o.config.getReliefFrequency());
            o.camLocation = new Vector3f(-53.644833f, 99.578f, 1083.5784f);
            // The saved camera quaternion, applied as-is (see Options.camRotation).
            o.camRotation = new com.jme3.math.Quaternion(0.19156869f, 0.020605633f, -0.0040228036f, 0.98125464f);
        } else if ("strata".equals(shot)) {
            // A mountain seen from just off its flank : the vantage for the surface strata (the rock and
            // snow bands, their slope-driven crags and the tree line). The peak to fly to is given as
            // "main out.png strata <x> <z>" -- the generator's own lines are a function of the column, so
            // a warm equatorial peak (bare rock, thin cap) and a polar one (snow down onto the grass) are
            // two different coordinates of the SAME world, not two configurations.
            // A 4th argument sets the relief amplitude (the in-game slider) : a high relief is the case
            // where the slope-driven bare rock is most visible, and the altitude fade of the soil most
            // load-bearing -- without it a 2.5-relief massif was one grey wall from the sea to the snow.
            float px = args.length > 2 ? Float.parseFloat(args[2]) : -440;
            float pz = args.length > 3 ? Float.parseFloat(args[3]) : 448;
            if (args.length > 4 && o.config.getTerrainGenerator() instanceof NoiseTerrainGenerator) {
                ((NoiseTerrainGenerator) o.config.getTerrainGenerator())
                        .setReliefAmplitude(Float.parseFloat(args[4]));
            }
            // A 5th argument is the horizontal stand-off : a tall massif needs a wide one to fit the whole
            // flank (sea -> tree line -> rock -> snow) in the frame, at the price of showing it as far
            // terrain rather than voxels. The camera is lifted above the ground it actually stands on.
            float dist = args.length > 5 ? Float.parseFloat(args[5]) : 105;
            float ph = o.config.getTerrainGenerator().getHeight(new Vector3f(px, 0, pz));
            float d = dist / 1.4142f; // same stand-off on each axis
            float camGround = o.config.getTerrainGenerator().getHeight(new Vector3f(px + d, 0, pz + d));
            o.camLocation = new Vector3f(px + d, Math.max(camGround + 25, ph * 0.55f), pz + d);
            o.camTarget = new Vector3f(px, ph * 0.55f, pz);
        } else if ("underwater".equals(shot)) {
            // Just BELOW the open-sea surface (30.8), looking up : shows the underside of the calm-water
            // surface (Snell window + refracted sun sparkle) -- the side served by the back faces.
            o.camLocation = new Vector3f(tx, 28.5f, tz);
            o.camTarget = new Vector3f(tx, 40f, tz + 40);
        }
        final boolean[] dug = {false};
        o.onUpdate = (h, ms) -> {
            if ("waterfall".equals(shot)) {
                if (dug[0] || ms < 3000) {
                    return; // let the chunks page in first
                }
                dug[0] = true;
                placeWaterfallSource(h);
                return;
            }
            if ("oceanup".equals(shot)) {
                // Walk north for the first 3 s (so the pager attaches/detaches chunks and the calm-water
                // merge is rebuilt several times), then pitch the camera up and hold : the water surface
                // must still be there.
                float t = Math.min(ms, 3000L) / 1000f;
                Vector3f p = new Vector3f(tx, 34, tz + t * 20f);
                h.getCamera().setLocation(p);
                h.getChunkPager().setLocation(p);
                h.getCamera().lookAt(new Vector3f(p.x, ms > 3000 ? 120 : 33.8f, p.z + 400), Vector3f.UNIT_Y);
                return;
            }
            if (dug[0] || ms < 2500 || !"tunnel".equals(shot)) {
                return;
            }
            dug[0] = true;
            // A 3x3 gallery running past the loaded-grid edge (gridRadius 6 * chunkSize 16 = 96), so the
            // sightline leaves the voxel region -- what a long tunnel/cave does in play.
            Vector3f p = new Vector3f();
            for (int d = -4; d < 130; d++) {
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        h.getWorldManager().removeBlock(p.set(tx + dx, eyeY + dy, tz - d));
                    }
                }
            }
        };
        o.minWaitMs = 6000;
        o.maxWaitMs = 60_000;
        if ("waterfall".equals(shot)) {
            // The source lands at 3 s : leave the cascade time to run down the slope before the shot.
            o.minWaitMs = 12_000;
        }
        Path png = capture(o);
        log.info("Wrote {}", png.toAbsolutePath());
        System.exit(0);
    }
}

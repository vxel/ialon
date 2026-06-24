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
import org.delaunois.ialon.blocks.ChunkLightManager;
import org.delaunois.ialon.blocks.ChunkLiquidManager;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkPager;
import org.delaunois.ialon.blocks.WorldManager;
import org.delaunois.ialon.blocks.generator.NoiseTerrainGenerator;
import org.delaunois.ialon.state.FarTerrainState;
import org.delaunois.ialon.state.LightingState;
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
        /** Point the camera looks at. */
        public Vector3f camTarget = new Vector3f(0, 30, 0);
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
        cam.lookAt(options.camTarget, Vector3f.UNIT_Y);

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

        worldManager = new WorldManager(chunkManager,
                new ChunkLightManager(config), new ChunkLiquidManager(config));

        if (options.realSky) {
            // Game's animated sky/sun. Dependency order : Sky needs Sun, Sun needs Lighting. All drive
            // off app.getCamera(). Heavier and assumes more bootstrap — used to inspect the sky itself.
            stateManager.attach(new LightingState(config));
            stateManager.attach(new SunState(config));
            stateManager.attach(new SkyState(config));
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
            viewPort.addProcessor(new ScreenshotProcessor(this, options.output, () -> {
                synchronized (lock) {
                    finished = true;
                    lock.notifyAll();
                }
            }));
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
    public static void main(String[] args) throws InterruptedException {
        Options o = new Options();
        o.output = args.length > 0
                ? Paths.get(args[0])
                : Paths.get("core/build/screenshots/island.png");
        o.config.setTerrainGenerator(new NoiseTerrainGenerator(
                2, o.config.getWaterHeight(), o.config.getMaxy(), o.config.getWorldSize()));
        o.config.setGridRadius(6);
        o.farTerrain = true;
        o.camLocation = new Vector3f(90, 110, 90);
        o.camTarget = new Vector3f(0, 30, 0);
        o.maxWaitMs = 60_000;
        Path png = capture(o);
        log.info("Wrote {}", png.toAbsolutePath());
        System.exit(0);
    }
}

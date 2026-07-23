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

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.Statistics;
import com.jme3.renderer.ViewPort;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.BlocksConfig;
import com.jme3.renderer.queue.GeometryList;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.texture.FrameBuffer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Attributes the frame's draw calls to their source in the scene graph.
 *
 * <p>The engine {@link Statistics} tell us <em>how many</em> objects were drawn ; they never say
 * <em>which</em>. This hooks a one-shot {@link SceneProcessor} into every viewport and, in
 * {@link SceneProcessor#postQueue}, reads the {@link RenderQueue} the renderer is about to flush —
 * so what it reports is not an estimate of visibility but the exact list of geometries drawn, after
 * cull hints, frustum culling and the chunk pager's cave-culling have had their say.
 *
 * <p>Geometries are grouped by their subtree (chunk mesh type, sky, far terrain, each GUI panel...),
 * front-face-culled "inside" clones of water and lava counted apart, so a "192 draw calls" number
 * becomes a list of who owes what. One caveat when comparing with the engine counters printed
 * alongside : a geometry rendered with a multi-pass technique counts once here and once per pass
 * there, so a gap between the two totals means multi-pass materials, not a miscount.
 *
 * <p>Triggered by F7 ({@link IalonDebugState}, desktop devMode) or periodically by
 * {@link PeriodicState} when {@code -Dialon.renderreport=<seconds>} is set — the Android route,
 * where there is no keyboard.
 *
 * @author Cedric de Launois
 */
@Slf4j
public final class RenderReport {

    private static final RenderQueue.Bucket[] BUCKETS = {
            RenderQueue.Bucket.Opaque,
            RenderQueue.Bucket.Sky,
            RenderQueue.Bucket.Transparent,
            RenderQueue.Bucket.Translucent,
            RenderQueue.Bucket.Gui
    };

    private RenderReport() {
        // utility
    }

    /**
     * Captures the next frame's render queues and logs the breakdown once they are in. Repeated calls
     * while a capture is pending are ignored.
     */
    public static void request(Application app) {
        if (app.getStateManager().getState(CaptureState.class) == null) {
            app.getStateManager().attach(new CaptureState());
        }
    }

    /**
     * Collects one viewport's about-to-be-drawn geometries. Reading the queue in
     * {@link #postQueue(RenderQueue)} is the only place where the renderer's own verdict is visible :
     * before it, culling has not run ; after it, the queues are cleared.
     */
    private static class Collector implements SceneProcessor {

        private final String viewPortName;
        private final Map<String, Bucket> buckets = new LinkedHashMap<>();
        private boolean initialized = false;
        private boolean captured = false;

        // Where the chunk draws sit, counted in Chebyshev chunk rings around the camera (ring r holds the
        // 8r columns whose square shell is r chunks out -- the same square metric the pager uses for its
        // grid). Chunk meshes dominate the frame's draw count, and the choice of HOW to cut them turns
        // entirely on this distribution : batching the outer rings is a wholly different proposition from
        // batching the near field, which is where cave culling pays and where block edits land.
        private final int[] chunkRings = new int[MAX_RING + 1];
        private Camera camera;

        Collector(String viewPortName) {
            this.viewPortName = viewPortName;
        }

        @Override
        public void postQueue(RenderQueue rq) {
            if (captured) {
                return;
            }
            captured = true;
            for (RenderQueue.Bucket bucket : BUCKETS) {
                GeometryList list = rq.getList(bucket);
                if (list == null) {
                    continue;
                }
                for (int i = 0; i < list.size(); i++) {
                    add(list.get(i), bucket);
                }
            }
        }

        private void add(Geometry geom, RenderQueue.Bucket bucket) {
            Bucket b = buckets.computeIfAbsent(label(geom, bucket), Bucket::new);
            b.draws++;
            b.triangles += geom.getMesh() == null ? 0 : geom.getMesh().getTriangleCount();
            b.vertices += geom.getMesh() == null ? 0 : geom.getMesh().getVertexCount();
            if (camera != null && CHUNK_GROUP.equals(group(geom))) {
                chunkRings[Math.min(chunkRing(geom, camera), MAX_RING)]++;
            }
        }

        @Override
        public void initialize(RenderManager rm, ViewPort vp) {
            initialized = true;
            this.camera = vp.getCamera();
        }

        @Override
        public boolean isInitialized() {
            return initialized;
        }

        @Override
        public void reshape(ViewPort vp, int w, int h) {
            // nothing to do
        }

        @Override
        public void preFrame(float tpf) {
            // nothing to do
        }

        @Override
        public void postFrame(FrameBuffer out) {
            // nothing to do
        }

        @Override
        public void cleanup() {
            // nothing to do
        }

        @Override
        public void setProfiler(AppProfiler profiler) {
            // nothing to do
        }
    }

    /**
     * Drives one capture : attaches a {@link Collector} to every enabled viewport, waits for the frame
     * to be rendered, logs the aggregated breakdown and removes everything it installed.
     */
    private static class CaptureState extends BaseAppState {

        private final List<ViewPort> viewPorts = new ArrayList<>();
        private final List<Collector> collectors = new ArrayList<>();

        @Override
        protected void initialize(Application app) {
            RenderManager rm = app.getRenderManager();
            // The engine counters we print for cross-check are only updated when statistics are on.
            app.getRenderer().getStatistics().setEnabled(true);
            hook(rm.getPreViews());
            hook(rm.getMainViews());
            hook(rm.getPostViews());
        }

        private void hook(List<ViewPort> views) {
            for (ViewPort vp : views) {
                if (!vp.isEnabled()) {
                    continue;
                }
                Collector collector = new Collector(vp.getName());
                vp.addProcessor(collector);
                viewPorts.add(vp);
                collectors.add(collector);
            }
        }

        @Override
        public void update(float tpf) {
            for (Collector collector : collectors) {
                if (!collector.captured) {
                    return; // the frame we hooked has not been rendered yet
                }
            }
            log.info(report(getApplication(), collectors));
            getStateManager().detach(this);
        }

        @Override
        protected void cleanup(Application app) {
            for (int i = 0; i < viewPorts.size(); i++) {
                viewPorts.get(i).removeProcessor(collectors.get(i));
            }
            viewPorts.clear();
            collectors.clear();
        }

        @Override
        protected void onEnable() {
            // nothing to do
        }

        @Override
        protected void onDisable() {
            // nothing to do
        }
    }

    private static String report(Application app, List<Collector> collectors) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(String.format(Locale.US, "%n=== RENDER REPORT ===%n"));
        int grandTotal = 0;
        for (Collector collector : collectors) {
            int draws = 0;
            int tris = 0;
            for (Bucket b : collector.buckets.values()) {
                draws += b.draws;
                tris += b.triangles;
            }
            grandTotal += draws;
            sb.append(String.format(Locale.US, "%n[%s] %d draws, %d triangles%n",
                    collector.viewPortName, draws, tris));
            List<Bucket> sorted = new ArrayList<>(collector.buckets.values());
            sorted.sort(Comparator.comparingInt((Bucket b) -> -b.draws));
            for (Bucket b : sorted) {
                sb.append(String.format(Locale.US, "    %4d draws  %8d tris  %8d verts  %s%n",
                        b.draws, b.triangles, b.vertices, b.name));
            }
        }
        appendChunkRings(sb, collectors);
        sb.append(String.format(Locale.US, "%nDraw calls (all viewports) : %d", grandTotal));
        appendEngineCounters(sb, app.getRenderer().getStatistics());
        sb.append(String.format(Locale.US, "%n====================="));
        return sb.toString();
    }

    private static void appendEngineCounters(StringBuilder sb, Statistics stats) {
        if (stats == null || stats.getLabels() == null) {
            return;
        }
        String[] labels = stats.getLabels();
        int[] data = new int[labels.length];
        stats.getData(data);
        sb.append(String.format(Locale.US,
                "%nEngine counters (previous frame) : %d objects, %d triangles, %d shader switches, %d texture binds",
                stat(labels, data, "Objects"), stat(labels, data, "Triangles"),
                stat(labels, data, "Shaders (F)"), stat(labels, data, "Textures (F)")));
    }

    /**
     * The bucket a geometry falls into : the subtree it hangs from, its own name (the chunk mesh type,
     * for chunk geometries), the render bucket, and whether it is one of the front-face-culled "inside"
     * clones of water and lava — those cost a full extra draw call each, even when the camera is
     * nowhere near the liquid.
     */
    /** Subtree name the chunk pager gives its pages ; see {@link #group}. */
    private static final String CHUNK_GROUP = "chunk-node";
    /** Rings past this are folded into the last bucket. gridRadiusMax is 15, so 16 is never reached. */
    private static final int MAX_RING = 16;

    /**
     * Chebyshev distance, in chunks, from the camera's column to the drawn chunk's column. Chebyshev
     * (not Euclidean) because the loaded region is a SQUARE of chunks : ring r is exactly the shell the
     * pager would add when the grid radius goes from r-1 to r, so the histogram reads directly as
     * "what would batching / dropping the outer rings buy".
     */
    private static int chunkRing(Geometry geom, Camera camera) {
        Vec3i size = BlocksConfig.getInstance().getChunkSize();
        if (size == null || size.x <= 0 || size.z <= 0) {
            return 0;
        }
        Vector3f g = geom.getWorldTranslation();
        Vector3f c = camera.getLocation();
        int dx = Math.abs(Math.floorDiv((int) Math.floor(g.x), size.x) - Math.floorDiv((int) Math.floor(c.x), size.x));
        int dz = Math.abs(Math.floorDiv((int) Math.floor(g.z), size.z) - Math.floorDiv((int) Math.floor(c.z), size.z));
        return Math.max(dx, dz);
    }

    /**
     * Appends the chunk draws' radial histogram, with a running total so the "what if the outer rings
     * were merged / dropped" question can be read straight off the last column.
     */
    private static void appendChunkRings(StringBuilder sb, List<Collector> collectors) {
        int[] rings = new int[MAX_RING + 1];
        int total = 0;
        for (Collector collector : collectors) {
            for (int r = 0; r <= MAX_RING; r++) {
                rings[r] += collector.chunkRings[r];
                total += collector.chunkRings[r];
            }
        }
        if (total == 0) {
            return;
        }
        sb.append(String.format(Locale.US, "%n%nChunk draws by ring (Chebyshev, chunks from the camera) : %d total%n",
                total));
        int cumulative = 0;
        for (int r = 0; r <= MAX_RING; r++) {
            if (rings[r] == 0) {
                continue;
            }
            cumulative += rings[r];
            sb.append(String.format(Locale.US, "    ring %-2d : %4d draws   (%4d cumulative, %2d%% ; %d beyond)%n",
                    r, rings[r], cumulative, Math.round(100f * cumulative / total), total - cumulative));
        }
    }

    private static String label(Geometry geom, RenderQueue.Bucket bucket) {
        String detail = nameOf(geom);
        Material mat = geom.getMaterial();
        if (mat != null && mat.getAdditionalRenderState().getFaceCullMode() == RenderState.FaceCullMode.Front) {
            detail += " (inside)";
        }
        return group(geom) + " / " + detail + " [" + bucket + "]";
    }

    /**
     * The name of the highest-but-one ancestor : the direct child of the scene root, which is the level
     * at which each subsystem owns its subtree (chunk-node, sky, FarTerrain, and one node per GUI
     * panel). Folds the hundreds of "Chunk - (x,y,z)" nodes into a single line.
     */
    private static String group(Geometry geom) {
        Spatial spatial = geom;
        while (spatial.getParent() != null && spatial.getParent().getParent() != null) {
            spatial = spatial.getParent();
        }
        return spatial == geom ? "(scene root)" : nameOf(spatial);
    }

    private static String nameOf(Spatial spatial) {
        String name = spatial.getName();
        return name == null || name.isEmpty() ? spatial.getClass().getSimpleName() : name;
    }

    private static int stat(String[] labels, int[] data, String name) {
        for (int i = 0; i < labels.length; i++) {
            if (name.equals(labels[i])) {
                return data[i];
            }
        }
        return -1;
    }

    /**
     * Logs the breakdown every {@code periodSeconds}. Attached by {@code Ialon} when
     * {@code -Dialon.renderreport=<seconds>} is set — the way to get the report on Android, where the
     * F7 trigger of {@link IalonDebugState} is out of reach (and devMode may be off).
     */
    public static class PeriodicState extends BaseAppState {

        private final float periodSeconds;
        private float elapsed = 0;

        public PeriodicState(float periodSeconds) {
            this.periodSeconds = periodSeconds;
        }

        @Override
        public void update(float tpf) {
            elapsed += tpf;
            if (elapsed >= periodSeconds) {
                elapsed = 0;
                RenderReport.request(getApplication());
            }
        }

        @Override
        protected void initialize(Application app) {
            // nothing to set up
        }

        @Override
        protected void cleanup(Application app) {
            // nothing to tear down
        }

        @Override
        protected void onEnable() {
            // nothing to do
        }

        @Override
        protected void onDisable() {
            // nothing to do
        }
    }

    /** Mutable accumulator for one label. */
    private static final class Bucket {
        private final String name;
        private int draws;
        private int triangles;
        private int vertices;

        private Bucket(String name) {
            this.name = name;
        }
    }
}

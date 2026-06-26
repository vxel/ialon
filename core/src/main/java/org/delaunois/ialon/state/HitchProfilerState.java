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
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.profile.AppProfiler;
import com.jme3.profile.AppStep;
import com.jme3.profile.SpStep;
import com.jme3.profile.VpStep;
import com.jme3.renderer.Statistics;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.blocks.ChunkPager;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.extern.slf4j.Slf4j;

/**
 * Pinpoints "hitches" (micro freezes). Installs itself as the jME {@link AppProfiler}, times every
 * {@link AppStep} segment of each frame, and — when a frame's full wall-clock <em>period</em> exceeds a
 * threshold — logs ONE line splitting that period into <b>work</b> (BeginFrame…last render step, the part
 * we can attribute to phases) and <b>gap</b> (buffer swap + vsync/fps-cap idle + GPU-driver stalls + OS
 * scheduling), plus GC activity and chunk-page churn :
 *
 * <pre>HITCH 52.0ms | work 3.4 (state 0.2 / spatial 3.0 / render 0.2) | gap 48.6 (swap/vsync/driver/OS) | top=SpatialUpdate 3.0ms | GC +1 (40ms) | chunkNode 567 (net +0) | pageOps +0</pre>
 *
 * Reading it :
 * <ul>
 *   <li><b>work</b> dominates ⇒ main-thread cost. Then look at the phase : <b>render</b> ⇒ GPU/VBO
 *       upload ; <b>spatial</b> ⇒ scene-graph update — {@code rootNode}/{@code guiNode}
 *       updateLogicalState (every Control runs here, e.g. the far-terrain LOD control) +
 *       updateGeometricState (bounding-volume refresh) ; <b>state</b> ⇒ an AppState's update.</li>
 *   <li><b>gap</b> dominates ⇒ the freeze is NOT in our code : buffer swap / vsync / GPU-driver stall /
 *       OS scheduling. If the <b>GC</b> column is also non-zero, it was a collection pause.</li>
 *   <li><b>top</b> names the single costliest AppStep ; <b>pageOps</b> &gt; 0 ⇒ chunks (re)meshed that
 *       frame even if the net child count is unchanged.</li>
 * </ul>
 * The steady path does no logging and no allocation (just {@code nanoTime} + array adds + a couple of
 * MXBean reads), so it is safe to leave on.
 *
 * <p>Enable on desktop by passing {@code -Dialon.hitch=<ms>} — the threshold is the wall-clock frame
 * time above which a frame is logged (≈12–15 ms catches dropped frames at a 120 fps cap). It is attached
 * in dev mode; the property's presence is what {@link org.delaunois.ialon.Ialon} keys off to attach it.
 *
 * @author Cedric de Launois
 */
@Slf4j
public class HitchProfilerState extends BaseAppState implements AppProfiler {

    private static final AppStep[] STEPS = AppStep.values();
    private static final double MS = 1_000_000.0;

    private final long thresholdNanos;

    private final long[] bucket = new long[STEPS.length];
    private long lastNs;
    private long beginNs = -1;
    private AppStep lastStep;

    private final List<GarbageCollectorMXBean> gcBeans = initGcBeans();
    private long prevGcCount = -1;
    private long prevGcTime;

    private Node chunkNode;
    private int prevChunkChildren = -1;

    // Present only when the app installed TimingNodes (hitch profiler enabled) : lets us split the
    // lumped SpatialUpdate step into world (rootNode) vs HUD (guiNode), each logical vs geometric.
    private TimingNode timingRoot;
    private TimingNode timingGui;

    private ChunkPager chunkPager;
    private long prevPageOps = -1;

    // Per-AppState (and render-substep) timing within a frame : jME calls appSubStep(name) before each
    // state's update/render, so the delta between consecutive calls is the previous substep's cost.
    // The map is reused and its values reset each frame, so the steady path allocates nothing after the
    // first frame (the state set is stable). Lets a 'state'-bound hitch name the exact AppState.
    private final Map<String, long[]> subAccum = new HashMap<>();
    private String lastSub;
    private long lastSubNs;

    public HitchProfilerState(long thresholdMs) {
        this.thresholdNanos = thresholdMs * 1_000_000L;
    }

    /**
     * The {@code java.lang.management} package is absent on Android (ART) : resolving it throws
     * {@link NoClassDefFoundError}. Guard it so the profiler still works on-device (only the GC column
     * is unavailable there) instead of crashing the GL thread.
     */
    private static List<GarbageCollectorMXBean> initGcBeans() {
        try {
            return ManagementFactory.getGarbageCollectorMXBeans();
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    protected void initialize(Application app) {
        if (app instanceof SimpleApplication) {
            SimpleApplication sa = (SimpleApplication) app;
            Spatial cn = sa.getRootNode().getChild(IalonConfig.CHUNK_NODE_NAME);
            if (cn instanceof Node) {
                chunkNode = (Node) cn;
            }
            if (sa.getRootNode() instanceof TimingNode) {
                timingRoot = (TimingNode) sa.getRootNode();
            }
            if (sa.getGuiNode() instanceof TimingNode) {
                timingGui = (TimingNode) sa.getGuiNode();
            }
        }
        ChunkPagerState pagerState = getStateManager().getState(ChunkPagerState.class);
        if (pagerState != null) {
            chunkPager = pagerState.getChunkPager();
        }
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    @Override
    protected void onEnable() {
        prevGcCount = -1;
        prevChunkChildren = -1;
        prevPageOps = -1;
        lastStep = null;
        beginNs = -1;
        getApplication().setAppProfiler(this);
        // Turn on the engine draw-call/triangle counters so the HITCH line's draws=/tris= are populated
        // (they are off by default on a build without StatsAppState, e.g. mobile).
        try {
            getApplication().getRenderer().getStatistics().setEnabled(true);
        } catch (Throwable ignore) {
            // statistics unavailable : draws=/tris= stay -1
        }
        log.warn("HitchProfiler ON — logging frames whose work exceeds {} ms", thresholdNanos / 1_000_000L);
    }

    @Override
    protected void onDisable() {
        if (getApplication().getAppProfiler() == this) {
            getApplication().setAppProfiler(null);
        }
    }

    @Override
    public void appStep(AppStep step) {
        long now = System.nanoTime();
        if (step == AppStep.BeginFrame) {
            // New frame : finalise the previous one (whose full wall-clock period we can now measure as
            // now - beginNs) and reset.
            if (beginNs >= 0) {
                finalizeFrame(now);
            }
            Arrays.fill(bucket, 0L);
            for (long[] v : subAccum.values()) {
                v[0] = 0;
            }
            lastSub = null;
            beginNs = now;
            lastNs = now;
            lastStep = step;
            return;
        }
        if (lastStep != null) {
            bucket[lastStep.ordinal()] += now - lastNs;
        }
        // Neither renderManager.render() (RenderMainViewPorts) nor rootNode.updateLogicalState()
        // (SpatialUpdate) is bracketed by appSubStep, so without this their time would be dumped on
        // whatever AppState was marked last — making sub= falsely accuse e.g. ChunkLiquidManagerState
        // for a rootLog/render cost. Drop the open substep at these boundaries so sub= reflects only
        // genuine per-AppState update cost (and render/spatial time stays in their own buckets).
        if (step == AppStep.RenderMainViewPorts || step == AppStep.SpatialUpdate) {
            lastSub = null;
        }
        lastNs = now;
        lastStep = step;
    }

    private void finalizeFrame(long nowBegin) {
        // work  = time inside BeginFrame..last render step (what we can attribute to phases).
        // period= full wall-clock frame time (this BeginFrame - previous BeginFrame).
        // gap   = period - work = buffer swap + vsync/fps-cap idle + GPU-driver stalls + OS scheduling.
        //         A freeze that shows NO 'work' lives here (e.g. a driver/swap stall or a GC pause on an
        //         otherwise-cheap frame) — which is why we trigger on 'period', not 'work'.
        long work = 0;
        for (long b : bucket) {
            work += b;
        }
        long period = nowBegin - beginNs;
        long gap = Math.max(0, period - work);

        long gcDeltaCount = 0;
        long gcDeltaTime = 0;
        long gcCount = 0;
        long gcTime = 0;
        if (gcBeans != null) {
            for (GarbageCollectorMXBean b : gcBeans) {
                long c = b.getCollectionCount();
                if (c > 0) {
                    gcCount += c;
                    gcTime += b.getCollectionTime();
                }
            }
        }
        if (prevGcCount >= 0) {
            gcDeltaCount = gcCount - prevGcCount;
            gcDeltaTime = gcTime - prevGcTime;
        }
        prevGcCount = gcCount;
        prevGcTime = gcTime;

        int children = chunkNode != null ? chunkNode.getQuantity() : -1;
        int childDelta = (prevChunkChildren < 0 || children < 0) ? 0 : children - prevChunkChildren;
        prevChunkChildren = children;

        long pageOps = chunkPager != null ? chunkPager.getPageOps() : -1;
        long pageOpsDelta = (prevPageOps < 0 || pageOps < 0) ? 0 : pageOps - prevPageOps;
        prevPageOps = pageOps;

        if (period < thresholdNanos) {
            return;
        }

        // Key phases, broken out (these are the ones that matter for movement hitches) :
        //  - state   : StateManagerUpdate segment (AppStates' update + the user simpleUpdate)
        //  - spatial : SpatialUpdate segment (rootNode/guiNode updateLogicalState + updateGeometricState)
        //  - render  : everything from StateManagerRender through the viewport render steps
        long state = bucket[AppStep.QueuedTasks.ordinal()]
                + bucket[AppStep.ProcessInput.ordinal()]
                + bucket[AppStep.ProcessAudio.ordinal()]
                + bucket[AppStep.StateManagerUpdate.ordinal()];
        long spatial = bucket[AppStep.SpatialUpdate.ordinal()];
        long render = bucket[AppStep.StateManagerRender.ordinal()]
                + bucket[AppStep.RenderFrame.ordinal()]
                + bucket[AppStep.RenderPreviewViewPorts.ordinal()]
                + bucket[AppStep.RenderMainViewPorts.ordinal()]
                + bucket[AppStep.RenderPostViewPorts.ordinal()];

        // Single dominant step (names the culprit precisely).
        int top = 0;
        for (int i = 1; i < bucket.length; i++) {
            if (bucket[i] > bucket[top]) {
                top = i;
            }
        }

        // When TimingNodes are installed, split the spatial step : world (root) vs HUD (gui), logical
        // (Controls) vs geometric (transform/bound refresh). This localises a SpatialUpdate-bound hitch.
        String spatialDetail = "";
        if (timingRoot != null) {
            spatialDetail = String.format(java.util.Locale.ROOT,
                    " [rootLog %.1f / rootGeo %.1f / guiLog %.1f / guiGeo %.1f]",
                    timingRoot.getLastLogicalNanos() / MS, timingRoot.getLastGeometricNanos() / MS,
                    timingGui != null ? timingGui.getLastLogicalNanos() / MS : 0.0,
                    timingGui != null ? timingGui.getLastGeometricNanos() / MS : 0.0);
        }

        // Costliest individual AppState substep (names a 'state'-bound hitch ; small on render-bound ones).
        long[] topSubNs = new long[1];
        String topSub = topSubStep(nowBegin, topSubNs);

        // Engine draw-call / triangle counts for this frame : discriminates a render-bound hitch that is
        // draw-call-CPU-bound (high draws — batching would help) from fill-rate/vertex-bound. Requires
        // statistics enabled (StatsAppState / showFps) ; -1 otherwise.
        int draws = -1;
        int tris = -1;
        Statistics st = getApplication().getRenderer().getStatistics();
        if (st != null) {
            String[] labels = st.getLabels();
            if (labels != null) {
                int[] data = new int[labels.length];
                st.getData(data);
                draws = stat(labels, data, "Objects");
                tris = stat(labels, data, "Triangles");
            }
        }

        log.warn("HITCH {}ms | work {} (state {} / spatial {}{} / render {}) | gap {} (swap/vsync/driver/OS) | top={} {}ms | sub={} {}ms | draws={} tris={} | GC +{} ({}ms) | chunkNode {} (net {}{}) | pageOps +{}",
                fmt(period), fmt(work), fmt(state), fmt(spatial), spatialDetail, fmt(render), fmt(gap),
                STEPS[top], fmt(bucket[top]), topSub, fmt(topSubNs[0]), draws, tris, gcDeltaCount, gcDeltaTime,
                children, childDelta >= 0 ? "+" : "", childDelta, pageOpsDelta);
    }

    private static String fmt(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / MS);
    }

    /** Looks up a named counter in the engine {@link Statistics} data, or -1 if absent. */
    private static int stat(String[] labels, int[] data, String name) {
        for (int i = 0; i < labels.length; i++) {
            if (labels[i].equals(name)) {
                return data[i];
            }
        }
        return -1;
    }

    @Override
    public void vpStep(VpStep step, ViewPort vp, RenderQueue.Bucket bucket) {
        // Not needed : appStep granularity already separates update from render.
    }

    @Override
    public void spStep(SpStep step, String... additionalInfo) {
        // Nothing to do
    }

    @Override
    public void appSubStep(String... additionalInfo) {
        long now = System.nanoTime();
        if (lastSub != null) {
            long[] b = subAccum.get(lastSub);
            if (b == null) {
                b = new long[1];
                subAccum.put(lastSub, b);
            }
            b[0] += now - lastSubNs;
        }
        lastSub = (additionalInfo != null && additionalInfo.length > 0) ? additionalInfo[0] : "?";
        lastSubNs = now;
    }

    /** The costliest AppState/render substep this frame (name + nanos), into store[0]=nanos. */
    private String topSubStep(long nowBegin, long[] store) {
        if (lastSub != null) {
            long[] b = subAccum.get(lastSub);
            if (b == null) {
                b = new long[1];
                subAccum.put(lastSub, b);
            }
            b[0] += nowBegin - lastSubNs;
            lastSub = null;
        }
        String top = "-";
        long topNs = 0;
        for (Map.Entry<String, long[]> e : subAccum.entrySet()) {
            if (e.getValue()[0] > topNs) {
                topNs = e.getValue()[0];
                top = e.getKey();
            }
        }
        store[0] = topNs;
        return top;
    }
}

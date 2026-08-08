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
 * <pre>HITCH 52.0ms | work 3.4 (state 0.2 / spatial 3.0 / render 0.2 [Opaque 0.1]) | gap 48.6 (swap/vsync/driver/OS) | top=SpatialUpdate 3.0ms | GC +1 (40ms) | chunkNode 567 (net +0) | pageOps +0</pre>
 *
 * Reading it :
 * <ul>
 *   <li><b>work</b> dominates ⇒ main-thread cost. Then look at the phase : <b>render</b> ⇒ GPU/VBO
 *       upload, split in brackets into its costliest stages by render-queue bucket (Opaque = voxel
 *       terrain, Transparent/Translucent = water and the merged sheets, Sky, Gui = HUD) ;
 *       <b>spatial</b> ⇒ scene-graph update — {@code rootNode}/{@code guiNode}
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

    // Per-render-stage timing WITHIN the viewport render, same delta technique : jME calls
    // vpStep(RenderBucket, vp, bucket) before flushing each queue bucket, so the interval between two
    // vpStep calls is the previous stage's cost.
    //
    // This exists because 'render' turned out to be the dominant phase of the on-device hitches (40-65 ms
    // of a 25-110 ms frame) while being uncorrelated with the draw and triangle counts — 163 draws /
    // 97783 tris took 64.0 ms, 179 draws / 106493 tris took 15.8 ms. A cost that does not track the work
    // submitted is episodic, and 'render' as one number cannot say which stage it lands in. Splitting by
    // bucket separates the candidates : Opaque is the voxel terrain, Transparent/Translucent the water and
    // the merged sheets, Sky the dome, Gui the HUD.
    private final Map<String, long[]> renderAccum = new HashMap<>();
    private String lastRenderStage;
    private long lastRenderNs;

    public HitchProfilerState(long thresholdMs) {
        this(thresholdMs, 0);
    }

    /**
     * @param cpuWindowSeconds when &gt; 0, additionally reports the render thread's CPU share of wall clock
     *                         once per window of that length — the computing-versus-waiting discriminator
     *                         described at {@link #accumulateCpuWindow}. 0 disables it.
     */
    public HitchProfilerState(long thresholdMs, long cpuWindowSeconds) {
        this.thresholdNanos = thresholdMs * 1_000_000L;
        this.windowNanos = cpuWindowSeconds * 1_000_000_000L;
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
        if (windowNanos > 0 && !tidResolved) {
            // Resolved HERE, not in initialize() : the profiler's callbacks are by definition on the
            // render thread, whereas whichever thread ran initialize() is an assumption. Getting that
            // wrong would not fail loudly — it would measure some other thread's CPU and report a
            // plausible, meaningless number, which is the worst outcome available.
            tidResolved = true;
            renderTid = findCurrentTid();
            if (renderTid < 0) {
                log.warn("RENDER CPU : could not identify the render thread's task id, measurement skipped");
            }
        }
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
            for (long[] v : renderAccum.values()) {
                v[0] = 0;
            }
            lastSub = null;
            lastRenderStage = null;
            beginNs = now;
            lastNs = now;
            lastStep = step;
            return;
        }
        if (lastStep != null) {
            bucket[lastStep.ordinal()] += now - lastNs;
        }
        // vpStep only fires inside the render steps, so any stage still open at an appStep boundary ends
        // here. Without this the last stage of the render (EndRender) stayed open until the NEXT frame's
        // BeginFrame and swallowed the whole buffer-swap/vsync gap — reporting 'EndRender 48.9' on a frame
        // whose render really cost 0.9 ms. The gap belongs to 'gap', which measures it independently.
        closeRenderStage(now);
        if (step == AppStep.EndFrame) {
            // Same trap for the per-AppState accounting, and it predates the render split : jME emits
            // EndFrame before the buffer swap, so a substep left open here was credited with the swap +
            // vsync idle on the next frame. That is what made 'sub=HitchProfilerState 48.9ms' appear on
            // frames where this state did nothing at all — the profiler accusing itself of the vsync wait.
            lastSub = null;
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

        accumulateCpuWindow(nowBegin, period, work, readDrawCount());

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

        log.warn("HITCH {}ms | work {} (state {} / spatial {}{} / render {} [{}]) | gap {} (swap/vsync/driver/OS) | top={} {}ms | sub={} {}ms | draws={} tris={} | GC +{} ({}ms) | chunkNode {} (net {}{}) | pageOps +{}",
                fmt(period), fmt(work), fmt(state), fmt(spatial), spatialDetail, fmt(render),
                renderBreakdown(nowBegin), fmt(gap),
                STEPS[top], fmt(bucket[top]), topSub, fmt(topSubNs[0]), draws, tris, gcDeltaCount, gcDeltaTime,
                children, childDelta >= 0 ? "+" : "", childDelta, pageOpsDelta);
    }

    // ---- Is the render thread COMPUTING or WAITING? ------------------------------------------------
    //
    // The viewport-scale test (-Dialon.vpscale) settled that the opaque pass is not fragment bound :
    // a quarter of the pixels left Opaque at 8.2 ms against 8.1 ms, a ratio of 1.01x. But it cannot
    // separate the two survivors, because neither scales with pixels :
    //   * per-draw CPU  — jME material/uniform setup plus the GL calls, through ANGLE's translation ;
    //   * GPU vertex/triangle throughput — vertex shading and primitive assembly, ~172k triangles here.
    //
    // These differ in one observable way : whether the thread burns CPU or blocks. A saturated GLES
    // command queue blocks inside glDrawElements, which is *inside* the Opaque bucket, so wall-clock
    // timing alone cannot tell — but the thread's CPU time can.
    //
    // Measured from /proc/self/task/<tid>/stat (utime + stime), which is plain file I/O and so works on
    // ART, where java.lang.management is absent (see initGcBeans). Ticks are coarse — USER_HZ is 100 on
    // Linux and Android alike, i.e. 10 ms granularity — so this aggregates over a window of seconds
    // instead of per frame. The assumption is self-diagnosing : were USER_HZ not 100, the reported
    // percentage would come out absurd (well over 100%) rather than quietly wrong.
    private static final long USER_HZ = 100;

    private final long windowNanos;
    private int renderTid = -1;
    private boolean tidResolved;
    private long windowStartNs = -1;
    private long windowStartTicks;
    private long windowPeriodNs;
    private long windowWorkNs;
    private long windowOpaqueNs;
    private long windowDraws;
    private int windowFrames;

    /**
     * This thread's kernel task id. Called on the render thread at init.
     *
     * <p>Asks {@code android.os.Process.myTid()} — which is just {@code gettid()} — by reflection, so
     * {@code :core} keeps no compile-time Android dependency. Reflection rather than a name match because
     * the name match is what failed on device: ART does not necessarily give the kernel task the same
     * {@code comm} as the Java thread name, and there is no reason to guess when the platform will answer.
     * The name match is kept only as the desktop fallback, where the class does not exist.
     */
    private static int findCurrentTid() {
        try {
            Class<?> process = Class.forName("android.os.Process");
            Object tid = process.getMethod("myTid").invoke(null);
            if (tid instanceof Integer && (Integer) tid > 0) {
                return (Integer) tid;
            }
        } catch (Exception e) {
            // not Android : fall through to the name match below
        }
        return findTidByName();
    }

    /** Desktop fallback : match {@code /proc/self/task/&#42;/comm} against the Java thread name. */
    private static int findTidByName() {
        String want = Thread.currentThread().getName();
        if (want.length() > 15) {
            want = want.substring(0, 15); // the kernel truncates comm to 15 characters
        }
        String[] tids = new java.io.File("/proc/self/task").list();
        if (tids == null) {
            return -1;
        }
        int found = -1;
        StringBuilder seen = new StringBuilder();
        for (String tid : tids) {
            try {
                byte[] raw = java.nio.file.Files.readAllBytes(
                        java.nio.file.Paths.get("/proc/self/task/" + tid + "/comm"));
                String comm = new String(raw, java.nio.charset.StandardCharsets.US_ASCII).trim();
                seen.append(comm).append(' ');
                if (comm.equals(want)) {
                    if (found >= 0) {
                        return -1; // ambiguous : two threads share the name, refuse to guess
                    }
                    found = Integer.parseInt(tid);
                }
            } catch (Exception e) {
                // task vanished or unreadable : skip it
            }
        }
        if (found < 0) {
            // Name the mismatch instead of failing silently : that is what cost a round trip the first time.
            log.warn("RENDER CPU : no kernel task named '{}' among [{}]", want, seen.toString().trim());
        }
        return found;
    }

    // Reused so reading the draw count every frame keeps the steady path allocation-free, as the class
    // contract promises. Only read at all when the CPU window is enabled (an opt-in diagnostic).
    private int[] statScratch;
    private String[] statLabels;

    /** This frame's draw count from the engine statistics, or -1 when unavailable/disabled. */
    private int readDrawCount() {
        if (windowNanos <= 0) {
            return -1;
        }
        Statistics st = getApplication().getRenderer().getStatistics();
        if (st == null) {
            return -1;
        }
        if (statLabels == null) {
            statLabels = st.getLabels();
            if (statLabels == null) {
                return -1;
            }
            statScratch = new int[statLabels.length];
        }
        st.getData(statScratch);
        return stat(statLabels, statScratch, "Objects");
    }

    /** This thread's consumed CPU, in clock ticks (utime + stime), or -1 if unavailable. */
    private long readCpuTicks() {
        if (renderTid < 0) {
            return -1;
        }
        try {
            byte[] raw = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get("/proc/self/task/" + renderTid + "/stat"));
            String stat = new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
            // The comm field may contain spaces and parentheses : everything is positional AFTER the
            // last ')'. From there, index 11 is utime and index 12 is stime.
            int afterComm = stat.lastIndexOf(')') + 2;
            if (afterComm >= stat.length()) {
                return -1;
            }
            String[] f = stat.substring(afterComm).trim().split("\\s+");
            if (f.length < 13) {
                return -1;
            }
            return Long.parseLong(f[11]) + Long.parseLong(f[12]);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Sums one window of frames and, when it closes, reports the render thread's CPU share of wall clock
     * next to the two predictions this is meant to choose between — so the reading is mechanical rather
     * than a judgement call.
     */
    private void accumulateCpuWindow(long nowBegin, long period, long work, int draws) {
        if (windowNanos <= 0) {
            return;
        }
        if (windowStartNs < 0) {
            windowStartNs = nowBegin;
            windowStartTicks = readCpuTicks();
            return;
        }
        long[] opaque = renderAccum.get(RenderQueue.Bucket.Opaque.name());
        windowPeriodNs += period;
        windowWorkNs += work;
        windowOpaqueNs += opaque == null ? 0 : opaque[0];
        windowDraws += Math.max(0, draws);
        windowFrames++;

        long elapsed = nowBegin - windowStartNs;
        if (elapsed < windowNanos) {
            return;
        }
        long ticks = readCpuTicks();
        if (ticks >= 0 && windowStartTicks >= 0 && windowFrames > 0) {
            double cpuSeconds = (ticks - windowStartTicks) / (double) USER_HZ;
            double wallSeconds = elapsed / 1e9;
            // Compared PER FRAME and in milliseconds, so it sits in the same units as work and Opaque.
            // If every millisecond of 'work' is real computation, cpu/frame lands on work/frame. If the
            // opaque pass is really the thread blocking on the GPU, it lands on (work - Opaque)/frame.
            double cpuPerFrame = cpuSeconds * 1000.0 / windowFrames;
            double ifCpuBound = windowWorkNs / (double) windowFrames / MS;
            double ifOpaqueIsGpuWait = (windowWorkNs - windowOpaqueNs) / (double) windowFrames / MS;
            // The bracket assumes the gap costs no CPU. It sometimes does — an fps cap or vsync that
            // busy-waits burns CPU outside 'work' — and then the measurement sits ABOVE the bracket and
            // says nothing about the opaque pass. Say so rather than pick the nearest prediction, which
            // would report a confident verdict from an artifact (seen on desktop : 19% measured against
            // predictions of 9% and 5%, where the 120 fps cap idle was the real consumer).
            String verdict;
            if (cpuPerFrame > ifCpuBound * 1.15) {
                verdict = "INCONCLUSIVE : cpu/frame exceeds the whole of 'work', so the gap is burning CPU"
                        + " too (fps cap / vsync busy-wait). Compare runs, or remove the cap, before reading it";
            } else if (Math.abs(cpuPerFrame - ifCpuBound) <= Math.abs(cpuPerFrame - ifOpaqueIsGpuWait)) {
                verdict = "CPU-bound (the thread is computing : attack draw/GL call count)";
            } else {
                verdict = "GPU-bound (the thread is blocking : attack vertex/triangle throughput)";
            }
            long meanDraws = windowDraws / windowFrames;
            String perDraw = meanDraws > 0
                    ? String.format(java.util.Locale.ROOT, "%.0f", windowOpaqueNs / (double) windowDraws / 1000.0)
                    : "?";
            log.warn("RENDER CPU over {}s : cpu {}ms/frame ({}% of wall) | {} frames,"
                            + " mean period {}ms = work {} + gap {} | mean Opaque {}ms over {} draws = {}us/draw"
                            + " | expect {}ms if all work is CPU, {}ms if Opaque is GPU wait ==> {}",
                    String.format(java.util.Locale.ROOT, "%.1f", wallSeconds),
                    String.format(java.util.Locale.ROOT, "%.1f", cpuPerFrame),
                    Math.round(100 * cpuSeconds / wallSeconds), windowFrames,
                    fmt(windowPeriodNs / windowFrames), fmt(windowWorkNs / windowFrames),
                    fmt((windowPeriodNs - windowWorkNs) / windowFrames),
                    fmt(windowOpaqueNs / windowFrames), meanDraws, perDraw,
                    String.format(java.util.Locale.ROOT, "%.1f", ifCpuBound),
                    String.format(java.util.Locale.ROOT, "%.1f", ifOpaqueIsGpuWait), verdict);
        }
        windowStartNs = nowBegin;
        windowStartTicks = ticks;
        windowPeriodNs = 0;
        windowWorkNs = 0;
        windowOpaqueNs = 0;
        windowDraws = 0;
        windowFrames = 0;
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

    /**
     * Splits the viewport render into its stages (see {@link #renderAccum}). Labelled by bucket when jME
     * reports one — that is the interesting axis — otherwise by the {@link VpStep} name, so stages that
     * are not a queue flush (scene processors in {@code PostQueue}, for instance) are still accounted for
     * rather than being folded into whichever bucket happened to be open.
     */
    @Override
    public void vpStep(VpStep step, ViewPort vp, RenderQueue.Bucket bucket) {
        long now = System.nanoTime();
        closeRenderStage(now);
        lastRenderStage = (step == VpStep.RenderBucket && bucket != null) ? bucket.name() : step.name();
        lastRenderNs = now;
    }

    /** Charges the elapsed time to the stage currently open, if any. */
    private void closeRenderStage(long now) {
        if (lastRenderStage == null) {
            return;
        }
        long[] b = renderAccum.get(lastRenderStage);
        if (b == null) {
            b = new long[1];
            renderAccum.put(lastRenderStage, b);
        }
        b[0] += now - lastRenderNs;
        lastRenderStage = null;
    }

    /**
     * The render stages of this frame, costliest first, as {@code Name a.b/Name c.d} — only those above
     * 0.5 ms, so the line stays short on a frame whose cost is concentrated (which is the case we care
     * about) and says so explicitly when it is spread thin instead.
     */
    private String renderBreakdown(long nowBegin) {
        closeRenderStage(nowBegin);
        StringBuilder sb = new StringBuilder();
        for (int rank = 0; rank < 3; rank++) {
            String bestName = null;
            long bestNs = 500_000L; // 0.5 ms floor
            for (Map.Entry<String, long[]> e : renderAccum.entrySet()) {
                long ns = e.getValue()[0];
                if (ns > bestNs && sb.indexOf(e.getKey() + " ") < 0) {
                    bestNs = ns;
                    bestName = e.getKey();
                }
            }
            if (bestName == null) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(bestName).append(' ').append(fmt(bestNs));
        }
        return sb.length() == 0 ? "spread" : sb.toString();
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

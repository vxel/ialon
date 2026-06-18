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

package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.SimpleApplication;
import com.jme3.app.state.BaseAppState;
import com.jme3.input.InputManager;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.texture.Image;
import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.Chunk;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.FillMode;
import com.simsilica.lemur.HAlignment;
import com.simsilica.lemur.Insets3f;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.style.ElementId;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.PlaceholderControl;
import org.delaunois.ialon.ui.UiHelper;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.extern.slf4j.Slf4j;
import org.delaunois.ialon.blocks.ChunkManager;
import org.delaunois.ialon.blocks.ChunkPager;

@Slf4j
public class IalonDebugState extends BaseAppState implements Resizable {

    private static final int MB = 1024 * 1024;
    private static final String APLHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    // Debug toggle (F8) : freezes the render chunk paging so the loaded chunks stay put while the
    // camera moves, to visually check the alignment between the voxels and the far terrain.
    private static final String FREEZE_PAGING = "ialon_debug_freeze_paging";
    // Debug toggle (F9) : logs a detailed memory breakdown (heap, off-heap direct buffers, chunk
    // meshes, chunk data arrays, collision meshes, texture atlas) to measure where RAM actually goes.
    private static final String MEMORY_REPORT = "ialon_debug_memory_report";

    private boolean pagingFrozen = false;
    private final ActionListener freezePagingListener = (name, isPressed, tpf) -> {
        if (isPressed) {
            toggleFreezePaging();
        }
    };
    private final ActionListener memoryReportListener = (name, isPressed, tpf) -> {
        if (isPressed) {
            logMemoryReport();
        }
    };

    private Node node;
    private Container container;
    private Container grid;
    private Label heapLabel;
    private Label worldPositionLabel;
    private Label positionLabel;
    private Label cursorPositionLabel;
    private Label blockLabel;
    private Label torchlightLevelLabel;
    private Label sunlightLevelLabel;
    private Label cacheSizeLabel;
    private Label timeLabel;
    private ChunkPager chunkPager;
    private PlayerState playerState;
    private PlaceholderControl placeholderControl;
    private SunState sunState;
    private float updateTime = 0.25f;
    private float curTime = 1;
    private final IalonConfig config;

    public IalonDebugState(IalonConfig config) {
        this.config = config;
    }

    @Override
    protected void initialize(Application app) {
        chunkPager = app.getStateManager().getState(ChunkPagerState.class).getChunkPager();
        playerState = app.getStateManager().getState(PlayerState.class);
        sunState = app.getStateManager().getState(SunState.class);

        container = new Container(new SpringGridLayout(Axis.Y, Axis.X, FillMode.Even, FillMode.Even));

        QuadBackgroundComponent background = new QuadBackgroundComponent(UiHelper.overlayColor(new ColorRGBA(0, 0, 0, 0.6f), config));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);

        heapLabel = addField(container, "Mem Heap: ");
        worldPositionLabel = addField(container, "Worl Pos: ");
        positionLabel = addField(container, "Chunk Pos: ");
        cursorPositionLabel = addField(container, "Cursor Pos:");
        blockLabel = addField(container, "Block:");
        sunlightLevelLabel = addField(container, "Sun Lvl: ");
        torchlightLevelLabel = addField(container, "Torch Lvl: ");
        cacheSizeLabel = addField(container, "Cache size: ");
        timeLabel = addField(container, "Time: ");

        grid = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        layout(getApplication().getCamera().getWidth(), getApplication().getCamera().getHeight());

        InputManager inputManager = app.getInputManager();
        inputManager.addMapping(FREEZE_PAGING, new KeyTrigger(KeyInput.KEY_F8));
        inputManager.addListener(freezePagingListener, FREEZE_PAGING);
        inputManager.addMapping(MEMORY_REPORT, new KeyTrigger(KeyInput.KEY_F9));
        inputManager.addListener(memoryReportListener, MEMORY_REPORT);

        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).register(this);
        }
    }

    @Override
    public void onResize(int width, int height) {
        layout(width, height);
    }

    private void layout(int width, int height) {
        container.setLocalScale(height / (container.getPreferredSize().getY() * 5));
        container.setLocalTranslation(0, height / 2f + container.getPreferredSize().getY(), 1);
        grid.setLocalTranslation((width - container.getPreferredSize().getX()) / 2f, height - 30f, 1);
    }

    /**
     * Toggles the render chunk paging : when frozen, the currently loaded chunks stay in place while
     * the camera/player keeps moving, making it easy to inspect the voxel ↔ far-terrain alignment.
     */
    private void toggleFreezePaging() {
        pagingFrozen = !pagingFrozen;
        ChunkPagerState pager = getApplication().getStateManager().getState(ChunkPagerState.class);
        if (pager != null) {
            pager.setEnabled(!pagingFrozen);
        }
        log.info("Render chunk paging {}", pagingFrozen ? "FROZEN (F8 to resume)" : "resumed");
    }

    /**
     * Logs a detailed memory breakdown. Mesh vertex/index buffers are NIO DIRECT buffers (off-heap),
     * so they are invisible to {@link Runtime#freeMemory()} : the "direct buffers" line (from the
     * JVM's direct {@link BufferPoolMXBean}) is where the bulk of the chunk-mesh memory shows up. The
     * per-category sums below attribute that total to the loaded chunk meshes, chunk data arrays,
     * collision meshes and the texture atlas.
     */
    private void logMemoryReport() {
        Runtime rt = Runtime.getRuntime();
        // Heap "used" includes uncollected garbage, which on a multi-GB desktop heap dwarfs the live
        // set. Hint a GC first so the reported figure approximates the LIVE objects (the number that
        // actually matters on a memory-constrained Android device).
        long heapUsedBeforeGc = rt.totalMemory() - rt.freeMemory();
        System.gc();
        System.gc();
        long heapUsed = rt.totalMemory() - rt.freeMemory();
        long heapMax = rt.maxMemory();

        long directUsed = 0;
        long directCount = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if ("direct".equals(pool.getName())) {
                directUsed = pool.getMemoryUsed();
                directCount = pool.getCount();
            }
        }

        // Loaded chunk render meshes (the geometries attached to the scene).
        long renderMeshBytes = 0;
        long geometryCount = 0;
        long vertexCount = 0;
        Map<Vec3i, Node> attachedPages = chunkPager.getAttachedPages();
        for (Node page : attachedPages.values()) {
            for (Geometry geom : collectGeometries(page)) {
                renderMeshBytes += meshBytes(geom.getMesh());
                geometryCount++;
                vertexCount += geom.getMesh().getVertexCount();
            }
        }

        // Chunk data arrays (heap) + collision meshes (direct), over the FULL ChunkManager cache
        // (not just the pager working set), so stale chunks lingering beyond the grid radius show up.
        long blockBytes = 0;
        long lightBytes = 0;
        long collisionBytes = 0;
        int cachedChunks = 0;
        int nonEmptyChunks = 0;
        for (Chunk chunk : chunkPager.getChunkManager().getCache().getChunks()) {
            cachedChunks++;
            if (chunk.getBlocks() != null) {
                blockBytes += chunk.getBlocks().length * 2L; // short[]
                nonEmptyChunks++;
            }
            if (chunk.getLightMap() != null) {
                lightBytes += chunk.getLightMap().length; // byte[]
            }
            if (chunk.getCollisionMesh() != null) {
                collisionBytes += meshBytes(chunk.getCollisionMesh());
            }
        }
        int fetchedChunks = chunkPager.getFetchedPages().size();

        // Texture atlas (diffuse) : ABGR8, plus ~33% for the mipmap chain on the GPU.
        Image atlas = config.getTextureAtlasManager().getDiffuseMap().getImage();
        long atlasBytes = (long) atlas.getWidth() * atlas.getHeight() * 4;

        log.info(String.format(Locale.US,
                "%n=== MEMORY REPORT ===%n"
                + "Heap used (live, post-GC) %6.1f MB / %.0f MB max  (was %.1f MB before GC = garbage)%n"
                + "Direct buffers (total) .. %6.1f MB  (%d buffers)  <- meshes live here (off-heap)%n"
                + "  Chunk render meshes ... %6.1f MB  (%d geometries, %d vertices, %d chunks attached)%n"
                + "  Collision meshes ...... %6.1f MB%n"
                + "Chunk data (heap) ....... %6.1f MB  (%d non-empty / %d cached / %d in pager)%n"
                + "  blocks short[] ........ %6.1f MB%n"
                + "  lightMap byte[] ....... %6.1f MB%n"
                + "Texture atlas (diffuse) . %6.1f MB  (%dx%d ABGR8, +~33%% mips on GPU)%n"
                + "=====================",
                mb(heapUsed), mb(heapMax), mb(heapUsedBeforeGc),
                mb(directUsed), directCount,
                mb(renderMeshBytes), geometryCount, vertexCount, attachedPages.size(),
                mb(collisionBytes),
                mb(blockBytes + lightBytes), nonEmptyChunks, cachedChunks, fetchedChunks,
                mb(blockBytes),
                mb(lightBytes),
                mb(atlasBytes), atlas.getWidth(), atlas.getHeight()));
    }

    private static double mb(long bytes) {
        return bytes / (double) MB;
    }

    /** Sums the byte size of all vertex/index buffers of a mesh (capacity x component size). */
    private static long meshBytes(Mesh mesh) {
        long bytes = 0;
        for (VertexBuffer vb : mesh.getBufferList()) {
            if (vb.getData() != null) {
                bytes += (long) vb.getData().capacity() * vb.getFormat().getComponentSize();
            }
        }
        return bytes;
    }

    private static List<Geometry> collectGeometries(Node node) {
        List<Geometry> geometries = new java.util.ArrayList<>();
        node.depthFirstTraversal(spatial -> {
            if (spatial instanceof Geometry) {
                geometries.add((Geometry) spatial);
            }
        });
        return geometries;
    }

    private Label addField(Container container, String title) {
        Container newContainer = container.addChild(new Container(new SpringGridLayout(Axis.X, Axis.Y, FillMode.Last, FillMode.Even), new ElementId(Container.ELEMENT_ID).child("entry")));
        Label textLabel = new Label(title);
        textLabel.setColor(ColorRGBA.White);
        textLabel.setFontSize(10);
        textLabel.getFont().getPage(0).clearParam(APLHA_DISCARD_THRESHOLD);
        newContainer.addChild(textLabel);
        Label label = newContainer.addChild(new Label("-"));
        label.setTextHAlignment(HAlignment.Right);
        label.setColor(ColorRGBA.White);
        label.setFontSize(10);
        label.getFont().getPage(0).clearParam(APLHA_DISCARD_THRESHOLD);
        return label;
    }

    @Override
    protected void cleanup(Application app) {
        InputManager inputManager = app.getInputManager();
        if (inputManager.hasMapping(FREEZE_PAGING)) {
            inputManager.deleteMapping(FREEZE_PAGING);
        }
        if (inputManager.hasMapping(MEMORY_REPORT)) {
            inputManager.deleteMapping(MEMORY_REPORT);
        }
        inputManager.removeListener(freezePagingListener);
        inputManager.removeListener(memoryReportListener);
        // Make sure paging is resumed if it was frozen.
        if (pagingFrozen) {
            ChunkPagerState pager = app.getStateManager().getState(ChunkPagerState.class);
            if (pager != null) {
                pager.setEnabled(true);
            }
            pagingFrozen = false;
        }
        if (app.getStateManager().getState(ScreenState.class) != null) {
            app.getStateManager().getState(ScreenState.class).unregister(this);
        }
    }

    @Override
    protected void onEnable() {
        if (node == null) {
            node = ((SimpleApplication) getApplication()).getGuiNode();
        }

        node.attachChild(container);
        node.attachChild(grid);
    }

    @Override
    protected void onDisable() {
        container.removeFromParent();
        grid.removeFromParent();
    }

    @Override
    public void update(float tpf) {
        curTime += tpf;

        if (curTime < updateTime) {
            return;
        }

        curTime = 0;

        // The world-dependent states are torn down and rebuilt on a world switch (WorldSelectionState),
        // so re-resolve them here instead of trusting the references cached at initialize() : otherwise
        // the overlay keeps querying the previous, now shut-down chunk manager and crashes.
        ChunkPagerState pagerState = getApplication().getStateManager().getState(ChunkPagerState.class);
        playerState = getApplication().getStateManager().getState(PlayerState.class);
        sunState = getApplication().getStateManager().getState(SunState.class);
        if (pagerState == null || playerState == null) {
            return; // a world switch is in progress : skip this frame
        }
        chunkPager = pagerState.getChunkPager();

        heapLabel.setText(getHeapString());
        worldPositionLabel.setText(getWorldLocationString());
        positionLabel.setText(getChunkLocationString());
        cursorPositionLabel.setText(getCursorLocationString());
        blockLabel.setText(getBlockLabelString());
        sunlightLevelLabel.setText(getSunlightLevelString());
        torchlightLevelLabel.setText(getTorchlightLevelString());
        cacheSizeLabel.setText(getCacheSizeString());
        timeLabel.setText(getLocalTimeString());

        if (config.isDebugGrid()) {
            displayGrid();
        }
    }

    private void displayGrid() {
        float size = 10;

        Vec3i centerpage = chunkPager.getCenterPage();
        grid.clearChildren();
        Map<Vec3i, Chunk> fetchedPages = chunkPager.getFetchedPages();
        Map<Vec3i, Node> attachedPages = chunkPager.getAttachedPages();

        List<Vec3i> locations = fetchedPages.keySet()
                .stream()
                .filter(loc -> loc.y == 4)
                .collect(Collectors.toList());

        Vec3i min = new Vec3i();
        for (Vec3i location : locations) {
            min.x = Math.min(location.x, min.x);
            min.z = Math.min(location.z, min.z);
        }

        locations.forEach(location ->
                displayGridLocation(location, centerpage, min, size, fetchedPages, attachedPages));
    }

    private void displayGridLocation(Vec3i location,
                                     Vec3i centerpage,
                                     Vec3i min,
                                     float size,
                                     Map<Vec3i, Chunk> fetchedPages,
                                     Map<Vec3i, Node> attachedPages) {
        ColorRGBA color = ColorRGBA.White;
        Chunk chunk = fetchedPages.get(location);
        Node attachedPage = attachedPages.get(location);
        Chunk cachedChunk = chunkPager.getChunkManager().getChunk(location).orElse(null);

        if (chunk.getNode() != null && cachedChunk != null && cachedChunk.getNode() != null) {
            if (attachedPage != null) {
                color = ColorRGBA.Green;
            } else {
                color = new ColorRGBA(0, 0.3f, 0, 1);
            }
        } else if (cachedChunk == null) {
            color = ColorRGBA.Red;
        } else if (cachedChunk.getNode() == null) {
            color = ColorRGBA.Black;
        } else if (cachedChunk.getNode().getParent() == null) {
            color = ColorRGBA.Gray;
        }

        if (attachedPage != null && (cachedChunk == null || cachedChunk.getNode() == null)) {
            color = ColorRGBA.Orange;
        }

        if (location.x == centerpage.x && location.z == centerpage.z) {
            color = ColorRGBA.Yellow;
        }

        Container block = new Container();
        block.setPreferredSize(new Vector3f(size, size, 0));
        block.setInsets(new Insets3f(1, 1, 1, 1));
        block.setBackground(new QuadBackgroundComponent(color));
        grid.addChild(block, location.x - min.x, location.z - min.z);
    }

    private String getWorldLocationString() {
        Vector3f loc = config.getPlayerLocation();
        if (loc == null) {
            return "?";
        }
        return String.format(Locale.ENGLISH,"(%.2f, %.2f, %.2f)", loc.x, loc.y, loc.z);
    }

    private String getChunkLocationString() {
        Vec3i loc = chunkPager.getCenterPage();
        if (loc == null) {
            return "?";
        }
        return String.format(Locale.ENGLISH,"(%d, %d, %d)", loc.x, loc.y, loc.z);
    }

    private String getCursorLocationString() {
        Vector3f loc = getRemovePlaceholderPosition();
        String blockLocation = "?";
        if (loc == null) {
            return "?";
        }
        Chunk c = chunkPager.getChunkManager().getChunk(ChunkManager.getChunkLocation(loc)).orElse(null);
        if (c != null) {
            Vec3i blockLocalLocation = c.toLocalLocation(ChunkManager.getBlockLocation(loc));
            blockLocation = String.format(Locale.ENGLISH,"(%d, %d, %d)", blockLocalLocation.x, blockLocalLocation.y, blockLocalLocation.z);
        }
        return String.format(Locale.ENGLISH,"(%.0f, %.0f, %.0f) %s", loc.x, loc.y, loc.z, blockLocation);
    }

    private String getBlockLabelString() {
        Block block = chunkPager.getChunkManager().getBlock(getRemovePlaceholderPosition()).orElse(null);
        Block block2 = chunkPager.getChunkManager().getBlock(getAddPlaceholderPosition()).orElse(null);
        return String.format(Locale.ENGLISH,"%s (%s)",
                block == null ? "-" : block.getName(),
                block2 == null ? "-" : block2.getName());
    }

    private String getSunlightLevelString() {
        return String.format(Locale.ENGLISH,"%d (%d)",
                playerState.getWorldManager().getSunlightLevel(getAddPlaceholderPosition()),
                playerState.getWorldManager().getSunlightLevel(getRemovePlaceholderPosition()));
    }

    private String getTorchlightLevelString() {
        return String.format(Locale.ENGLISH,"%d (%d)",
                playerState.getWorldManager().getTorchlightLevel(getAddPlaceholderPosition()),
                playerState.getWorldManager().getTorchlightLevel(getRemovePlaceholderPosition()));
    }

    private String getCacheSizeString() {
        return String.format(Locale.ENGLISH, "%d", chunkPager.getAttachedPages().size());
    }

    private String getLocalTimeString() {
        if (sunState != null && sunState.getSunControl() != null) {
            return String.format(Locale.ENGLISH, "%02d:%02d:%02d",
                    sunState.getSunControl().getLocalTime().getHour(),
                    sunState.getSunControl().getLocalTime().getMinute(),
                    sunState.getSunControl().getLocalTime().getSecond());
        }
        return "-";
    }

    private String getHeapString() {
        return String.format(Locale.ENGLISH,"%d MB / %d MB", getUsedHeap() / MB, getTotalHeap() / MB);
    }

    private long getTotalHeap() {
        return Runtime.getRuntime().totalMemory();
    }

    private long getUsedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }

    private Vector3f getRemovePlaceholderPosition() {
        if (placeholderControl == null && playerState.getPlayerNode() != null) {
            placeholderControl = playerState.getHeadNode().getControl(PlaceholderControl.class);
        }

        if (placeholderControl != null) {
            return placeholderControl.getRemovePlaceholderPosition();
        }
        return Vector3f.NAN;
    }

    private Vector3f getAddPlaceholderPosition() {
        if (placeholderControl == null && playerState.getPlayerNode() != null) {
            placeholderControl = playerState.getPlayerNode().getControl(PlaceholderControl.class);
        }

        if (placeholderControl != null) {
            return placeholderControl.getAddPlaceholderPosition();
        }
        return Vector3f.NAN;
    }
}

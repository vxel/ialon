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
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.scene.Node;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.Chunk;
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

import org.delaunois.ialon.ChunkManager;
import org.delaunois.ialon.ChunkPager;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.control.PlaceholderControl;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

public class IalonDebugState extends BaseAppState {

    private static final int MB = 1024 * 1024;
    private static final String APLHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";

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

        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
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

        container.setLocalScale(getApplication().getCamera().getHeight() / (container.getPreferredSize().getY() * 5));
        container.setLocalTranslation(0, getApplication().getCamera().getHeight() / 2f + container.getPreferredSize().getY(), 1);

        grid = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        grid.setLocalTranslation((getApplication().getCamera().getWidth() - container.getPreferredSize().getX()) / 2f, getApplication().getCamera().getHeight() - 30f, 1);
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
        // Nothing to do
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

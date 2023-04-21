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
import com.jme3.font.BitmapFont;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.BufferUtils;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockIds;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMeshGenerator;
import com.rvandoosselaer.blocks.TypeIds;
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.component.DynamicInsetsComponent;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonBlock;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Objects;

import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;
import static com.rvandoosselaer.blocks.ShapeIds.CROSS_PLANE;
import static com.rvandoosselaer.blocks.ShapeIds.CUBE;
import static com.rvandoosselaer.blocks.ShapeIds.DOUBLE_SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.FENCE;
import static com.rvandoosselaer.blocks.ShapeIds.PLATE;
import static com.rvandoosselaer.blocks.ShapeIds.POLE;
import static com.rvandoosselaer.blocks.ShapeIds.PYRAMID;
import static com.rvandoosselaer.blocks.ShapeIds.SHORT_POLE;
import static com.rvandoosselaer.blocks.ShapeIds.SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE_NORTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_EAST;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_INNER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_OUTER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.WEDGE_SOUTH;
import static org.delaunois.ialon.IalonBlock.BIRCH_LEAVES;
import static org.delaunois.ialon.IalonBlock.BIRCH_LOG;
import static org.delaunois.ialon.IalonBlock.BIRCH_PLANKS;
import static org.delaunois.ialon.IalonBlock.BRICKS;
import static org.delaunois.ialon.IalonBlock.COBBLESTONE;
import static org.delaunois.ialon.IalonBlock.DIRT;
import static org.delaunois.ialon.IalonBlock.GRASS;
import static org.delaunois.ialon.IalonBlock.GRASS_SNOW;
import static org.delaunois.ialon.IalonBlock.GRAVEL;
import static org.delaunois.ialon.IalonBlock.ITEM_GRASS;
import static org.delaunois.ialon.IalonBlock.ITEM_SEAWEED;
import static org.delaunois.ialon.IalonBlock.METAL1;
import static org.delaunois.ialon.IalonBlock.METAL2;
import static org.delaunois.ialon.IalonBlock.METAL3;
import static org.delaunois.ialon.IalonBlock.METAL4;
import static org.delaunois.ialon.IalonBlock.METAL5;
import static org.delaunois.ialon.IalonBlock.MOSSY_COBBLESTONE;
import static org.delaunois.ialon.IalonBlock.MOSSY_STONE_BRICKS;
import static org.delaunois.ialon.IalonBlock.OAK_LEAVES;
import static org.delaunois.ialon.IalonBlock.OAK_LOG;
import static org.delaunois.ialon.IalonBlock.OAK_PLANKS;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_LEAVES;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_LOG;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_PLANKS;
import static org.delaunois.ialon.IalonBlock.ROCK;
import static org.delaunois.ialon.IalonBlock.SAND;
import static org.delaunois.ialon.IalonBlock.SCALE;
import static org.delaunois.ialon.IalonBlock.SLATE;
import static org.delaunois.ialon.IalonBlock.SNOW;
import static org.delaunois.ialon.IalonBlock.SPRUCE_LEAVES;
import static org.delaunois.ialon.IalonBlock.SPRUCE_LOG;
import static org.delaunois.ialon.IalonBlock.SPRUCE_PLANKS;
import static org.delaunois.ialon.IalonBlock.STONE_BRICKS;
import static org.delaunois.ialon.IalonBlock.STONE_BRICKS2;
import static org.delaunois.ialon.IalonBlock.STONE_BRICKS3;
import static org.delaunois.ialon.IalonBlock.TILE_RED;
import static org.delaunois.ialon.IalonBlock.WHITE_LIGHT;
import static org.delaunois.ialon.IalonBlock.WINDOW;

@Slf4j
public class BlockSelectionState extends BaseAppState implements ActionListener, AnalogListener {

    private static final String SPACER = null;
    private static final String[] BLOCK_IDS = {
            // Page 1
            getName(GRASS, CUBE),
            getName(GRASS, DOUBLE_SLAB),
            getName(GRASS, SLAB),
            getName(GRASS_SNOW, CUBE),
            getName(GRASS_SNOW, DOUBLE_SLAB),
            getName(GRASS_SNOW, SLAB),
            WATER_SOURCE,
            getName(WHITE_LIGHT, SHORT_POLE),
            getName(SCALE, SQUARE_NORTH),
            SPACER,

            getName(DIRT, CUBE),
            getName(DIRT, DOUBLE_SLAB),
            getName(DIRT, SLAB),
            getName(DIRT, PLATE),
            getName(DIRT, WEDGE_SOUTH),
            getName(DIRT, PYRAMID),
            getName(DIRT, POLE),
            getName(DIRT, STAIRS_EAST),
            getName(DIRT, STAIRS_INNER_CORNER_SOUTH),
            getName(DIRT, STAIRS_OUTER_CORNER_SOUTH),

            getName(BIRCH_LOG, CUBE),
            getName(BIRCH_LOG, DOUBLE_SLAB),
            getName(BIRCH_LOG, SLAB),
            getName(BIRCH_LOG, PLATE),
            getName(BIRCH_LOG, WEDGE_SOUTH),
            getName(BIRCH_LOG, PYRAMID),
            getName(BIRCH_LOG, POLE),
            SPACER,
            SPACER,
            SPACER,

            getName(BIRCH_PLANKS, CUBE),
            getName(BIRCH_PLANKS, DOUBLE_SLAB),
            getName(BIRCH_PLANKS, SLAB),
            getName(BIRCH_PLANKS, PLATE),
            getName(BIRCH_PLANKS, WEDGE_SOUTH),
            getName(BIRCH_PLANKS, PYRAMID),
            getName(BIRCH_PLANKS, POLE),
            getName(BIRCH_PLANKS, STAIRS_EAST),
            getName(BIRCH_PLANKS, STAIRS_INNER_CORNER_SOUTH),
            getName(BIRCH_PLANKS, STAIRS_OUTER_CORNER_SOUTH),

            // Page 2
            getName(BRICKS, CUBE),
            getName(BRICKS, DOUBLE_SLAB),
            getName(BRICKS, SLAB),
            getName(BRICKS, PLATE),
            getName(BRICKS, WEDGE_SOUTH),
            getName(BRICKS, PYRAMID),
            getName(BRICKS, POLE),
            getName(BRICKS, STAIRS_EAST),
            getName(BRICKS, STAIRS_INNER_CORNER_SOUTH),
            getName(BRICKS, STAIRS_OUTER_CORNER_SOUTH),

            getName(COBBLESTONE, CUBE),
            getName(COBBLESTONE, DOUBLE_SLAB),
            getName(COBBLESTONE, SLAB),
            getName(COBBLESTONE, PLATE),
            getName(COBBLESTONE, WEDGE_SOUTH),
            getName(COBBLESTONE, PYRAMID),
            getName(COBBLESTONE, POLE),
            getName(COBBLESTONE, STAIRS_EAST),
            getName(COBBLESTONE, STAIRS_INNER_CORNER_SOUTH),
            getName(COBBLESTONE, STAIRS_OUTER_CORNER_SOUTH),

            getName(MOSSY_COBBLESTONE, CUBE),
            getName(MOSSY_COBBLESTONE, DOUBLE_SLAB),
            getName(MOSSY_COBBLESTONE, SLAB),
            getName(MOSSY_COBBLESTONE, PLATE),
            getName(MOSSY_COBBLESTONE, WEDGE_SOUTH),
            getName(MOSSY_COBBLESTONE, PYRAMID),
            getName(MOSSY_COBBLESTONE, POLE),
            getName(MOSSY_COBBLESTONE, STAIRS_EAST),
            getName(MOSSY_COBBLESTONE, STAIRS_INNER_CORNER_SOUTH),
            getName(MOSSY_COBBLESTONE, STAIRS_OUTER_CORNER_SOUTH),

            getName(GRAVEL, CUBE),
            getName(GRAVEL, DOUBLE_SLAB),
            getName(GRAVEL, SLAB),
            getName(GRAVEL, PLATE),
            getName(GRAVEL, WEDGE_SOUTH),
            getName(GRAVEL, PYRAMID),
            getName(GRAVEL, POLE),
            getName(GRAVEL, STAIRS_EAST),
            getName(GRAVEL, STAIRS_INNER_CORNER_SOUTH),
            getName(GRAVEL, STAIRS_OUTER_CORNER_SOUTH),

            // Page 3
            getName(SLATE, CUBE),
            getName(SLATE, DOUBLE_SLAB),
            getName(SLATE, SLAB),
            getName(SLATE, PLATE),
            getName(SLATE, WEDGE_SOUTH),
            getName(SLATE, PYRAMID),
            getName(SLATE, POLE),
            getName(SLATE, STAIRS_EAST),
            getName(SLATE, STAIRS_INNER_CORNER_SOUTH),
            getName(SLATE, STAIRS_OUTER_CORNER_SOUTH),

            getName(TILE_RED, CUBE),
            getName(TILE_RED, DOUBLE_SLAB),
            getName(TILE_RED, SLAB),
            getName(TILE_RED, PLATE),
            getName(TILE_RED, WEDGE_SOUTH),
            getName(TILE_RED, PYRAMID),
            getName(TILE_RED, POLE),
            getName(TILE_RED, STAIRS_EAST),
            getName(TILE_RED, STAIRS_INNER_CORNER_SOUTH),
            getName(TILE_RED, STAIRS_OUTER_CORNER_SOUTH),

            getName(PALM_TREE_LOG, CUBE),
            getName(PALM_TREE_LOG, DOUBLE_SLAB),
            getName(PALM_TREE_LOG, SLAB),
            getName(PALM_TREE_LOG, PLATE),
            getName(PALM_TREE_LOG, WEDGE_SOUTH),
            getName(PALM_TREE_LOG, PYRAMID),
            getName(PALM_TREE_LOG, POLE),
            SPACER,
            SPACER,
            SPACER,

            getName(PALM_TREE_PLANKS, CUBE),
            getName(PALM_TREE_PLANKS, DOUBLE_SLAB),
            getName(PALM_TREE_PLANKS, SLAB),
            getName(PALM_TREE_PLANKS, PLATE),
            getName(PALM_TREE_PLANKS, WEDGE_SOUTH),
            getName(PALM_TREE_PLANKS, PYRAMID),
            getName(PALM_TREE_PLANKS, POLE),
            getName(PALM_TREE_PLANKS, STAIRS_EAST),
            getName(PALM_TREE_PLANKS, STAIRS_INNER_CORNER_SOUTH),
            getName(PALM_TREE_PLANKS, STAIRS_OUTER_CORNER_SOUTH),

            // Page 4
            getName(ROCK, CUBE),
            getName(ROCK, DOUBLE_SLAB),
            getName(ROCK, SLAB),
            getName(ROCK, PLATE),
            getName(ROCK, WEDGE_SOUTH),
            getName(ROCK, PYRAMID),
            getName(ROCK, POLE),
            getName(ROCK, STAIRS_EAST),
            getName(ROCK, STAIRS_INNER_CORNER_SOUTH),
            getName(ROCK, STAIRS_OUTER_CORNER_SOUTH),

            getName(OAK_LOG, CUBE),
            getName(OAK_LOG, DOUBLE_SLAB),
            getName(OAK_LOG, SLAB),
            getName(OAK_LOG, PLATE),
            getName(OAK_LOG, WEDGE_SOUTH),
            getName(OAK_LOG, PYRAMID),
            getName(OAK_LOG, POLE),
            SPACER,
            SPACER,
            SPACER,

            getName(OAK_PLANKS, CUBE),
            getName(OAK_PLANKS, DOUBLE_SLAB),
            getName(OAK_PLANKS, SLAB),
            getName(OAK_PLANKS, PLATE),
            getName(OAK_PLANKS, WEDGE_SOUTH),
            getName(OAK_PLANKS, PYRAMID),
            getName(OAK_PLANKS, POLE),
            getName(OAK_PLANKS, STAIRS_EAST),
            getName(OAK_PLANKS, STAIRS_INNER_CORNER_SOUTH),
            getName(OAK_PLANKS, STAIRS_OUTER_CORNER_SOUTH),

            getName(SAND, CUBE),
            getName(SAND, DOUBLE_SLAB),
            getName(SAND, SLAB),
            getName(SAND, PLATE),
            getName(SAND, WEDGE_SOUTH),
            getName(SAND, PYRAMID),
            getName(SAND, POLE),
            getName(SAND, STAIRS_EAST),
            getName(SAND, STAIRS_INNER_CORNER_SOUTH),
            getName(SAND, STAIRS_OUTER_CORNER_SOUTH),

            // Page 5
            getName(SNOW, CUBE),
            getName(SNOW, DOUBLE_SLAB),
            getName(SNOW, SLAB),
            getName(SNOW, PLATE),
            getName(SNOW, WEDGE_SOUTH),
            getName(SNOW, PYRAMID),
            getName(SNOW, POLE),
            getName(SNOW, STAIRS_EAST),
            getName(SNOW, STAIRS_INNER_CORNER_SOUTH),
            getName(SNOW, STAIRS_OUTER_CORNER_SOUTH),

            getName(SPRUCE_LOG, CUBE),
            getName(SPRUCE_LOG, DOUBLE_SLAB),
            getName(SPRUCE_LOG, SLAB),
            getName(SPRUCE_LOG, PLATE),
            getName(SPRUCE_LOG, WEDGE_SOUTH),
            getName(SPRUCE_LOG, PYRAMID),
            getName(SPRUCE_LOG, POLE),
            SPACER,
            SPACER,
            SPACER,

            getName(SPRUCE_PLANKS, CUBE),
            getName(SPRUCE_PLANKS, DOUBLE_SLAB),
            getName(SPRUCE_PLANKS, SLAB),
            getName(SPRUCE_PLANKS, PLATE),
            getName(SPRUCE_PLANKS, WEDGE_SOUTH),
            getName(SPRUCE_PLANKS, PYRAMID),
            getName(SPRUCE_PLANKS, POLE),
            getName(SPRUCE_PLANKS, STAIRS_EAST),
            getName(SPRUCE_PLANKS, STAIRS_INNER_CORNER_SOUTH),
            getName(SPRUCE_PLANKS, STAIRS_OUTER_CORNER_SOUTH),

            getName(STONE_BRICKS, CUBE),
            getName(STONE_BRICKS, DOUBLE_SLAB),
            getName(STONE_BRICKS, SLAB),
            getName(STONE_BRICKS, PLATE),
            getName(STONE_BRICKS, WEDGE_SOUTH),
            getName(STONE_BRICKS, PYRAMID),
            getName(STONE_BRICKS, POLE),
            getName(STONE_BRICKS, STAIRS_EAST),
            getName(STONE_BRICKS, STAIRS_INNER_CORNER_SOUTH),
            getName(STONE_BRICKS, STAIRS_OUTER_CORNER_SOUTH),

            // Page 6
            getName(STONE_BRICKS2, CUBE),
            getName(STONE_BRICKS3, CUBE),
            getName(STONE_BRICKS2, DOUBLE_SLAB),
            getName(STONE_BRICKS2, SLAB),
            getName(STONE_BRICKS2, WEDGE_SOUTH),
            getName(STONE_BRICKS2, PYRAMID),
            getName(STONE_BRICKS2, POLE),
            getName(STONE_BRICKS2, STAIRS_EAST),
            getName(STONE_BRICKS2, STAIRS_INNER_CORNER_SOUTH),
            getName(STONE_BRICKS2, STAIRS_OUTER_CORNER_SOUTH),

            getName(MOSSY_STONE_BRICKS, CUBE),
            getName(MOSSY_STONE_BRICKS, DOUBLE_SLAB),
            getName(MOSSY_STONE_BRICKS, SLAB),
            getName(MOSSY_STONE_BRICKS, PLATE),
            getName(MOSSY_STONE_BRICKS, WEDGE_SOUTH),
            getName(MOSSY_STONE_BRICKS, PYRAMID),
            getName(MOSSY_STONE_BRICKS, POLE),
            getName(MOSSY_STONE_BRICKS, STAIRS_EAST),
            getName(MOSSY_STONE_BRICKS, STAIRS_INNER_CORNER_SOUTH),
            getName(MOSSY_STONE_BRICKS, STAIRS_OUTER_CORNER_SOUTH),

            getName(METAL1, CUBE),
            getName(METAL1, DOUBLE_SLAB),
            getName(METAL1, SLAB),
            getName(METAL1, PLATE),
            getName(METAL1, WEDGE_SOUTH),
            getName(METAL1, PYRAMID),
            getName(METAL1, POLE),
            getName(METAL1, STAIRS_EAST),
            getName(METAL1, STAIRS_INNER_CORNER_SOUTH),
            getName(METAL1, STAIRS_OUTER_CORNER_SOUTH),

            getName(METAL2, CUBE),
            getName(METAL2, DOUBLE_SLAB),
            getName(METAL2, SLAB),
            getName(METAL2, PLATE),
            getName(METAL2, WEDGE_SOUTH),
            getName(METAL2, PYRAMID),
            getName(METAL2, POLE),
            getName(METAL2, STAIRS_EAST),
            getName(METAL2, STAIRS_INNER_CORNER_SOUTH),
            getName(METAL2, STAIRS_OUTER_CORNER_SOUTH),

            // Page 7
            getName(METAL3, CUBE),
            getName(METAL3, DOUBLE_SLAB),
            getName(METAL3, SLAB),
            getName(METAL3, PLATE),
            getName(METAL3, WEDGE_SOUTH),
            getName(METAL3, PYRAMID),
            getName(METAL3, POLE),
            getName(METAL3, STAIRS_EAST),
            getName(METAL3, STAIRS_INNER_CORNER_SOUTH),
            getName(METAL3, STAIRS_OUTER_CORNER_SOUTH),

            getName(METAL4, CUBE),
            getName(METAL4, DOUBLE_SLAB),
            getName(METAL4, SLAB),
            getName(METAL4, PLATE),
            getName(METAL4, WEDGE_SOUTH),
            getName(METAL4, PYRAMID),
            getName(METAL4, POLE),
            getName(METAL4, STAIRS_EAST),
            getName(METAL4, STAIRS_INNER_CORNER_SOUTH),
            getName(METAL4, STAIRS_OUTER_CORNER_SOUTH),

            getName(METAL5, CUBE),
            getName(METAL5, DOUBLE_SLAB),
            getName(METAL5, SLAB),
            getName(METAL5, PLATE),
            getName(METAL5, WEDGE_SOUTH),
            getName(METAL5, PYRAMID),
            getName(METAL5, POLE),
            getName(METAL5, STAIRS_EAST),
            getName(METAL5, STAIRS_INNER_CORNER_SOUTH),
            getName(METAL5, STAIRS_OUTER_CORNER_SOUTH),

            getName(BIRCH_LEAVES, CUBE),
            getName(PALM_TREE_LEAVES, CUBE),
            getName(OAK_LEAVES, CUBE),
            getName(SPRUCE_LEAVES, CUBE),
            getName(WINDOW, CUBE),
            getName(WINDOW, PLATE),
            getName(WINDOW, SQUARE),
            getName(ITEM_GRASS, CROSS_PLANE),
            getName(ITEM_SEAWEED, CROSS_PLANE),
            SPACER,

            // Page 8
            getName(OAK_LOG, FENCE),
            getName(STONE_BRICKS, FENCE),
            getName(STONE_BRICKS2, FENCE),
            getName(BIRCH_LOG, FENCE),
            getName(BIRCH_PLANKS, FENCE),
            getName(METAL1, FENCE),
            getName(METAL2, FENCE),
            getName(METAL3, FENCE),
            getName(METAL4, FENCE),
            getName(METAL5, FENCE)

    };

    private static final int SCREEN_MARGIN = 30;
    private static final int SPACING = 10;
    private static final int MENUBLOCK_PAGESIZE_X = 10;
    private static final int MENUBLOCK_PAGESIZE_Y = 4;
    private static final int BLOCK_HISTORY_SIZE = 3;
    private static final String APLHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private Node[] blocks;

    private SimpleApplication app;
    private BitmapFont guiFont;

    // Buttons
    private Container nextButton;
    private Container previousButton;
    private Container blockSelectionButton;
    private final Container[] historyButton = new Container[BLOCK_HISTORY_SIZE];

    // Container
    private Container menuBlock;
    private Container[] menuBlockPages;
    private final Node historyButtons = new Node();
    private final Node[] lastSelectedBlockNode = new Node[BLOCK_HISTORY_SIZE];
    private final int[] history = new int[BLOCK_HISTORY_SIZE];

    private int selectedBlockPage = 0;
    private int selectedBlockIndex = 0;
    private Node selectedBlockNode;

    private float buttonSize = 100;
    private float blockButtonSize = 100;

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;

        for (int i = 0; i < lastSelectedBlockNode.length; i++) {
            lastSelectedBlockNode[i] = new Node();
        }
        Arrays.fill(history, -1);

        buttonSize = app.getCamera().getHeight() / 8f;
        blockButtonSize = buttonSize;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        blocks = createBlockNodes();
        menuBlockPages = createMenuBlock();
        menuBlock = menuBlockPages[0];

        // Create history buttons
        for (int i = 0; i < BLOCK_HISTORY_SIZE; i++) {
            int index = i;
            historyButton[i] = createBlockButton(lastSelectedBlockNode[i], buttonSize, new DefaultMouseListener() {
                @Override
                public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                    event.setConsumed();
                    if (event.isPressed() && history[index] >= 0) {
                        setSelectedBlockIndex(history[index]);
                    }
                }
            });
            historyButton[i].setLocalTranslation(i * (buttonSize + SPACING), 0, 1);
            historyButtons.attachChild(historyButton[i]);
        }
        historyButtons.setLocalTranslation(app.getCamera().getWidth() / 2f - (BLOCK_HISTORY_SIZE / 2f) * buttonSize - SPACING, SCREEN_MARGIN + buttonSize, 1);
        historyButtons.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        historyButtons.addLight(new AmbientLight(ColorRGBA.White.mult(.5f)));

        // Create selection button
        setSelectedBlockIndex(selectedBlockIndex);
        blockSelectionButton = createBlockButton(selectedBlockNode, buttonSize, new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    toggleBlockMenu();
                }
                highlight(event.isPressed(), blockSelectionButton);
            }
        });
        blockSelectionButton.setLocalTranslation(app.getCamera().getWidth() - buttonSize - SCREEN_MARGIN, (app.getCamera().getHeight() + buttonSize) / 2f, 1);
        blockSelectionButton.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        blockSelectionButton.addLight(new AmbientLight(ColorRGBA.White.mult(.5f)));
    }

    private void highlight(boolean isPressed, Container button) {
        if (isPressed) {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0.5f, 0.5f, 0.5f, 0.5f);
        } else {
            ((QuadBackgroundComponent)button.getBackground()).getColor().set(0, 0, 0, 0.5f);
        }
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    public void resize() {
        hideBlockMenu();
        menuBlockPages = createMenuBlock();
        menuBlock = menuBlockPages[0];
        setSelectedBlockIndex(selectedBlockIndex);
        blockSelectionButton.setLocalTranslation(
                app.getCamera().getWidth() - buttonSize - SCREEN_MARGIN,
                (app.getCamera().getHeight() + buttonSize) / 2f,
                1);
        historyButtons.setLocalTranslation(
                app.getCamera().getWidth() / 2f - (BLOCK_HISTORY_SIZE / 2f) * buttonSize - SPACING,
                SCREEN_MARGIN + buttonSize,
                1);
    }

    public void selectBlockMenuPage(int page) {
        log.info("Selection block page {}", page);
        if (page < 0) {
            selectedBlockPage = menuBlockPages.length + (page % menuBlockPages.length);
        } else {
            selectedBlockPage = page % menuBlockPages.length;
        }
        Node parent = menuBlock.getParent();
        if (parent != null) {
            menuBlock.removeFromParent();
            menuBlock = menuBlockPages[selectedBlockPage];
            parent.attachChild(menuBlock);

        } else {
            menuBlock = menuBlockPages[selectedBlockPage];
        }
    }

    public void hideBlockMenu() {
        if (menuBlock.getParent() != null) {
            menuBlock.removeFromParent();
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(true);
        }
    }

    public void showBlockMenu() {
        if (menuBlock.getParent() == null) {
            app.getGuiNode().attachChild(menuBlock);
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(false);
        }
    }

    public void toggleBlockMenu() {
        if (menuBlock.getParent() == null) {
            showBlockMenu();
        } else {
            hideBlockMenu();
        }
    }

    public Block getSelectedBlock() {
        return BlocksConfig.getInstance().getBlockRegistry().get(BLOCK_IDS[selectedBlockIndex]);
    }

    private void setSelectedBlockIndex(int index) {
        log.info("Selecting {}", BLOCK_IDS[index]);

        selectedBlockIndex = index;
        selectedBlockNode = updateBlockNode(selectedBlockNode, index);
        updateHistory(index);
    }

    private void updateHistory(int index) {
        boolean found = false;
        for (int i = 0; i < history.length; i++) {
            highlight(false, historyButton[i]);
            if (history[i] == index) {
                highlight(true, historyButton[i]);
                found = true;
            }
        }

        if (found) {
            return;
        }

        if (history.length - 1 >= 0) {
            System.arraycopy(history, 0, history, 1, history.length - 1);
        }
        history[0] = index;

        for (int i = 0; i < history.length; i++) {
            if (history[i] >= 0) {
                lastSelectedBlockNode[i] = updateBlockNode(lastSelectedBlockNode[i], history[i]);
            }
        }
        highlight(true, historyButton[0]);
    }

    private Node updateBlockNode(Node nodeToRemove, int blockIndexToAdd) {
        Node nodeToAdd = (Node)blocks[blockIndexToAdd].clone();
        updateBlockNode(nodeToRemove, nodeToAdd);
        return nodeToAdd;
    }

    private void updateBlockNode(Node nodeToRemove, Node newNode) {
        Node parent = null;
        if (nodeToRemove != null) {
            parent = nodeToRemove.getParent();
            nodeToRemove.removeFromParent();
        }
        if (parent != null) {
            parent.attachChild(newNode);
        }
    }

    private Node[] createBlockNodes() {
        Node[] nodes = new Node[BLOCK_IDS.length];
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        for (int i = 0; i < BLOCK_IDS.length; i++) {
            if (Objects.equals(BLOCK_IDS[i], SPACER)) {
                nodes[i] = null;
            } else {
                Block block = blockRegistry.get(BLOCK_IDS[i]);
                if (block == null) {
                    log.warn("Unknown block {}", BLOCK_IDS[i]);
                } else {
                    nodes[i] = createBlockNode(block, buttonSize, BLOCK_IDS[i]);
                }
            }
        }
        return nodes;
    }

    private Container[] createMenuBlock() {
        int numPages = (BLOCK_IDS.length / (BlockSelectionState.MENUBLOCK_PAGESIZE_X * BlockSelectionState.MENUBLOCK_PAGESIZE_Y)) + 1;
        Container[] pages = new Container[numPages];

        for (int i = 0; i < pages.length; i++) {
            pages[i] = createBlockList(i);
        }

        return pages;
    }

    private Container createBlockList(int page) {
        Container blockList = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        final float sizeX = (BlockSelectionState.MENUBLOCK_PAGESIZE_X + 1) * (blockButtonSize + SPACING) - SPACING;
        final float sizeY = BlockSelectionState.MENUBLOCK_PAGESIZE_Y * (blockButtonSize + SPACING) - SPACING;
        final float posx = app.getCamera().getWidth() - SCREEN_MARGIN - sizeX;
        final float posy = (app.getCamera().getHeight() + sizeY) / 2f;
        blockList.setPreferredSize(new Vector3f(sizeX, sizeY, 0));
        blockList.setBackground(new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0)));
        blockList.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        blockList.addLight(new AmbientLight(ColorRGBA.White.mult(.5f)));

        MouseListener blockButtonMouseListener = new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    setSelectedBlockIndex(target.getUserData("index"));
                    hideBlockMenu();
                }
            }
        };

        int index = page * BlockSelectionState.MENUBLOCK_PAGESIZE_X * BlockSelectionState.MENUBLOCK_PAGESIZE_Y;
        for (int y = 0; y < BlockSelectionState.MENUBLOCK_PAGESIZE_Y; y++) {
            for (int x = 0; x < BlockSelectionState.MENUBLOCK_PAGESIZE_X; x++) {
                Container blockButton;
                if (index >= blocks.length || blocks[index] == null) {
                    // Filler button
                    blockButton = createButton("", blockButtonSize, blockButtonSize, null);

                } else {
                    Node blockNode = blocks[index];
                    blockButton = createBlockButton(blockNode, blockButtonSize, blockButtonMouseListener);
                    blockButton.setUserData("index", index);
                }
                blockList.addChild(blockButton, x, y);
                index++;
            }
        }

        nextButton = createButton("Next", blockButtonSize, blockButtonSize, new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    selectBlockMenuPage(selectedBlockPage + 1);
                }
                highlight(event.isPressed(), nextButton);
            }
        });
        blockList.addChild(nextButton, BlockSelectionState.MENUBLOCK_PAGESIZE_X + 1, 0);

        previousButton = createButton("Previous", blockButtonSize, blockButtonSize, new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    selectBlockMenuPage(selectedBlockPage - 1);
                }
                highlight(event.isPressed(), previousButton);
            }
        });
        blockList.addChild(previousButton, BlockSelectionState.MENUBLOCK_PAGESIZE_X + 1, BlockSelectionState.MENUBLOCK_PAGESIZE_Y - 1);

        blockList.setLocalTranslation(posx, posy, 1);
        return blockList;
    }

    private Container createBlockButton(Node blockNode, float size, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
        buttonContainer.setBackground(background);

        float boxSize = size * .5f;
        Node buttonNode = new Node("block-node");
        if (blockNode != null) {
            buttonNode.attachChild(blockNode);
        }
        buttonNode.addControl(new GuiControl("layer") {
            @Override
            public Vector3f getPreferredSize() {
                return new Vector3f(boxSize, boxSize, boxSize);
            }
        });
        buttonContainer.addChild(buttonNode);
        if (listener != null) {
            buttonContainer.addMouseListener(listener);
        }
        return buttonContainer;
    }

    private Node createBlockNode(Block block, float size, String name) {
        if (block == null) {
            return null;
        }

        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        chunk.addBlock(new Vec3i(0, 0, 0), block);
        chunk.update();

        ChunkMeshGenerator meshGenerator = BlocksConfig.getInstance().getChunkMeshGenerator();
        chunk.createNode(meshGenerator);

        Node node = null;
        if (chunk.getNode().getQuantity() > 0) {
            node = chunk.getNode();
            node.setName(name);
            Geometry geometry = (Geometry) node.getChild(0).clone();

            geometry.setLocalScale(size / 2f);
            geometry.setQueueBucket(RenderQueue.Bucket.Gui);
            geometry.setLocalTranslation(size / 2f, -size / 2f, 0);

            if (!TypeIds.SCALE.equals(block.getType())) {
                // For shape having (partially-) transparent materials, we override the cull-mode
                // material parameter because the faces are not properly ordered in the Gui bucket
                // and the cullmode Off won't work (except for scale and items)
                if (!block.getType().startsWith("item")) {
                    geometry.getMaterial().getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Back);
                }
                geometry.rotate(new Quaternion().fromAngleAxis(toRadians(25), Vector3f.UNIT_X));
                geometry.rotate(new Quaternion().fromAngleAxis(toRadians(-45), Vector3f.UNIT_Y));
            }

            int vc = geometry.getMesh().getVertexCount();
            FloatBuffer buf = BufferUtils.createFloatBuffer(vc * 4);
            for (int i = 0; i < vc; i++) {
                // White light color
                buf.put(1);
                buf.put(1);
                buf.put(1);
                // Max sun
                buf.put(240);
            }
            geometry.getMesh().setBuffer(VertexBuffer.Type.Color, 4, buf);

            node.attachChild(geometry);

        }
        return node;
    }

    private float toRadians(float degrees){
        return (degrees / 180) * FastMath.PI;
    }

    private Container createButton(String text, float sizeX, float sizeY, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setPreferredSize(new Vector3f(sizeX, sizeY, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
        buttonContainer.setBackground(background);

        Label label = buttonContainer.addChild(new Label(text));
        label.getFont().getPage(0).clearParam(APLHA_DISCARD_THRESHOLD);
        label.getFont().getPage(0).clearParam("VertexColor");

        // Center the text in the box.
        label.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        label.setColor(ColorRGBA.White);

        if (listener != null) {
            buttonContainer.addMouseListener(listener);
        }

        return buttonContainer;
    }

    private static String getName(IalonBlock block, String shape) {
        if (CUBE.equals(shape)) {
            return BlockIds.getName(block.getType(), shape);
        } else {
            return BlockIds.getName(block.getType(), shape, 0);
        }
    }

    @Override
    protected void onEnable() {
        if (blockSelectionButton.getParent() == null) {
            app.getGuiNode().attachChild(blockSelectionButton);
            app.getGuiNode().attachChild(historyButtons);
        }
        addKeyMappings();
    }

    @Override
    protected void onDisable() {
        if (blockSelectionButton.getParent() != null) {
            app.getGuiNode().detachChild(blockSelectionButton);
            app.getGuiNode().detachChild(historyButtons);
        }
        hideBlockMenu();
        deleteKeyMappings();
    }

    private void addKeyMappings() {
        // Nothing to do
    }

    private void deleteKeyMappings() {
        // Nothing to do
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
        // Nothing to do
    }

    @Override
    public void onAnalog(String name, float intensity, float tpf) {
        // Nothing to do
    }
}

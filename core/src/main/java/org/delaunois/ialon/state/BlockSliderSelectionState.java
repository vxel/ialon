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
import com.jme3.input.controls.TouchListener;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.input.event.MouseMotionEvent;
import com.jme3.input.event.TouchEvent;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.Camera;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.BatchNode;
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
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.anim.AbstractTween;
import com.simsilica.lemur.anim.Animation;
import com.simsilica.lemur.anim.Tween;
import com.simsilica.lemur.anim.TweenAnimation;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.component.SpringGridLayout;
import com.simsilica.lemur.core.GuiControl;
import com.simsilica.lemur.effect.AbstractEffect;
import com.simsilica.lemur.effect.EffectInfo;
import com.simsilica.lemur.event.DefaultMouseListener;
import com.simsilica.lemur.event.MouseListener;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.IalonBlock;
import org.delaunois.ialon.IalonConfig;

import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Objects;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import static com.rvandoosselaer.blocks.BlockIds.WATER_SOURCE;
import static com.rvandoosselaer.blocks.ShapeIds.CROSS_PLANE;
import static com.rvandoosselaer.blocks.ShapeIds.CUBE;
import static com.rvandoosselaer.blocks.ShapeIds.DOUBLE_SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.PLATE;
import static com.rvandoosselaer.blocks.ShapeIds.POLE;
import static com.rvandoosselaer.blocks.ShapeIds.PYRAMID;
import static com.rvandoosselaer.blocks.ShapeIds.SHORT_POLE;
import static com.rvandoosselaer.blocks.ShapeIds.SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE_HS;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE_NORTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_EAST;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_INNER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_OUTER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.WEDGE_SOUTH;
import static org.delaunois.ialon.IalonBlock.BED;
import static org.delaunois.ialon.IalonBlock.BEDPILLOW;
import static org.delaunois.ialon.IalonBlock.BIRCH_LEAVES;
import static org.delaunois.ialon.IalonBlock.BIRCH_LOG;
import static org.delaunois.ialon.IalonBlock.BIRCH_PLANKS;
import static org.delaunois.ialon.IalonBlock.BOOKS;
import static org.delaunois.ialon.IalonBlock.BRICKS;
import static org.delaunois.ialon.IalonBlock.COBBLESTONE;
import static org.delaunois.ialon.IalonBlock.COLOR_BLACK;
import static org.delaunois.ialon.IalonBlock.COLOR_BLUE;
import static org.delaunois.ialon.IalonBlock.COLOR_CYAN;
import static org.delaunois.ialon.IalonBlock.COLOR_GREEN;
import static org.delaunois.ialon.IalonBlock.COLOR_MAGENTA;
import static org.delaunois.ialon.IalonBlock.COLOR_ORANGE;
import static org.delaunois.ialon.IalonBlock.COLOR_RED;
import static org.delaunois.ialon.IalonBlock.COLOR_RED2;
import static org.delaunois.ialon.IalonBlock.COLOR_ROSE;
import static org.delaunois.ialon.IalonBlock.COLOR_YELLOW;
import static org.delaunois.ialon.IalonBlock.DIRT;
import static org.delaunois.ialon.IalonBlock.DRAWERS;
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
import static org.delaunois.ialon.IalonBlock.OVEN;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_LEAVES;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_LOG;
import static org.delaunois.ialon.IalonBlock.PALM_TREE_PLANKS;
import static org.delaunois.ialon.IalonBlock.PAVING;
import static org.delaunois.ialon.IalonBlock.RAIL;
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
import static org.delaunois.ialon.IalonKeyMapping.TOUCH;

@Slf4j
public class BlockSliderSelectionState extends BaseAppState {

    private static final String BLOCK_NAME = "blockName";
    private static final ColorRGBA BLOCK_AMBIENT_LIGHT = ColorRGBA.White.mult(.7f);
    private static final String SPACER = null;
    private static final String[] BLOCK_IDS = {
            getName(GRASS, CUBE),
            getName(GRASS_SNOW, CUBE),
            WATER_SOURCE,
            getName(WHITE_LIGHT, SHORT_POLE),
            getName(SCALE, SQUARE_NORTH),
            getName(RAIL, SQUARE_HS),
            getName(DIRT, CUBE),
            getName(BIRCH_LOG, CUBE),
            getName(BIRCH_PLANKS, CUBE),
            getName(BRICKS, CUBE),
            getName(COBBLESTONE, CUBE),
            getName(MOSSY_COBBLESTONE, CUBE),
            getName(GRAVEL, CUBE),
            getName(SLATE, CUBE),
            getName(TILE_RED, CUBE),
            getName(PALM_TREE_LOG, CUBE),
            getName(PALM_TREE_PLANKS, CUBE),
            getName(ROCK, CUBE),
            getName(OAK_LOG, CUBE),
            getName(OAK_PLANKS, CUBE),
            getName(SAND, CUBE),
            getName(SNOW, CUBE),
            getName(SPRUCE_LOG, CUBE),
            getName(SPRUCE_PLANKS, CUBE),
            getName(STONE_BRICKS, CUBE),
            getName(STONE_BRICKS2, CUBE),
            getName(STONE_BRICKS3, CUBE),
            getName(MOSSY_STONE_BRICKS, CUBE),
            getName(PAVING, CUBE),
            getName(METAL1, CUBE),
            getName(METAL2, CUBE),
            getName(METAL3, CUBE),
            getName(METAL4, CUBE),
            getName(METAL5, CUBE),
            getName(BIRCH_LEAVES, CUBE),
            getName(PALM_TREE_LEAVES, CUBE),
            getName(OAK_LEAVES, CUBE),
            getName(SPRUCE_LEAVES, CUBE),
            getName(WINDOW, CUBE),
            getName(ITEM_GRASS, CROSS_PLANE),
            getName(ITEM_SEAWEED, CROSS_PLANE),
            getName(COLOR_BLACK, CUBE),
            getName(COLOR_BLUE, CUBE),
            getName(COLOR_CYAN, CUBE),
            getName(COLOR_GREEN, CUBE),
            getName(COLOR_MAGENTA, CUBE),
            getName(COLOR_ORANGE, CUBE),
            getName(COLOR_RED, CUBE),
            getName(COLOR_RED2, CUBE),
            getName(COLOR_ROSE, CUBE),
            getName(COLOR_YELLOW, CUBE),
            getName(BOOKS, CUBE),
            getName(DRAWERS, CUBE),
            getName(OVEN, CUBE),
            getName(BED, SLAB),
            getName(BEDPILLOW, SLAB),
    };

    private static final String[] SHAPES = {
            CUBE,
            DOUBLE_SLAB,
            SLAB,
            PLATE,
            WEDGE_SOUTH,
            PYRAMID,
            POLE,
            STAIRS_EAST,
            STAIRS_INNER_CORNER_SOUTH,
            STAIRS_OUTER_CORNER_SOUTH,
            SQUARE,
            CROSS_PLANE
    };

    private static final int SCREEN_MARGIN = 30;
    private static final int SPACING = 10;
    private static final int BLOCK_ROWS = 3;

    private static final int BLOCK_HISTORY_SIZE = 3;
    private static final String APLHA_DISCARD_THRESHOLD = "AlphaDiscardThreshold";
    private Node[] blocks;

    private SimpleApplication app;
    private BitmapFont guiFont;

    // Buttons
    private Container blockSelectionButton;
    private final Container[] historyButton = new Container[BLOCK_HISTORY_SIZE];

    // Container
    private Node menuBlock;
    private Node submenuBlock;
    private final Node historyButtons = new Node();
    private final Node[] lastSelectedBlockNode = new Node[BLOCK_HISTORY_SIZE];
    private final String[] history = new String[BLOCK_HISTORY_SIZE];

    private String selectedBlockName = null;
    private Node selectedBlockNode;
    private ScrollListener scrollListener = null;
    private ContainerScroller containerScroller = null;

    private float buttonSize = 100;
    private float blockButtonSize = 100;
    private final IalonConfig config;

    public BlockSliderSelectionState(IalonConfig config) {
        this.config = config;
    }

    @Override
    public void initialize(Application app) {
        this.app = (SimpleApplication) app;

        for (int i = 0; i < lastSelectedBlockNode.length; i++) {
            lastSelectedBlockNode[i] = new Node();
        }
        Arrays.fill(history, null);

        buttonSize = app.getCamera().getHeight() / 8f;
        containerScroller = new ContainerScroller(app.getCamera(), blockButtonSize);
        scrollListener = new ScrollListener(containerScroller);
        blockButtonSize = buttonSize;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        blocks = createBlockNodes();
        menuBlock = createBlockTypeSelectionPopup();

        // Create history buttons
        for (int i = 0; i < BLOCK_HISTORY_SIZE; i++) {
            int index = i;
            historyButton[i] = createBlockButton(lastSelectedBlockNode[i], buttonSize, new DefaultMouseListener() {
                @Override
                public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                    event.setConsumed();
                    if (event.isPressed() && history[index] != null) {
                        setSelectedBlockName(history[index]);
                    }
                }
            }, true);
            historyButton[i].setLocalTranslation(i * (buttonSize + SPACING), 0, 1);
            historyButtons.attachChild(historyButton[i]);
        }
        historyButtons.setLocalTranslation(app.getCamera().getWidth() / 2f - (BLOCK_HISTORY_SIZE / 2f) * buttonSize - SPACING, SCREEN_MARGIN + buttonSize, 1);
        historyButtons.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        historyButtons.addLight(new AmbientLight(BLOCK_AMBIENT_LIGHT));

        // Create selection button
        setSelectedBlockName(config.getSelectedBlockName());
        blockSelectionButton = createBlockButton(selectedBlockNode, buttonSize, new DefaultMouseListener() {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                event.setConsumed();
                if (event.isPressed()) {
                    toggleBlockMenu();
                }
                highlight(event.isPressed(), blockSelectionButton);
            }
        }, true);
        blockSelectionButton.setLocalTranslation(
                (app.getCamera().getWidth() - buttonSize) / 2.0f,
                (float)app.getCamera().getHeight() - SCREEN_MARGIN,
                1
        );
        blockSelectionButton.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        blockSelectionButton.addLight(new AmbientLight(BLOCK_AMBIENT_LIGHT));
    }

    private void highlight(boolean isPressed, Container button) {
        if (isPressed) {
            ((QuadBackgroundComponent) button.getBackground()).getColor().set(0.5f, 0.5f, 0.5f, 0.5f);
        } else {
            ((QuadBackgroundComponent) button.getBackground()).getColor().set(0, 0, 0, 0.5f);
        }
    }

    @Override
    protected void cleanup(Application app) {
        // Nothing to do
    }

    public void resize() {
        hideBlockMenu();
        menuBlock = createBlockTypeSelectionPopup();
        setSelectedBlockName(selectedBlockName);
        blockSelectionButton.setLocalTranslation(
                (app.getCamera().getWidth() - buttonSize) / 2.0f,
                (float)app.getCamera().getHeight() - SCREEN_MARGIN,
                1);
        historyButtons.setLocalTranslation(
                app.getCamera().getWidth() / 2f - (BLOCK_HISTORY_SIZE / 2f) * buttonSize - SPACING,
                SCREEN_MARGIN + buttonSize,
                1);
    }

    public void hideBlockMenu() {
        if (menuBlock.getParent() != null) {
            menuBlock.removeFromParent();
            if (submenuBlock != null) {
                submenuBlock.removeFromParent();
            }
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(true);
            app.getStateManager().getState(ButtonManagerState.class).setEnabled(true);
            app.getStateManager().getState(SettingsState.class).setEnabled(true);
        }
        app.getInputManager().removeListener(scrollListener);
    }

    public void showBlockMenu() {
        if (menuBlock.getParent() == null) {
            app.getGuiNode().attachChild(menuBlock);
            app.getStateManager().getState(PlayerState.class).setTouchEnabled(false);
            app.getStateManager().getState(ButtonManagerState.class).setEnabled(false);
            app.getStateManager().getState(SettingsState.class).setEnabled(false);
            app.getInputManager().addListener(scrollListener, TOUCH);
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
        return BlocksConfig.getInstance().getBlockRegistry().get(selectedBlockName);
    }

    private void setSelectedBlockName(String blockName) {
        if (blockName == null) {
            log.info("Block is null !");
            blockName = getName(GRASS, CUBE);
        }

        log.info("Selecting {}", blockName);

        selectedBlockName = blockName;
        Block selectedBlock = getSelectedBlock();
        selectedBlockNode = updateBlockNode(selectedBlockNode, selectedBlockName);
        config.setSelectedBlock(selectedBlock);
        config.setSelectedBlockName(blockName);
        updateHistory(blockName);
    }

    private void updateHistory(String blockName) {
        boolean found = false;
        for (int i = 0; i < history.length; i++) {
            highlight(false, historyButton[i]);
            if (blockName.equals(history[i])) {
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
        history[0] = blockName;

        for (int i = 0; i < history.length; i++) {
            if (history[i] != null) {
                lastSelectedBlockNode[i] = updateBlockNode(lastSelectedBlockNode[i], history[i]);
            }
        }
        highlight(true, historyButton[0]);
    }

    private Node updateBlockNode(Node nodeToRemove, String blockNameToAdd) {
        Block block = BlocksConfig.getInstance().getBlockRegistry().get(blockNameToAdd);
        Node node =  createBlockNode(block, buttonSize, blockNameToAdd);
        updateBlockNode(nodeToRemove, node);
        return node;
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

    /**
     * Creates the block selection popup, showing the block types
     * @return the Node
     */
    private Node createBlockTypeSelectionPopup() {
        Container blockList = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        blockList.setName("BlockList");

        MouseListener blockButtonMouseListener = new DefaultMouseListener(20, 20) {
            @Override
            public void click(MouseButtonEvent event, Spatial target, Spatial capture) {
                if (target != null) {
                    Integer blockIndex = target.getUserData("index");
                    if (submenuBlock != null) {
                        submenuBlock.removeFromParent();
                        submenuBlock = null;
                    }
                    if (blockIndex != null) {
                        submenuBlock = createBlockShapeSelectionPopup(blockIndex);
                        if (submenuBlock != null) {
                            app.getGuiNode().attachChild(submenuBlock);
                        }
                    }
                    event.setConsumed();
                }
            }
        };

        for (int index = 0; index < blocks.length; index++) {
            Node blockNode = blocks[index];
            Container blockButton = createBlockButton(blockNode, blockButtonSize, blockButtonMouseListener, false);
            QuadBackgroundComponent quadBackgroundComponent = new QuadBackgroundComponent(new ColorRGBA(0f, 0, 0, 0f));
            quadBackgroundComponent.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
            blockButton.setBackground(quadBackgroundComponent);
            blockButton.setUserData("index", index);
            blockList.addChild(blockButton, index / BLOCK_ROWS, index % BLOCK_ROWS);
        }

        final float posy = app.getCamera().getHeight() - blockButtonSize - SCREEN_MARGIN * 2f;
        final float popupWidth = (float)Math.ceil((float) blocks.length / (float) BLOCK_ROWS) * blockButtonSize;

        Container blockListParent = new Container();
        blockListParent.setName("BlockListParent");
        if (popupWidth < app.getCamera().getWidth()) {
            // Block list smaller than screen : Center the block list
            blockListParent.setLocalTranslation((app.getCamera().getWidth() - popupWidth) / 2f, posy, 1f);
        } else {
            // Block list larger than screen : align left
            blockListParent.setLocalTranslation(0, posy, 1f);
        }
        blockListParent.addChild(blockList);

        Panel background = new Panel();
        background.setName("BlockButtonBackground");
        background.setPreferredSize(new Vector3f(app.getCamera().getWidth(), blockButtonSize * BLOCK_ROWS, 0));
        QuadBackgroundComponent quadBackgroundComponent = new QuadBackgroundComponent(new ColorRGBA(0f, 0, 0, 0.5f));
        quadBackgroundComponent.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
        background.setBackground(quadBackgroundComponent);
        background.setLocalTranslation(0, posy, -1f);

        containerScroller.setContainer(blockList);

        background.addMouseListener(scrollListener);

        BatchNode batchNode = new BatchNode();
        batchNode.attachChild(blockListParent);
        batchNode.attachChild(background);
        batchNode.batch();
        batchNode.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        batchNode.addLight(new AmbientLight(BLOCK_AMBIENT_LIGHT));

        return batchNode;
    }

    /**
     * Creates the block shape selection popup, showing the block shapes available for the
     * given block
     * @param blockIndex the block index
     * @return the Node
     */
    private Node createBlockShapeSelectionPopup(int blockIndex) {
        Container blockList = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        blockList.setName("SubBlockList");
        String blockName = BLOCK_IDS[blockIndex];

        MouseListener blockButtonMouseListener = new DefaultMouseListener(20, 20) {
            @Override
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                log.info("Click event={} target={}", event, target);
                if (event.isReleased()) {
                    String blockName = target.getUserData(BLOCK_NAME);
                    if (blockName == null) {
                        log.warn("Wrong Click Target {}", target.getName());

                    } else {
                        setSelectedBlockName(target.getUserData(BLOCK_NAME));
                        hideBlockMenu();
                        event.setConsumed();
                    }
                }
            }
        };

        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        for (int index = 0; index < SHAPES.length; index++) {
            String name = BlockIds.getName(blockName, SHAPES[index], 0);
            Block block = blockRegistry.get(name);
            if (block != null) {
                Node blockNode = createBlockNode(block, buttonSize, name);
                Container blockButton = createBlockButton(blockNode, blockButtonSize, blockButtonMouseListener, false);
                QuadBackgroundComponent buttonBackground = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0f));
                // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
                buttonBackground.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
                blockButton.setBackground(buttonBackground);
                blockButton.setUserData(BLOCK_NAME, name);
                blockList.addChild(blockButton, index, 1);
            }
        }

        int numBlockTypes = blockList.getChildren().size();

        BatchNode batchNode;
        if (numBlockTypes == 1) {
            // Only one possible block : select it
            setSelectedBlockName(blockList.getChild(0).getUserData(BLOCK_NAME));
            hideBlockMenu();
            batchNode = null;

        } else {
            // Multiple possible blocks : show shape selection popup
            final float posx = (app.getCamera().getWidth() - numBlockTypes * blockButtonSize) / 2f;
            final float posy = app.getCamera().getHeight() - blockButtonSize - SCREEN_MARGIN * 3f - blockButtonSize * BLOCK_ROWS;

            Container blockListParent = new Container();
            blockListParent.setName("SubBlockListParent");
            blockListParent.setLocalTranslation(posx, posy, -1f);
            blockListParent.addChild(blockList);

            Panel background = new Panel();
            background.setName("SubBlockButtonBackground");
            background.setPreferredSize(new Vector3f(app.getCamera().getWidth(), blockButtonSize, 0));
            QuadBackgroundComponent quadBackgroundComponent = new QuadBackgroundComponent(new ColorRGBA(0f, 0, 0, 0.5f));
            quadBackgroundComponent.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
            background.setBackground(quadBackgroundComponent);
            background.setLocalTranslation(0, posy, -1f);

            batchNode = new BatchNode();
            batchNode.attachChild(blockListParent);
            batchNode.attachChild(background);
            batchNode.batch();
            batchNode.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
            batchNode.addLight(new AmbientLight(BLOCK_AMBIENT_LIGHT));
        }

        return batchNode;
    }

    /**
     * Creates a UI button with a given 3D Block inside
     * @param blockNode the block node (must be generated)
     * @param size the preferred size of the button
     * @param listener the mouse listener to assign to the button
     * @param withBackground true to add a backgroung to the button, false to skip the background
     * @return the button - a Lemur Container
     */
    private Container createBlockButton(Node blockNode, float size, MouseListener listener, boolean withBackground) {
        Container buttonContainer = new Container();
        buttonContainer.setName("BlockButtonContainer");
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));

        if (withBackground) {
            QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
            // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
            background.getMaterial().getMaterial().clearParam(APLHA_DISCARD_THRESHOLD);
            buttonContainer.setBackground(background);
        }

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

    private float toRadians(float degrees) {
        return (degrees / 180) * FastMath.PI;
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
    }

    @Override
    protected void onDisable() {
        if (blockSelectionButton.getParent() != null) {
            app.getGuiNode().detachChild(blockSelectionButton);
            app.getGuiNode().detachChild(historyButtons);
        }
        hideBlockMenu();
    }

    /**
     * Listens to two types of events
     * - Mouse Scroll
     * - Touch Scroll
     * and calls the given ContainerScroller to apply the scroll.
     * For touch scroll, a "force impulse" is given to the translation
     */
    private static final class ScrollListener extends DefaultMouseListener implements TouchListener {

        private final ContainerScroller containerScroller;
        private final Vector3f tmpVector = new Vector3f();

        private ScrollListener(ContainerScroller containerScroller) {
            this.containerScroller = containerScroller;
        }

        @Override
        public void mouseMoved(MouseMotionEvent event, Spatial target, Spatial capture) {
            if (event.getDeltaWheel() == 0) {
                return;
            }
            event.setConsumed();
            float deltaX = (int)(event.getDeltaWheel() / containerScroller.getMargin()) * containerScroller.getMargin();
            containerScroller.scroll(deltaX);
        }

        @Override
        public void onTouch(String name, TouchEvent event, float tpf) {
            if (event.getType() == TouchEvent.Type.SCROLL && collides(event.getX(), event.getY())) {
                containerScroller.scrollImpulse(event.getDeltaX(), tpf);
                event.setConsumed();
            }
        }

        public boolean collides(float x, float y) {
            tmpVector.setX(x);
            tmpVector.setY(y);
            return containerScroller.getContainer().getWorldBound().contains(tmpVector);
        }

    }

    private static class ContainerScroller {

        private static final float EFFECT_LEN = 0.5f;

        @Getter
        private Container container;

        @Getter
        private final Camera camera;

        @Getter
        private final float margin;

        private final ScrollEffect scrollEffect = new ScrollEffect();

        public ContainerScroller(Camera camera, float margin) {
            this.camera = camera;
            this.margin = margin;
        }

        public void setContainer(Container container) {
            this.container = container;
            this.scrollEffect.setLength(EFFECT_LEN);
            container.addEffect("scroll", scrollEffect);
        }

        public void scroll(float deltaX) {
            if (container != null) {
                Vector3f location = container.getLocalTranslation();
                float min = camera.getWidth() - container.getSize().getX() - margin;
                float newlocation = Math.max(Math.min(location.x + deltaX, margin), min);
                container.setLocalTranslation(container.getLocalTranslation().setX(newlocation));
            }
        }

        public void scrollImpulse(float deltaX, float tpf) {
            if (container != null) {
                scrollEffect.setMin(camera.getWidth() - container.getSize().getX() - margin);
                scrollEffect.setMax(margin);
                scrollEffect.setForce(deltaX / tpf / 100);
                container.runEffect("scroll");
            }
        }

    }

    @Getter
    @Setter
    public static class ScrollEffect extends AbstractEffect<Panel> {

        private float force;
        private float length = 1;
        private float min;
        private float max;

        @Override
        public Animation create(Panel target, EffectInfo existing) {
            Tween impulse = new ImpulseSpatial(target, force, length, min, max);
            return new TweenAnimation(impulse);
        }
    }

    public static class ImpulseSpatial extends AbstractTween {

        private final Spatial target;
        private final float force;
        private final float min;
        private final float max;
        private final Vector3f translation;

        public ImpulseSpatial(Spatial target, float force, float length, float min, float max) {
            super(length);
            this.target = target;
            this.force = force;
            this.min = min;
            this.max = max;
            this.translation = new Vector3f(target.getLocalTranslation());
        }

        @Override
        protected void doInterpolate(double t) {
            translation.x = Math.max(Math.min(translation.x + (1 - (float)t) * force, max), min);
            target.setLocalTranslation(translation);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[target=" + target + ", force=" + force + ", length=" + getLength() + "]";
        }
    }
}

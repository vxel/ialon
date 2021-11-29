package org.delaunois.ialon.state;

import com.jme3.app.Application;
import com.jme3.app.state.BaseAppState;
import com.jme3.font.BitmapFont;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.AnalogListener;
import com.jme3.input.event.MouseButtonEvent;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
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

import org.delaunois.ialon.Ialon;
import org.delaunois.ialon.IalonBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.Objects;

import static com.rvandoosselaer.blocks.BlockIds.BIRCH_LEAVES;
import static com.rvandoosselaer.blocks.BlockIds.BIRCH_LOG;
import static com.rvandoosselaer.blocks.BlockIds.BIRCH_PLANKS;
import static com.rvandoosselaer.blocks.BlockIds.BRICKS;
import static com.rvandoosselaer.blocks.BlockIds.COBBLESTONE;
import static com.rvandoosselaer.blocks.BlockIds.DIRT;
import static com.rvandoosselaer.blocks.BlockIds.GRASS;
import static com.rvandoosselaer.blocks.BlockIds.GRASS_SNOW;
import static com.rvandoosselaer.blocks.BlockIds.GRAVEL;
import static com.rvandoosselaer.blocks.BlockIds.ITEM_GRASS;
import static com.rvandoosselaer.blocks.BlockIds.MOSSY_COBBLESTONE;
import static com.rvandoosselaer.blocks.BlockIds.MOSSY_STONE_BRICKS;
import static com.rvandoosselaer.blocks.BlockIds.OAK_LEAVES;
import static com.rvandoosselaer.blocks.BlockIds.OAK_LOG;
import static com.rvandoosselaer.blocks.BlockIds.OAK_PLANKS;
import static com.rvandoosselaer.blocks.BlockIds.PALM_TREE_LEAVES;
import static com.rvandoosselaer.blocks.BlockIds.PALM_TREE_LOG;
import static com.rvandoosselaer.blocks.BlockIds.PALM_TREE_PLANKS;
import static com.rvandoosselaer.blocks.BlockIds.ROCK;
import static com.rvandoosselaer.blocks.BlockIds.SAND;
import static com.rvandoosselaer.blocks.BlockIds.SCALE;
import static com.rvandoosselaer.blocks.BlockIds.SNOW;
import static com.rvandoosselaer.blocks.BlockIds.SPRUCE_LEAVES;
import static com.rvandoosselaer.blocks.BlockIds.SPRUCE_LOG;
import static com.rvandoosselaer.blocks.BlockIds.SPRUCE_PLANKS;
import static com.rvandoosselaer.blocks.BlockIds.STONE_BRICKS;
import static com.rvandoosselaer.blocks.BlockIds.WHITE_CUBE_LIGHT;
import static com.rvandoosselaer.blocks.BlockIds.WINDOW;
import static com.rvandoosselaer.blocks.ShapeIds.CUBE;
import static com.rvandoosselaer.blocks.ShapeIds.DOUBLE_SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.LIQUID;
import static com.rvandoosselaer.blocks.ShapeIds.PLATE;
import static com.rvandoosselaer.blocks.ShapeIds.POLE;
import static com.rvandoosselaer.blocks.ShapeIds.PYRAMID;
import static com.rvandoosselaer.blocks.ShapeIds.SLAB;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE;
import static com.rvandoosselaer.blocks.ShapeIds.SQUARE_NORTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_EAST;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_INNER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.STAIRS_OUTER_CORNER_SOUTH;
import static com.rvandoosselaer.blocks.ShapeIds.WEDGE_SOUTH;
import static com.rvandoosselaer.blocks.TypeIds.WATER;
import static org.delaunois.ialon.IalonBlock.METAL1;
import static org.delaunois.ialon.IalonBlock.METAL2;
import static org.delaunois.ialon.IalonBlock.METAL3;
import static org.delaunois.ialon.IalonBlock.METAL4;
import static org.delaunois.ialon.IalonBlock.METAL5;
import static org.delaunois.ialon.IalonBlock.SLATE;
import static org.delaunois.ialon.IalonBlock.TILE_RED;


public class BlockSelectionState extends BaseAppState implements ActionListener, AnalogListener {

    private static final Logger LOG = LoggerFactory.getLogger(BlockSelectionState.class.getName());

    public static String SPACER = null;
    public static String[] BLOCK_IDS = {
            getName(GRASS, CUBE),
            getName(GRASS, DOUBLE_SLAB),
            getName(GRASS, SLAB),
            getName(GRASS_SNOW, CUBE),
            getName(GRASS_SNOW, DOUBLE_SLAB),
            getName(GRASS_SNOW, SLAB),
            getName(WATER, LIQUID),
            WHITE_CUBE_LIGHT,
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
            ITEM_GRASS,
            SPACER,

    };

    public Node[] blocks;

    private Ialon app;
    private BitmapFont guiFont;
    private Container menuBlock;
    private Container[] menuBlockPages;
    private Container buttonBlockSelection;
    private int selectedBlockPage = 0;
    private int selectedBlockIndex = 0;
    private Node selectedBlockNode;

    private int BUTTON_SIZE = 100;
    private int BLOCK_BUTTON_SIZE = 100;
    private static final int SCREEN_MARGIN = 30;
    private static final int SPACING = 10;
    private static final int MENUBLOCK_PAGESIZE_X = 10;
    private static final int MENUBLOCK_PAGESIZE_Y = 4;

    @Override
    public void initialize(Application app) {
        this.app = (Ialon) app;

        BUTTON_SIZE = app.getCamera().getHeight() / 8;
        BLOCK_BUTTON_SIZE = BUTTON_SIZE;

        if (guiFont == null) {
            guiFont = app.getAssetManager().loadFont("Interface/Fonts/Default.fnt");
        }

        blocks = createBlockNodes();
        menuBlockPages = createMenuBlock(MENUBLOCK_PAGESIZE_X, MENUBLOCK_PAGESIZE_Y);
        menuBlock = menuBlockPages[0];
        setSelectedBlockIndex(selectedBlockIndex);
        buttonBlockSelection = createBlockButton(selectedBlockNode, BUTTON_SIZE, new DefaultMouseListener() {
            public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                if (event.isPressed()) {
                    toggleBlockMenu();
                }
            }
        });
        buttonBlockSelection.setLocalTranslation(app.getCamera().getWidth() - BUTTON_SIZE - SCREEN_MARGIN, (app.getCamera().getHeight() + BUTTON_SIZE) / 2f, 1);
    }

    @Override
    protected void cleanup(Application app) {
    }

    public void selectBlockMenuPage(int page) {
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
        }
    }

    public void toggleBlockMenu() {
        if (menuBlock.getParent() == null) {
            app.getGuiNode().attachChild(menuBlock);
        } else {
            menuBlock.removeFromParent();
        }
    }

    public Block getSelectedBlock() {
        return BlocksConfig.getInstance().getBlockRegistry().get(BLOCK_IDS[selectedBlockIndex]);
    }

    private void setSelectedBlockIndex(int index) {
        selectedBlockIndex = index;
        LOG.info("Selecting {}", BLOCK_IDS[index]);

        Node parent = null;
        if (selectedBlockNode != null) {
            parent = selectedBlockNode.getParent();
            selectedBlockNode.removeFromParent();
        }
        selectedBlockNode = (Node)blocks[index].clone();
        if (parent != null) {
            parent.attachChild(selectedBlockNode);
        }
    }

    private Node[] createBlockNodes() {
        Node[] blocks = new Node[BLOCK_IDS.length];
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        for (int i = 0; i < BLOCK_IDS.length; i++) {
            blocks[i] = Objects.equals(BLOCK_IDS[i], SPACER) ? null : createBlockNode(blockRegistry.get(BLOCK_IDS[i]), BUTTON_SIZE);
        }
        return blocks;
    }

    private Container[] createMenuBlock(int pageSizeX, int pageSizeY) {
        int numPages = (BLOCK_IDS.length / (pageSizeX * pageSizeY)) + 1;
        Container[] pages = new Container[numPages];

        for (int i = 0; i < pages.length; i++) {
            pages[i] = createBlockList(i, pageSizeX, pageSizeY);
        }

        return pages;
    }

    private Container createBlockList(int page, int pageSizeX, int pageSizeY) {
        Container blockList = new Container(new SpringGridLayout(Axis.X, Axis.Y));
        final int sizeX = (pageSizeX + 1) * (BLOCK_BUTTON_SIZE + SPACING) - SPACING;
        final int sizeY = pageSizeY * (BLOCK_BUTTON_SIZE + SPACING) - SPACING;
        final float posx = app.getCamera().getWidth() - SCREEN_MARGIN - sizeX;
        final float posy = (app.getCamera().getHeight() + sizeY) / 2f;
        blockList.setPreferredSize(new Vector3f(sizeX, sizeY, 0));
        blockList.setBackground(new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0)));

        int index = page * pageSizeX * pageSizeY;
        for (int y = 0; y < pageSizeY; y++) {
            for (int x = 0; x < pageSizeX; x++) {
                Container blockButton;
                if (index >= blocks.length || blocks[index] == null) {
                    // Filler button
                    blockButton = createButton("", BLOCK_BUTTON_SIZE, BLOCK_BUTTON_SIZE, null);

                } else {
                    Node blockNode = blocks[index];
                    int finalIndex = index;
                    blockButton = createBlockButton(blockNode, BLOCK_BUTTON_SIZE,
                            new DefaultMouseListener() {
                                public void mouseButtonEvent(MouseButtonEvent event, Spatial target, Spatial capture) {
                                    if (event.isPressed()) {
                                        setSelectedBlockIndex(finalIndex);
                                        hideBlockMenu();
                                    }
                                }
                            });
                }
                blockList.addChild(blockButton, x, y);
                index++;
            }
        }

        Container next = createButton("Next", BLOCK_BUTTON_SIZE, BLOCK_BUTTON_SIZE, new DefaultMouseListener() {
            protected void click(MouseButtonEvent event, Spatial target, Spatial capture) {
                selectBlockMenuPage(selectedBlockPage + 1);
            }
        });
        blockList.addChild(next, pageSizeX + 1, 0);

        Container previous = createButton("Previous", BLOCK_BUTTON_SIZE, BLOCK_BUTTON_SIZE, new DefaultMouseListener() {
            protected void click(MouseButtonEvent event, Spatial target, Spatial capture) {
                selectBlockMenuPage(selectedBlockPage - 1);
            }
        });
        blockList.addChild(previous, pageSizeX + 1, pageSizeY - 1);

        blockList.setLocalTranslation(posx, posy, 1);
        return blockList;
    }

    private Container createBlockButton(Node blockNode, float size, MouseListener listener) {
        Container buttonContainer = new Container();
        buttonContainer.setPreferredSize(new Vector3f(size, size, 0));
        QuadBackgroundComponent background = new QuadBackgroundComponent(new ColorRGBA(0, 0, 0, 0.5f));
        // Clear AlphaDiscardThreshold because it is useless here and generates a new specific Shader
        background.getMaterial().getMaterial().clearParam("AlphaDiscardThreshold");
        buttonContainer.setBackground(background);

        float boxSize = size * .5f;
        Node buttonNode = new Node("block-node");
        buttonNode.attachChild(blockNode);
        buttonNode.addControl(new GuiControl("layer") {
            @Override
            public Vector3f getPreferredSize() {
                return new Vector3f(boxSize, boxSize, boxSize);
            }
        });
        buttonNode.addLight(new DirectionalLight(new Vector3f(1, -1, 1)));
        buttonNode.addLight(new AmbientLight(ColorRGBA.White.mult(.5f)));
        buttonContainer.addChild(buttonNode);
        if (listener != null) {
            buttonContainer.addMouseListener(listener);
        }
        return buttonContainer;
    }

    private Node createBlockNode(Block block, float size) {
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
            Geometry geometry = (Geometry) node.getChild(0).clone();

            geometry.setLocalScale(size / 2f);
            geometry.setQueueBucket(RenderQueue.Bucket.Gui);
            geometry.setLocalTranslation(size / 2f, -size / 2f, 0);

            if (!TypeIds.SCALE.equals(block.getType())) {
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
        background.getMaterial().getMaterial().clearParam("AlphaDiscardThreshold");
        buttonContainer.setBackground(background);

        Label label = buttonContainer.addChild(new Label(text));
        label.getFont().getPage(0).clearParam("AlphaDiscardThreshold");

        // Center the text in the box.
        label.setInsetsComponent(new DynamicInsetsComponent(0.5f, 0.5f, 0.5f, 0.5f));
        label.setColor(ColorRGBA.White);

        if (listener != null) {
            buttonContainer.addMouseListener(listener);
        }

        return buttonContainer;
    }

    private static String getName(String block, String shape) {
        return BlockIds.getName(block, shape);
    }

    private static String getName(IalonBlock block, String shape) {
        return BlockIds.getName(block.getName(), shape);
    }

    @Override
    protected void onEnable() {
        if (buttonBlockSelection.getParent() == null) {
            app.getGuiNode().attachChild(buttonBlockSelection);
        }
        addKeyMappings();
    }

    @Override
    protected void onDisable() {
        if (buttonBlockSelection.getParent() != null) {
            app.getGuiNode().detachChild(buttonBlockSelection);
        }
        hideBlockMenu();
        deleteKeyMappings();
    }

    @Override
    public void update(float tpf) {
    }

    private void addKeyMappings() {
    }

    private void deleteKeyMappings() {
    }

    @Override
    public void onAction(String name, boolean isPressed, float tpf) {
    }

    @Override
    public void onAnalog(String name, float intensity, float tpf) {
    }
}

package org.delaunois.ialon.blocks;

/**
 * Keys of the block types the engine dispatches on, or that a {@link BlockIds} entry aliases. The
 * full type catalog is defined declaratively in {@code Blocks/blocks.yaml} and registered at startup
 * by {@code IalonBlockCatalog}; this interface only holds the subset that Java code references
 * symbolically (rendering/gameplay branches on the type, or a {@link BlockIds} cube-form alias points
 * here). A type that is only ever named from the YAML does not belong here.
 * Use these keys to retrieve types from {@link TypeRegistry#get(String)}.
 *
 * @author: rvandoosselaer
 */
public interface TypeIds {

    String BIRCH_LOG = "birch_log";
    String BIRCH_LEAVES = "birch_leaves";
    String BRICKS = "bricks";
    String COBBLESTONE = "cobblestone";
    String DIRT = "dirt";
    String GRAVEL_DARK = "gravel_dark";
    String GRASS = "grass";
    String PALM_TREE_LOG = "palm_tree_log";
    String PALM_TREE_LEAVES = "palm_tree_leaves";
    String ROCK = "rock";
    String OAK_LOG = "oak_log";
    String OAK_LEAVES = "oak_leaves";
    String OAK_PLANKS = "oak_planks";
    String SAND = "sand";
    String SNOW = "snow";
    String SPRUCE_LOG = "spruce_log";
    String SPRUCE_LEAVES = "spruce_leaves";
    String WATER = "water";
    String ITEM_GRASS = "item_grass";
    String SCALE = "scale";
    String RAIL = "rail";
    String RAIL_CURVED = "rail_curved";
    String RAIL_SLOPE = "rail_slope";
    String PHANTOM = "phantom";
    String FIRE = "fire";
    String LAVA = "lava";
    String DOOR_LEFT = "door_left";
    String DOOR_RIGHT = "door_right";
    String DOOR_LEFT_METAL = "door_left_metal";
    String DOOR_RIGHT_METAL = "door_right_metal";
}

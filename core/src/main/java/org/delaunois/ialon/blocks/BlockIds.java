package org.delaunois.ialon.blocks;

/**
 * Keys of the specific blocks that Java code references by name, plus the {@link #getName} helper used
 * to build block names at runtime. The full block catalog is defined declaratively in
 * {@code Blocks/blocks.yaml} and registered at startup by {@code IalonBlockCatalog} - this interface is
 * NOT that catalog and does not enumerate the (type x shape x liquid-level) fan-out. It only holds:
 * the empty block ({@link #NONE}), the liquid-source block ids that are not type ids
 * ({@link #WATER_SOURCE}, {@link #LAVA_SOURCE}), and the handful of cube-form blocks the code names
 * directly (e.g. terrain generation). A cube-form block id equals its type id, so those entries alias
 * {@link TypeIds} to document intent ("the placeable cube block", not "the material").
 *
 * The convention is that the name of the block is a concatenation of the type and shape with a dash or
 * hyphen in between. When the shape of the block is the default shape (ShapeIds.CUBE) the shape can be
 * left out in the name.
 *
 * Some examples:
 * - block (type: grass, shape: cube)
 *   name: grass
 * - block (type: dirt, shape: cube_west)
 *   name: dirt-cube_west
 * - block (type: mossy_cobblestone, shape: stairs_inverted_inner_corner_east)
 *   name: mossy_cobblestone-stairs_inverted_inner_corner_east
 *
 * @author: rvandoosselaer
 */
public interface BlockIds {

    String NONE = "";

    String BIRCH_LOG = TypeIds.BIRCH_LOG;
    String BIRCH_LEAVES = TypeIds.BIRCH_LEAVES;

    String COBBLESTONE = TypeIds.COBBLESTONE;

    String DIRT = TypeIds.DIRT;

    String GRAVEL_DARK = TypeIds.GRAVEL_DARK;

    String GRASS = TypeIds.GRASS;
    String GRASS_SNOW = TypeIds.GRASS_SNOW;
    String GRASS_TOUNDRA = TypeIds.GRASS_TOUNDRA;

    String PALM_TREE_LOG = TypeIds.PALM_TREE_LOG;
    String PALM_TREE_LEAVES = TypeIds.PALM_TREE_LEAVES;

    String ROCK = TypeIds.ROCK;

    String OAK_LOG = TypeIds.OAK_LOG;
    String OAK_LEAVES = TypeIds.OAK_LEAVES;

    String SAND = TypeIds.SAND;

    String SNOW = TypeIds.SNOW;

    String SPRUCE_LOG = TypeIds.SPRUCE_LOG;
    String SPRUCE_LEAVES = TypeIds.SPRUCE_LEAVES;

    String WATER = TypeIds.WATER;
    String WATER_SOURCE = "water_source";

    String LAVA_SOURCE = "lava_source";

    String RAIL = TypeIds.RAIL;
    String RAIL_CURVED = TypeIds.RAIL_CURVED;

    static String getName(String type, String shape) {
        return ShapeIds.CUBE.equals(shape) ? type : type + "-" + shape;
    }
    static String getName(String type, String shape, int waterLevel) {
        return ShapeIds.CUBE.equals(shape) ? type : type + "-" + shape + (waterLevel >= 0 ? "-" + waterLevel : "");
    }

}

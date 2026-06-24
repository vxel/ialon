package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the four door (Plate) orientations register under the names the inventory and the
 * door-toggle action look them up by — the same resolution trap that produced an empty fire slot
 * (see the block-naming notes).
 */
class DoorBlockTest {

    @Test
    void doorOrientationsAreRegistered() {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();

        // The level-0 suffixed names are what both the inventory (local getName) and
        // WorldManager#toggleDoor / #orientateBlockDoor build via BlockIds.getName(type, shape, 0).
        // Both door types (DOOR and the mirror-hinge DOOR_RIGHT) must resolve in all 4 orientations.
        for (String type : new String[]{TypeIds.DOOR_LEFT, TypeIds.DOOR_RIGHT}) {
            for (String shape : new String[]{
                    ShapeIds.PLATE_NORTH, ShapeIds.PLATE_SOUTH, ShapeIds.PLATE_EAST, ShapeIds.PLATE_WEST}) {
                String name = type + "-" + shape + "-0";
                Block door = registry.get(name);
                assertNotNull(door, "Door block should be registered: " + name);
                assertEquals(type, door.getType(), "Wrong type for " + name);
                assertEquals(shape, door.getShape(), "Wrong shape for " + name);
            }
        }
    }

    /**
     * A vertical Plate must cover only the wall face it is flush against — never the floor (DOWN) — so
     * it does not hide the top face of the block beneath it (the "hole under the door" bug). The
     * dominant {@code plate_up} (and {@code slab}) must still cover DOWN as before.
     */
    @Test
    void verticalPlateDoesNotCoverFloor() {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        // The four side plates each cover their own wall face, and crucially NOT the floor.
        Shape plateNorth = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.PLATE_NORTH);
        assertFalse(plateNorth.fullyCoversFace(Direction.DOWN),
                "plate_north must not cover DOWN (would hole the block below)");
        assertFalse(plateNorth.fullyCoversFace(Direction.UP), "plate_north must not cover UP");

        for (String s : new String[]{
                ShapeIds.PLATE_NORTH, ShapeIds.PLATE_SOUTH, ShapeIds.PLATE_EAST, ShapeIds.PLATE_WEST}) {
            Shape plate = BlocksConfig.getInstance().getShapeRegistry().get(s);
            assertFalse(plate.fullyCoversFace(Direction.DOWN), s + " must not cover DOWN");
        }

        // Regression guard : a floor slab still covers DOWN exactly as before.
        Shape slabUp = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.SLAB);
        assertTrue(slabUp.fullyCoversFace(Direction.DOWN), "slab_up must still cover DOWN");
    }
}

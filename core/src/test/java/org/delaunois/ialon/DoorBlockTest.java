package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Direction;
import org.delaunois.ialon.blocks.Shape;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldManager;
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
        // Every door type (default look + the metal example, each with both hinges) must resolve in
        // all 4 orientations.
        for (String type : new String[]{
                TypeIds.DOOR_LEFT, TypeIds.DOOR_RIGHT, TypeIds.DOOR_LEFT_METAL, TypeIds.DOOR_RIGHT_METAL}) {
            for (String shape : new String[]{
                    ShapeIds.DOOR_NORTH, ShapeIds.DOOR_SOUTH, ShapeIds.DOOR_EAST, ShapeIds.DOOR_WEST}) {
                String name = type + "-" + shape + "-0";
                Block door = registry.get(name);
                assertNotNull(door, "Door block should be registered: " + name);
                assertEquals(type, door.getType(), "Wrong type for " + name);
                assertEquals(shape, door.getShape(), "Wrong shape for " + name);
            }
        }
    }

    /**
     * The door framework is generic over the look prefix : any {@code <look>_door_<hinge>} type is a
     * door, regardless of the look (default, metal, or any added later). Non-door types are rejected.
     */
    @Test
    void isDoorRecognisesEveryLook() {
        assertTrue(WorldManager.isDoor(TypeIds.DOOR_LEFT));
        assertTrue(WorldManager.isDoor(TypeIds.DOOR_RIGHT));
        assertTrue(WorldManager.isDoor(TypeIds.DOOR_LEFT_METAL));
        assertTrue(WorldManager.isDoor(TypeIds.DOOR_RIGHT_METAL));
        assertTrue(WorldManager.isDoor("door_left_oak"), "any look suffix must be accepted");

        assertFalse(WorldManager.isDoor(TypeIds.OAK_PLANKS));
        assertFalse(WorldManager.isDoor("metal1"));
        assertFalse(WorldManager.isDoor(null));
    }

    /**
     * A vertical door/plate panel must cover only the wall face it is flush against — never the floor
     * (DOWN) — so it does not hide the top face of the block beneath it (the "hole under the door"
     * bug). The dominant {@code plate_up} / {@code slab} must still cover DOWN as before.
     */
    @Test
    void verticalPanelDoesNotCoverFloor() {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        // Each side door/plate covers only its own wall face, and crucially NOT the floor or ceiling.
        for (String s : new String[]{
                ShapeIds.DOOR_NORTH, ShapeIds.DOOR_SOUTH, ShapeIds.DOOR_EAST, ShapeIds.DOOR_WEST,
                ShapeIds.PLATE_NORTH, ShapeIds.PLATE_SOUTH, ShapeIds.PLATE_EAST, ShapeIds.PLATE_WEST}) {
            Shape panel = BlocksConfig.getInstance().getShapeRegistry().get(s);
            assertFalse(panel.fullyCoversFace(Direction.DOWN), s + " must not cover DOWN");
            assertFalse(panel.fullyCoversFace(Direction.UP), s + " must not cover UP");
        }

        // Regression guard : a floor slab still covers DOWN exactly as before.
        Shape slabUp = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.SLAB);
        assertTrue(slabUp.fullyCoversFace(Direction.DOWN), "slab_up must still cover DOWN");
    }
}

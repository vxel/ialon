package org.delaunois.ialon.blocks;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Locks the door hinge geometry : a closed door must hinge against the wall it opens onto, so doors
 * placed against a jamb open flat against it. The expected neighbours were derived from the engine's
 * actual plate rotation (a {@code plate_north} panel is flush against the SOUTH cell face, etc.) — see
 * WorldManager#doorHingeNeighbour. DOOR_RIGHT is the mirror : for the same closed shape it hinges on
 * the opposite side, which is what lets two coplanar leaves form a double-leaf door.
 */
class DoorHingeTest {

    @Test
    void doorHingesOnTheSideItOpensOnto() {
        assertEquals(Direction.WEST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.DOOR_NORTH));
        assertEquals(Direction.EAST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.DOOR_SOUTH));
        assertEquals(Direction.SOUTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.DOOR_EAST));
        assertEquals(Direction.NORTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, ShapeIds.DOOR_WEST));
    }

    @Test
    void doorRightHingesOnTheOppositeSide() {
        // Same closed shape as DOOR but the mirror hinge — opposite jamb (enables double-leaf doors).
        assertEquals(Direction.EAST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.DOOR_NORTH));
        assertEquals(Direction.WEST, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.DOOR_SOUTH));
        assertEquals(Direction.NORTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.DOOR_EAST));
        assertEquals(Direction.SOUTH, WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, ShapeIds.DOOR_WEST));
    }

    @Test
    void hingeDependsOnlyOnHingeSuffixNotOnLook() {
        // The hinge is read from the _left/_right suffix, so any look behaves like the default one.
        for (String shape : new String[]{
                ShapeIds.DOOR_NORTH, ShapeIds.DOOR_SOUTH, ShapeIds.DOOR_EAST, ShapeIds.DOOR_WEST}) {
            assertEquals(WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT, shape),
                    WorldManager.doorHingeNeighbour(TypeIds.DOOR_LEFT_METAL, shape));
            assertEquals(WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT, shape),
                    WorldManager.doorHingeNeighbour(TypeIds.DOOR_RIGHT_METAL, shape));
        }
    }
}

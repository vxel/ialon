package com.rvandoosselaer.blocks;

import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * An enum holding direction information in a right-handed coordinate system, just as OpenGL.
 *
 * @author rvandoosselaer
 */
@Slf4j
@Getter
@ToString
public enum Direction {
    UP(0, 1, 0),
    DOWN(0, -1, 0),
    WEST(-1, 0, 0),
    EAST(1, 0, 0),
    SOUTH(0, 0, 1),
    NORTH(0, 0, -1);

    private final Vec3i vector;

    Direction(int x, int y, int z) {
        this.vector = new Vec3i(x, y, z);
    }

    public static Direction fromVector(@NonNull Vector3f vector3f) {
        int x = Math.round(vector3f.x);
        int y = Math.round(vector3f.y);
        int z = Math.round(vector3f.z);
        if (x != 0) {
            if (x == 1) {
                return EAST;
            } else if (x == -1) {
                return WEST;
            }
        } else if (y != 0) {
            if (y == 1) {
                return UP;
            } else if (y == -1) {
                return DOWN;
            }
        } else if (z != 0) {
            if (z == 1) {
                return SOUTH;
            } else if (z == -1) {
                return NORTH;
            }
        }
        log.error("Unable to find direction from vector {}. Returning UP.", vector3f);
        return UP;
    }

    public Direction opposite() {
        if (vector.x != 0) {
            return vector.x > 0 ? WEST : EAST;
        } else if (vector.y != 0) {
            return vector.y > 0 ? DOWN : UP;
        } else if (vector.z != 0) {
            return vector.z > 0 ? NORTH : SOUTH;
        }
        throw new IllegalStateException("Invalid direction vector: " + vector);
    }

}

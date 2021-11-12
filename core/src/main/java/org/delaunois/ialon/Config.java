package org.delaunois.ialon;

import com.jme3.math.ColorRGBA;
import com.simsilica.mathd.Vec3i;

public class Config {

    // Screen - Rendering
    public static final int SCREEN_WIDTH = 1520;
    public static final int SCREEN_HEIGHT = 720;
    public static final int FPS_LIMIT = 120;
    public static final int MAX_UPDATE_PER_FRAME = 3;

    // Grid
    public static final int GRID_DIAMETER = 5;
    public static final int PHYSICS_GRID_DIAMETER = 1;
    public static final int CHUNK_SIZE = 16;
    public static final int CHUNK_HEIGHT = 16;
    public static final int GRID_SIZE = GRID_DIAMETER * 2 + 1;
    public static final int GRID_HEIGHT = 7;
    public static final int PHYSICS_GRID_SIZE = PHYSICS_GRID_DIAMETER * 2 + 1;
    public static final Vec3i GRID_LOWER_BOUND = new Vec3i(Integer.MIN_VALUE, 0, Integer.MIN_VALUE);
    public static final Vec3i GRID_UPPER_BOUND = new Vec3i(Integer.MAX_VALUE, GRID_HEIGHT - 1, Integer.MAX_VALUE);
    public static final int MAXY = GRID_HEIGHT * CHUNK_HEIGHT;

    // World
    public static final float WATER_HEIGHT = 50f;
    public static final float AMBIANT_INTENSITY = 0.4f;
    public static final float SUN_INTENSITY = 1.3f;
    public static final float SUN_AMPLITUDE = 10f;

    public static final ColorRGBA SKY_COLOR = ColorRGBA.fromRGBA255(100, 172, 255, 255);
    public static final ColorRGBA SKY_ZENITH_COLOR = ColorRGBA.fromRGBA255(65, 142, 255, 255);
    public static final ColorRGBA SKY_HORIZON_COLOR = ColorRGBA.White;
    public static final ColorRGBA SKY_DAY_COLOR = ColorRGBA.White;
    public static final ColorRGBA SKY_EVENING_COLOR = new ColorRGBA(1f, 0.7f, 0.5f, 1);
    public static final ColorRGBA SKY_NIGHT_COLOR = ColorRGBA.fromRGBA255(9, 12, 19, 1);
    public static final ColorRGBA DAY_COLOR = ColorRGBA.White;
    public static final ColorRGBA EVENING_COLOR = ColorRGBA.fromRGBA255(255, 173, 66, 255);
    public static final ColorRGBA NIGHT_COLOR = new ColorRGBA(0.4f, 0.4f, 0.4f, 1);
    public static final float TIME_FACTOR = 0.3f; // Should be 0.01f
    public static final float GROUND_GRAVITY = 10;
    public static final float WATER_GRAVITY = 0.4f;
    public static final float JUMP_SPEED = 5;
    public static final float WATER_JUMP_SPEED = 2;

    // Player
    public static final float ROTATION_SPEED = 1f;
    public static final float PLAYER_START_HEIGHT = 10;
    public static final float PLAYER_MOVE_SPEED = 0.05f;
    public static final float PLAYER_FLY_SPEED = 0.1f;
    public static final float PLAYER_HEIGHT = 1.5f;
    public static final float PLAYER_RADIUS = 0.3f;
    public static final boolean PLAYER_START_FLY = false;

    // Debug
    public static final boolean DEBUG_COLLISIONS = false;

}

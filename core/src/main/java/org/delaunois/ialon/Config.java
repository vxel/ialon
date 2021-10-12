package org.delaunois.ialon;

import com.jme3.math.ColorRGBA;
import com.simsilica.mathd.Vec3i;

public class Config {

    // Screen - Rendering
    public static final int SCREEN_WIDTH = 1520;
    public static final int SCREEN_HEIGHT = 720;
    public static final int FPS_LIMIT = 120;
    public static final int MAX_UPDATE_PER_FRAME = 1;

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
    public static final float AMBIANT_INTENSITY = 0.75f;
    public static final float NIGHT_INTENSITY = 0.08f;
    public static final float SUN_INTENSITY = 0.75f;
    public static final float SUN_AMPLITUDE = 10f;
    public static final float SUN_HEIGHT = 5f;
    public static final ColorRGBA SUN_COLOR = new ColorRGBA(1f, 1f, 0.4f, 1f);
    public static final ColorRGBA SUN_DAY_COLOR = new ColorRGBA(1f, 1f, 1f, 1f);
    public static final ColorRGBA SUN_EVENING_COLOR = new ColorRGBA(1f, 1f, 0.4f, 1f);
    public static final float DAYBREAK_DURATION = 8f;
    public static final float TIME_FACTOR = 0.001f; // Should be 0.01f
    public static final float GROUND_GRAVITY = 10;
    public static final float WATER_GRAVITY = 0.2f;
    public static final float JUMP_SPEED = 5;

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

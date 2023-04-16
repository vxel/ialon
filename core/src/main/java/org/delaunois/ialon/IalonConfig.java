package org.delaunois.ialon;

import com.jme3.math.ColorRGBA;
import com.rvandoosselaer.blocks.ShapeIds;
import com.simsilica.mathd.Vec3i;

import lombok.Getter;
import lombok.Setter;

/**
 * Global configuration options for Ialon
 */
@Getter
@Setter
public class IalonConfig {

    private static IalonConfig instance = new IalonConfig();
    
    // Screen - Rendering
    private int screenWidth = 1520;
    private int screenHeight = 720;
    private int fpsLimit = 60;
    private int maxUpdatePerFrame = 8;
    private int chunkPoolsize = 4;

    // Grid
    private int gridRadius = 4;
    private int gridRadiusMin = 2;
    private int gridRadiusMax = 15;
    private int physicsGridRadius = 1;
    private int chunkSize = 16;
    private int chunkHeight = 16;
    private int gridSize = gridRadius * 2 + 1;
    private int gridHeight = 7;
    private int physicsGridSize = physicsGridRadius * 2 + 1;
    private Vec3i gridLowerBound = new Vec3i(Integer.MIN_VALUE, 0, Integer.MIN_VALUE);
    private Vec3i gridUpperBound = new Vec3i(Integer.MAX_VALUE, gridHeight - 1, Integer.MAX_VALUE);
    private int maxy = gridHeight * chunkHeight;

    // World
    private float waterHeight = 50f;
    private boolean simulateLiquidFlow = true;
    private int simulateLiquidFlowModel = 2;
    private float waterSimulationSpeed = 2f;
    private float ambiantIntensity = 0.5f;
    private float sunIntensity = 1.2f;
    private float sunAmplitude = 10f;

    private ColorRGBA skyColor = ColorRGBA.fromRGBA255(100, 172, 255, 255);
    private ColorRGBA skyZenithColor = ColorRGBA.fromRGBA255(65, 142, 255, 255);
    private ColorRGBA skyHorizonColor = ColorRGBA.White;

    private ColorRGBA skyDayColor = ColorRGBA.White;
    private ColorRGBA skyEveningColor = new ColorRGBA(1f, 0.7f, 0.5f, 1);
    private ColorRGBA skyNightColor = ColorRGBA.fromRGBA255(9, 12, 19, 255);
    private ColorRGBA groundDayColor = ColorRGBA.fromRGBA255(141, 199, 255, 255);
    private ColorRGBA groundEveningColor = ColorRGBA.fromRGBA255(150, 136, 126, 255);
    private ColorRGBA groundNightColor = ColorRGBA.fromRGBA255(6, 6, 12, 255);
    private ColorRGBA dayColor = ColorRGBA.White;
    private ColorRGBA eveningColor = ColorRGBA.fromRGBA255(255, 173, 66, 255);
    private ColorRGBA nightColor = new ColorRGBA(0.2f, 0.2f, 0.2f, 1);
    private float timeFactor = 0.01f; // Should be 0.01f
    private float groundGravity = 9;
    private float waterGravity = 0.4f;
    private float jumpSpeed = 5f;
    private float waterJumpSpeed = 5f;

    // Player
    private float rotationSpeed = 1.5f;
    private float playerStartHeight = 10;
    private float playerMoveSpeed = 0.05f;
    private float playerFlySpeed = 0.1f;
    private float playerHeight = 1.6f;
    private float playerRadius = 0.3f;
    private float playerStepHeight = 0.3f;
    private boolean playerStartFly = false;

    // Debug
    private boolean devMode = false;
    private boolean debugCollisions = false;
    private boolean debugGrid = false;
    private boolean debugChunks = false;

    // Shapes
    private String[] standardShapesNoStairs = {
            ShapeIds.CUBE,
            ShapeIds.PYRAMID,
            ShapeIds.POLE,
            ShapeIds.FENCE,
            ShapeIds.SLAB,
            ShapeIds.DOUBLE_SLAB,
            ShapeIds.PLATE,
            ShapeIds.WEDGE_NORTH,
            ShapeIds.WEDGE_EAST,
            ShapeIds.WEDGE_SOUTH,
            ShapeIds.WEDGE_WEST,
            ShapeIds.WEDGE_INVERTED_NORTH,
            ShapeIds.WEDGE_INVERTED_EAST,
            ShapeIds.WEDGE_INVERTED_SOUTH,
            ShapeIds.WEDGE_INVERTED_WEST,
    };
    private String[] standardShapes = {
            ShapeIds.CUBE,
            ShapeIds.PYRAMID,
            ShapeIds.POLE,
            ShapeIds.FENCE,
            ShapeIds.SLAB,
            ShapeIds.DOUBLE_SLAB,
            ShapeIds.PLATE,
            ShapeIds.WEDGE_NORTH,
            ShapeIds.WEDGE_EAST,
            ShapeIds.WEDGE_SOUTH,
            ShapeIds.WEDGE_WEST,
            ShapeIds.WEDGE_INVERTED_NORTH,
            ShapeIds.WEDGE_INVERTED_EAST,
            ShapeIds.WEDGE_INVERTED_SOUTH,
            ShapeIds.WEDGE_INVERTED_WEST,
            ShapeIds.STAIRS_NORTH,
            ShapeIds.STAIRS_EAST,
            ShapeIds.STAIRS_SOUTH,
            ShapeIds.STAIRS_WEST,
            ShapeIds.STAIRS_INVERTED_NORTH,
            ShapeIds.STAIRS_INVERTED_EAST,
            ShapeIds.STAIRS_INVERTED_SOUTH,
            ShapeIds.STAIRS_INVERTED_WEST,
            ShapeIds.STAIRS_INNER_CORNER_NORTH,
            ShapeIds.STAIRS_INNER_CORNER_EAST,
            ShapeIds.STAIRS_INNER_CORNER_SOUTH,
            ShapeIds.STAIRS_INNER_CORNER_WEST,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_NORTH,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_EAST,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_SOUTH,
            ShapeIds.STAIRS_INVERTED_INNER_CORNER_WEST,
            ShapeIds.STAIRS_OUTER_CORNER_NORTH,
            ShapeIds.STAIRS_OUTER_CORNER_EAST,
            ShapeIds.STAIRS_OUTER_CORNER_SOUTH,
            ShapeIds.STAIRS_OUTER_CORNER_WEST,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_NORTH,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_EAST,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_SOUTH,
            ShapeIds.STAIRS_INVERTED_OUTER_CORNER_WEST
    };
    private byte[] allLevels = { 0, 1, 2, 3, 4, 5, 6, 7 };

    private IalonConfig() {
        // Prevent instanciation
    }

    public static IalonConfig getInstance() {
        return instance;
    }

}

package com.rvandoosselaer.blocks;

import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;

import java.util.concurrent.TimeUnit;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A chunk holds an array of {@link Block} elements. Blocks can be retrieved, added or removed using the appropriate
 * methods.
 * Each time the data structure of the chunk changes (when blocks are added or removed), the {@link #update()} method
 * should be called to reevaluate the {@link #isFull()} and {@link #isEmpty()} flags.
 * Make sure to call the {@link #cleanup()} method to properly dispose of the chunk.
 *
 * @author rvandoosselaer
 */
@Slf4j
@Getter
@ToString(onlyExplicitlyIncluded = true)
public class Chunk {

    // For CPU optimization
    private static final Vec3i CHUNK_SIZE = BlocksConfig.getInstance().getChunkSize();
    private static final BlockRegistry REGISTRY = BlocksConfig.getInstance().getBlockRegistry();
    private static final ColorRGBA WATER_COLOR_FILTER = ColorRGBA.fromRGBA255(52, 160, 255, 1);
    private static final String WATER_BLOCK = "water-liquid";

    // a one dimensional array is quicker to lookup blocks then a 3n array
    private short[] blocks;

    @Setter(AccessLevel.PRIVATE)
    @ToString.Include
    private Vec3i location;

    private Vector3f worldLocation;

    @ToString.Include
    private boolean empty;

    @ToString.Include
    private boolean full;

    /**
     * Wheter this chunk was loaded (false) or generated (true)
     */
    @Setter
    private boolean generated = false;
    /**
     * Whether a block has been added or deleted to this chunk since it was loaded/generated
     */
    @Setter
    private boolean dirty = false;
    @Setter
    private Node node;
    @Setter
    private Mesh collisionMesh;
    @Setter
    private ChunkResolver chunkResolver;

    /**
     * The lightmap for this chunk. Each byte defines the levels of sun light and torch light
     * of a block :
     *
     * Sunlight|TorchLight
     * 8               0
     * +-+-+-+-+-+-+-+-+
     * |s|s|s|s|t|t|t|t|
     * +-+-+-+-+-+-+-+-+
     */
    private byte[] lightMap;

    // To avoid many instanciation of Vec3i (costly)
    private final Vec3i v = new Vec3i();

    public Chunk(@NonNull Vec3i location) {
        setLocation(location);
        full = false;
        empty = true;
    }

    public static Chunk createAt(@NonNull Vec3i location) {
        return new Chunk(location);
    }

    private void allocate() {
        if (blocks == null) {
            setBlocks(new short[CHUNK_SIZE.x * CHUNK_SIZE.y * CHUNK_SIZE.z]);
            setLightMap(new byte[CHUNK_SIZE.x * CHUNK_SIZE.y * CHUNK_SIZE.z]);
        }
    }

    public void setBlocks(short[] blocks) {
        this.blocks = blocks;
        update();
    }

    public void setLightMap(byte[] lightMap) {
        this.lightMap = lightMap;
    }

    /**
     * Add a block to this chunk. If there was already a block at this location, it will be overwritten.
     *
     * @param location local coordinate in the chunk
     * @param block    the block to add
     * @return the previous block at the location or null
     */
    public Block addBlock(@NonNull Vec3i location, Block block) {
        return addBlock(location.x, location.y, location.z, block);
    }

    /**
     * Add a block to this chunk. If there was already a block at this location, it will be replaced.
     *
     * @param x     local x coordinate in the chunk
     * @param y     local y coordinate in the chunk
     * @param z     local z coordinate in the chunk
     * @param block the block to add
     * @return the previous block at the location or null
     */
    public Block addBlock(int x, int y, int z, Block block) {
        if (isInsideChunk(x, y, z)) {
            allocate();
            int index = calculateIndex(x, y, z);
            Block previous = REGISTRY.get(blocks[index]);
            blocks[index] = block.getId();
            if (log.isTraceEnabled()) {
                log.trace("Added {} at ({}, {}, {}) to {}", block, x, y, z, this);
            }
            dirty = true;
            empty = false;
            return previous;
        }
        log.warn("Block location ({}, {}, {}) is outside of the chunk boundaries!", x, y, z);
        return null;
    }

    /**
     * Retrieve the block at the given block coordinate in this chunk.
     *
     * @param location local coordinate in the chunk
     * @return block or null
     */
    public Block getBlock(@NonNull Vec3i location) {
        return getBlock(location.x, location.y, location.z);
    }

    /**
     * Retrieve the block at the given block coordinate in this chunk.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return block or null
     */
    public Block getBlock(int x, int y, int z) {
        if (isInsideChunk(x, y, z)) {
            return this.blocks == null ? null : REGISTRY.get(this.blocks[calculateIndex(x, y, z)]);
        }

        log.warn("Block location ({}, {}, {}) is outside of the chunk boundaries!", x, y, z);
        return null;
    }

    /**
     * Removes and returns the block at the given coordinate in this chunk.
     *
     * @param location local coordinate in the chunk
     * @return the removed block or null
     */
    public Block removeBlock(@NonNull Vec3i location) {
        return removeBlock(location.x, location.y, location.z);
    }

    /**
     * Removes and returns the block at the given coordinate in this chunk.
     *
     * @param x local x coordinate
     * @param y local y coordinate
     * @param z local z coordinate
     * @return the removed block or null
     */
    public Block removeBlock(int x, int y, int z) {
        if (blocks != null && isInsideChunk(x, y, z)) {
            int index = calculateIndex(x, y, z);
            Block block = REGISTRY.get(blocks[index]);
            blocks[index] = 0;
            if (log.isTraceEnabled()) {
                log.trace("Removed {} at ({}, {}, {}) from {}", block, x, y, z, this);
            }
            dirty = true;
            return block;
        }

        log.warn("block location ({}, {}, {}) is outside the chunk boundaries!", x, y, z);
        return null;
    }

    /**
     * Creates and returns the node of the chunk with the given {@link ChunkMeshGenerator}.
     *
     * @param strategy mesh generation strategy to use for constructing the node
     * @return the generated chunk node
     */
    public Node createNode(ChunkMeshGenerator strategy) {
        setNode(strategy.createNode(this));
        return getNode();
    }

    /**
     * Creates and returns the collision mesh of the chunk with the given {@link ChunkMeshGenerator}.
     *
     * @param strategy mesh generation strategy to use for creating the collision mesh
     * @return the generated collision mesh
     */
    public Mesh createCollisionMesh(ChunkMeshGenerator strategy) {
        setCollisionMesh(strategy.createCollisionMesh(this));
        return getCollisionMesh();
    }

    /**
     * Updates the {@link #isEmpty()} and {@link #isFull()} values. This should be called whenever the block data has
     * changed.
     */
    public void update() {
        if (blocks == null) {
            empty = true;
            full = false;
            return;
        }

        long start = System.nanoTime();
        boolean empty = true;
        boolean full = true;

        for (short block : blocks) {
            if (block == 0 && full) {
                full = false;
            }
            if (block != 0 && empty) {
                empty = false;
            }

            if (!empty && !full) {
                // break out of the loop
                break;
            }
        }

        this.empty = empty;
        this.full = full;

        if (log.isTraceEnabled()) {
            log.trace("Updating {}} values took {}ms", this, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    public void cleanup() {
        if (log.isDebugEnabled()) {
            log.debug("cleanup " + this.location);
        }
        this.blocks = null;
        this.node = null;
        this.collisionMesh = null;
        this.chunkResolver = null;

        // location and worldLocation should not be set to null because other
        // threads need non-null values in order to finish gracefully (e.g. FacesMeshGenerator)
    }

    /**
     * Calculates the local coordinate of the block inside this chunk, based on the world location of the block.
     *
     * @param blockWorldLocation block location in the world
     * @return the local block coordinate
     */
    public Vec3i toLocalLocation(@NonNull Vec3i blockWorldLocation) {
        Vec3i localCoord = new Vec3i(blockWorldLocation.x - (location.x * CHUNK_SIZE.x), blockWorldLocation.y - (location.y * CHUNK_SIZE.y), blockWorldLocation.z - (location.z * CHUNK_SIZE.z));
        if (!isInsideChunk(localCoord.x, localCoord.y, localCoord.z)) {
            log.warn("Block world location {} is not part of this chunk {}!", blockWorldLocation, this);
            return null;
        }

        return localCoord;
    }

    /**
     * checks if the block location in the world is part of this chunk.
     *
     * @param blockWorldLocation block location in the world
     * @return true if this chunk contains the location, false otherwise
     */
    public boolean containsLocation(Vec3i blockWorldLocation) {
        Vec3i localCoord = new Vec3i(blockWorldLocation.x - (location.x * CHUNK_SIZE.x), blockWorldLocation.y - (location.y * CHUNK_SIZE.y), blockWorldLocation.z - (location.z * CHUNK_SIZE.z));

        return isInsideChunk(localCoord.x, localCoord.y, localCoord.z);
    }

    /**
     * Calculates the world location of the chunk.
     *
     * @return the world location of the chunk
     */
    public Vector3f getWorldLocation() {
        if (worldLocation == null) {
            float blockScale = BlocksConfig.getInstance().getBlockScale();

            worldLocation = location.toVector3f().multLocal(CHUNK_SIZE.toVector3f()).multLocal(blockScale);
            // the chunk at (1, 0, 1) should be positioned at (1 * chunkSize.x, 0 * chunkSize.y, 1 * chunkSize.z)
            // we also add an offset to the chunk location to compensate for the block extends. A block positioned at
            // (0, 0, 0) will have it's bounding box center at (0, 0, 0) so the block on the x-axis and z-axis
            // will go from -blockScale / 2 to blockScale / 2.
            worldLocation.addLocal(blockScale * 0.5f, blockScale * 0.5f, blockScale * 0.5f);
        }
        return worldLocation;
    }

    /**
     * Returns the neighbouring block at the given direction. When a {@link ChunkResolver} is set, the block from a
     * neighbouring chunk is retrieved.
     *
     * @param blockLocation  block coordinate
     * @param direction neighbour direction
     * @return the neighbouring block or null
     */
    public Block getNeighbour(@NonNull Vec3i blockLocation, @NonNull Direction direction) {
        return getNeighbour(blockLocation.x, blockLocation.y, blockLocation.z,
                direction.getVector().x, direction.getVector().y, direction.getVector().z);
    }

    public Block getNeighbour(int x, int y, int z, int dx, int dy, int dz) {
        // Beware of CPU optimization : method heavily used
        int blx = x + dx;
        int bly = y + dy;
        int blz = z + dz;

        if (isInsideChunk(blx, bly, blz)) {
            return getBlock(blx, bly, blz);
        }

        if (hasChunkResolver()) {
            int[] loc = computeNeighbourCoordinates(location.x, location.y, location.z, blx, bly, blz);
            Chunk chunk = chunkResolver.unsafeFastGet(v.set(loc[0], loc[1], loc[2]));
            if (chunk != null) {
                return chunk.getBlock(loc[3], loc[4], loc[5]);
            }
        }

        return null;
    }

    public Vector4f applyAO(Vector4f color, int ao) {
        int shift = Math.max(1, 3 - ao);
        int torchlight = ((int)color.w) & 0xF;
        int sunlight = (((int)color.w) >> 4) & 0xF;
        torchlight = torchlight / shift;
        sunlight = sunlight / shift;
        int lightLevel = (sunlight << 4 | torchlight);
        return new Vector4f(color.x, color.y, color.z, lightLevel);
    }

    public int vertexAO(Block sideBlock1, Block sideBlock2, Block cornerBlock) {
        int side1 = getAOIndex(sideBlock1);
        int side2 = getAOIndex(sideBlock2);
        int corner = getAOIndex(cornerBlock);

        if (side1 == 1 && side2 == 1) {
            return 0;
        }
        return 3 - (side1 + side2 + corner);
    }

    public int getAOIndex(Block block) {
        if (block != null && block.getShape().equals(ShapeIds.CUBE)) {
            return 1;
        }
        return 0;
    }

    /**
     * Checks if the face of the block is visible using the faceVisibleFunction and thus should be rendered.
     *
     * @param location  block coordinate
     * @param direction of the face
     * @return true if the face is visible
     */
    public boolean isFaceVisible(@NonNull Vec3i location, @NonNull Direction direction) {
        return isFaceVisible(location, direction, getBlock(location), getNeighbour(location, direction));
    }

    public boolean isFaceVisible(@NonNull BlockNeighborhood neighborhood, @NonNull Direction direction) {
        return isFaceVisible(neighborhood.getLocation(), direction, neighborhood.getCenterBlock(), neighborhood.getNeighbour(direction));
    }

    public boolean isFaceVisible(@NonNull Vec3i location, @NonNull Direction direction, Block block, Block neighbour) {
        // Beware : method heavily used
        if (this.location.y == 0 && location.y == 0 && direction == Direction.DOWN) {
            // Optimisation : Do not render faces below the world
            return false;
        }
        return isFaceVisible(block, direction, neighbour);
    }

    public boolean isFaceVisible(Block block, @NonNull Direction direction, Block neighbour) {
        if (neighbour == null) {
            return true;
        }

        boolean fullyCovers = BlocksConfig
                .getInstance()
                .getShapeRegistry()
                .get(neighbour.getShape())
                .fullyCoversFace(direction.opposite());

        if (!fullyCovers) {
            // Draw the face if the neighbour face does not fully cover the square
            return true;
        }

        if (neighbour.isTransparent() || block.isTransparent()) {
            return !(block.getType().equals(neighbour.getType()));
        }

        return ShapeIds.LIQUID.equals(neighbour.getShape());
    }

    public boolean isNeighbourFaceVisible(@NonNull Vec3i location, @NonNull Direction neighbourBlockDirection, @NonNull Direction neighbourFaceDirection) {
        Block neighbour = getNeighbour(location, neighbourBlockDirection);
        Block neighbourNeighbour = getNeighbour(location.add(neighbourBlockDirection.getVector()), neighbourFaceDirection);

        return isFaceVisible(neighbour, neighbourBlockDirection, neighbourNeighbour);
    }

    private boolean hasChunkResolver() {
        return chunkResolver != null;
    }

    /**
     * Gets the light at the given block location
     * @param blockLocation the block location within the chunk
     * @return a Vector with (x, y, z) = color of the light and w = sun and torch light level,
     */
    public Vector4f getLightLevel(Vec3i blockLocation) {
        int x = blockLocation.x;
        int y = blockLocation.y;
        int z = blockLocation.z;
        return getLightLevel(x, y, z, null, WATER_COLOR_FILTER);
    }

    /**
     * Gets the light for the face towards the given direction, at the given block location
     * @param blockLocation the block location within the chunk
     * @return a Vector with (x, y, z) = color of the light and w = sun and torch light level,
     */
    public Vector4f getLightLevel(Vec3i blockLocation, Direction direction) {
        // Beware of CPU optimization : method heavily used
        int x = blockLocation.x;
        int y = blockLocation.y;
        int z = blockLocation.z;

        if (direction != null) {
            x += direction.getVector().x;
            y += direction.getVector().y;
            z += direction.getVector().z;
        }

        if (isInsideChunk(x, y, z)) {
            return getLightLevel(x, y, z, direction, WATER_COLOR_FILTER);
        }

        if (hasChunkResolver()) {
            int[] loc = computeNeighbourCoordinates(location.x, location.y, location.z, x, y, z);
            Chunk chunk = chunkResolver.unsafeFastGet(new Vec3i(loc[0], loc[1], loc[2]));
            if (chunk != null) {
                return chunk.getLightLevel(loc[3], loc[4], loc[5], direction, WATER_COLOR_FILTER);
            } else {
                return Vector4f.ZERO;
            }
        }

        return Vector4f.ZERO;
    }

    public Vector4f applyColorFilter(Vector4f color) {
        return color;
    }

    int[] computeNeighbourCoordinates(int clx, int cly, int clz, int blx, int bly, int blz) {
        int cx = clx, cy = cly, cz = clz, x = blx, y = bly, z = blz;

        if (x < 0) {
            cx = cx - 1;
            x = CHUNK_SIZE.x - 1;
        } else if (x >= CHUNK_SIZE.x) {
            cx = cx + 1;
            x = 0;
        }

        if (y < 0) {
            cy = cy - 1;
            y = CHUNK_SIZE.y - 1;
        } else if (y >= CHUNK_SIZE.y) {
            cy = cy + 1;
            y = 0;
        }

        if (z < 0) {
            cz = cz - 1;
            z = CHUNK_SIZE.z - 1;
        } else if (z >= CHUNK_SIZE.z) {
            cz = cz + 1;
            z = 0;
        }

        return new int[]{ cx, cy, cz, x, y, z };
    }

    /**
     * Gets the light at the given block location and optionally apply the color
     * filter if the block has the given name.
     * @param x the x coordinate of the block location within the chunk
     * @param y the y coordinate of the block location within the chunk
     * @param z the z coordinate of the block location within the chunk
     * @return a Vector with (x, y, z) = color of the light and w = sun and torch light level,
     */
    private Vector4f getLightLevel(int x, int y, int z, Direction face, ColorRGBA color) {
        if (blocks == null) {
            return new Vector4f(1f, 1f, 1f, 0xF << 4);
        }
        int index = calculateIndex(x, y, z);
        int level = this.lightMap[index];
        ColorRGBA lightColor = ColorRGBA.White;
        if (color != null) {
            short bId = this.blocks[index];
            if (bId > 0) {
                Block block = REGISTRY.get(bId);
                if (block.getLiquidLevel() == Block.LIQUID_FULL || (block.getLiquidLevel() > 0 && face == Direction.UP)) {
                    lightColor = color;
                }
            }
        }
        return new Vector4f(lightColor.r, lightColor.g, lightColor.b, level);
    }

    public int getSunlight(int x, int y, int z) {
        return getSunlight(calculateIndex(x, y, z));
    }

    private int getSunlight(int index) {
        if (lightMap == null) {
            return 0xF;
        }
        return (this.lightMap[index] >> 4) & 0xF;
    }

    public void setSunlight(int x, int y, int z, int intensity) {
        allocate();
        int i = calculateIndex(x, y, z);
        lightMap[i] = (byte) ((lightMap[i] & 0xF) | (intensity << 4));
        dirty = true;
    }

    public int getTorchlight(int x, int y, int z) {
        return getTorchlight(calculateIndex(x, y, z));
    }

    private int getTorchlight(int index) {
        if (lightMap == null) {
            return 0;
        }
        return this.lightMap[index] & 0xF;
    }

    public void setTorchlight(int x, int y, int z, int intensity) {
        allocate();
        int i = calculateIndex(x, y, z);
        this.lightMap[i] = (byte) ((lightMap[i] & 0xF0) | intensity);
        dirty = true;
    }


    /**
     * Checks if the given block coordinate is inside the chunk.
     *
     * @param x coordinate of the block in this chunk
     * @param y coordinate of the block in this chunk
     * @param z coordinate of the block in this chunk
     * @return true if the coordinate of the block is inside the chunk, false otherwise.
     */
    public static boolean isInsideChunk(int x, int y, int z) {
        return x >= 0 && x < CHUNK_SIZE.x && y >= 0 && y < CHUNK_SIZE.y && z >= 0 && z < CHUNK_SIZE.z;
    }

    /**
     * Calculate the index in the block array for the given block coordinate.
     *
     * @param x block coordinate
     * @param y block coordinate
     * @param z block coordinate
     * @return the block array index for the block coordinate.
     */
    private static int calculateIndex(int x, int y, int z) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        return z + (y + x * chunkSize.y) * chunkSize.z;
    }

}

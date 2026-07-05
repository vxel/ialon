/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon.blocks.generator;

import static org.delaunois.ialon.blocks.BlockIds.WATER;
import static org.delaunois.ialon.blocks.BlockIds.WATER_SOURCE;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.ChunkLightManager;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.delaunois.ialon.blocks.WorldEditOverlay;
import org.delaunois.ialon.blocks.fastnoise.FastNoise;
import org.delaunois.ialon.blocks.fastnoise.LayeredNoise;
import org.delaunois.ialon.blocks.fastnoise.NoiseLayer;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

public class NoiseTerrainGenerator implements TerrainGenerator {

    private static final float GROUND_MIN = 12f;
    private static final int CANOPY_RADIUS = 3; // largest canopy radius : sizes the heightmap margin
    private static final int TREE_TRUNK_MIN = 4; // base trunk height drawn in [MIN, MAX]
    private static final int TREE_TRUNK_MAX = 6;
    private static final int TREE_TRUNK_BONUS_MAX = 3; // tallest species bonus (spruce/palm) on top of MAX
    private static final int TREE_CANOPY_MIN = 2; // canopy radius drawn in [MIN, CANOPY_RADIUS]
    // Tallest possible tree, used to size the vertical margins (chunk emptiness test + tree base bounds)
    // so a tall tree straddling a chunk border is never silently dropped.
    private static final int TREE_HEIGHT = TREE_TRUNK_MAX + TREE_TRUNK_BONUS_MAX + 2 * CANOPY_RADIUS + 1;

    // --- Vegetation scatter ---
    // Trees are scattered one-candidate-per-cell (TREE_CELL_SIZE blocks), jittered inside the cell and
    // kept with a probability driven by a low-frequency "forest density" map : this yields natural
    // spacing plus groves and clearings, instead of the former uniform float-modulo placement.
    // A power of two that divides the world period (worldSize = worldSizeChunks * 16) so the per-cell
    // scatter wraps EXACTLY at the seam, with no discontinuity, for any finite world size.
    private static final int TREE_CELL_SIZE = 4;
    private static final float TREE_MAX_PROB = 0.70f; // tree chance per cell in the heart of a wood
    private static final float SLOPE_MAX = 2.5f; // max ground height delta to a neighbour to allow a tree

    // --- Species selection (no biomes : altitude tiers + a per-wood broadleaf preference) ---
    // Relative altitude across the grass band [0 = shore, 1 = rock line] : spruce caps the cool
    // highlands, palms fringe the warm shore, oak/birch fill the middle (one species per wood).
    private static final float SPRUCE_ALT = 0.50f;
    private static final float PALM_ALT = 0.10f;
    private static final float PALM_FRACTION = 0.22f; // share of the low coastal fringe that is palm groves
    private static final float SPECIES_FREQUENCY = 0.01f; // oak-vs-birch wood patches (~100 blocks)

    // --- Ground plants (cross-plane items on the grass surface) ---
    private static final float PLANT_COVERAGE = 0.02f; // chance a grass-band column carries a plant
    private static final float MUSHROOM_FRACTION = 0.18f; // of forest-floor plants that are mushrooms
    private static final float SUNFLOWER_FRACTION = 0.02f; // of meadow plants that are sunflowers
    // Forest-density map : low frequency = large patches (big woods AND big clearings). The raw noise is
    // concentrated near its median, so a smoothstep around FOREST_THRESHOLD carves crisp dense woods
    // (density -> 1) and bare clearings (density -> 0). Raise the threshold to enlarge the clearings.
    private static final float FOREST_FREQUENCY = 0.004f; // ~125-block patches
    private static final int FOREST_OCTAVES = 2; // fewer octaves -> more contrast (higher variance)
    private static final float FOREST_THRESHOLD = 0.54f; // noise level splitting clearing (below) / wood (above)
    private static final float FOREST_EDGE = 0.06f; // half-width of the wood/clearing transition band
    // Independent per-cell/per-column draws are taken from distinct z-channels of the white-noise hash.
    private static final int CH_JITTER_X = 0;
    private static final int CH_JITTER_Z = 1;
    private static final int CH_PLANT = 2;
    private static final int CH_TRUNK = 3;
    private static final int CH_CANOPY = 4;
    private static final int CH_GRASS = 5;
    private static final int CH_PLANT_KIND = 6;
    private static final int CH_SPECIES = 7;

    /** Tree shapes : broadleaf sphere (oak/birch), conical spruce, top-tuft palm. */
    private enum CanopyStyle { SPHERE, CONE, PALM }

    private enum TreeSpecies {
        OAK(CanopyStyle.SPHERE, 0, 1),
        BIRCH(CanopyStyle.SPHERE, 1, 0),  // taller, slimmer
        SPRUCE(CanopyStyle.CONE, 2, 0),    // tall conifer
        PALM(CanopyStyle.PALM, 3, -1);     // tall bare trunk + small crown
        final CanopyStyle style;
        final int trunkBonus;
        final int radiusDelta;
        TreeSpecies(CanopyStyle style, int trunkBonus, int radiusDelta) {
            this.style = style;
            this.trunkBonus = trunkBonus;
            this.radiusDelta = radiusDelta;
        }
    }

    // Altitude tiers for the land surface, expressed as a fraction of the world ceiling
    // (gridHeight * chunkHeight) : the highest mountains are capped with snow, then bare rock.
    // Public so the far-terrain horizon (FarTerrainState) can colour the same tiers at the same
    // altitudes -- single source of truth, keeping the distant relief consistent with the voxels.
    public static final float SNOW_LINE_RATIO = 0.65f; // surface above 80% of the ceiling -> snow
    public static final float ROCK_LINE_RATIO = 0.60f; // surface above 70% (below the snow line) -> rock

    // "Relief" (reliefAmplitude) scales the MOUNTAINS layer fully (it sets the broad altitude, hence how
    // high the land climbs toward the fixed rock/snow tiers), but only PARTLY scales the hills/details
    // layers : those add zero-mean LOCAL roughness, so they keep this floor of their strength even at the
    // lowest relief. Otherwise a low relief flattens the whole landscape into a pancake instead of merely
    // removing the high snowy peaks. The blend (floor + (1-floor)*amplitude) passes through 1.0 at
    // amplitude 1.0, so the default world is generated exactly as before.
    private static final float RELIEF_LOCAL_FLOOR = 0.55f;

    // Fixed terrain base level (the lowland/sea-bed shelf the hard floor settles the low terrain around),
    // decoupled from waterHeight so the water level only fills the basins -- it never drags the whole
    // terrain down. 30 = the default world's water height, keeping that world byte-identical.
    private static final float TERRAIN_BASE_LEVEL = 30f;

    // Soft ceiling : peaks taller than this fraction of the world ceiling are compressed (see getHeight).
    // Kept above the default-relief max (~0.86 of the ceiling) so the default world is left untouched.
    private static final float PEAK_SOFT_START_RATIO = 0.88f;

    // Fallback world ceiling when none is supplied (mirrors the default gridHeight * chunkHeight).
    private static final int DEFAULT_WORLD_HEIGHT = 7 * 16;

    // The heightmap depends only on the chunk's (x, z) column, yet every chunk of a vertical column
    // (gridHeight of them) would recompute the same hundreds of noise samples. This bounded,
    // thread-safe LRU memorizes the heightmap per column so it is computed once and shared by
    // the whole column.
    private static final int HEIGHTS_CACHE_CAPACITY = 256;

    private long seed;
    private float waterHeight;
    // Tunable generation knobs, defaulting to the constants above so an unconfigured (default) world is
    // generated exactly as before. Per-world values are injected by IalonConfig#getDefaultChunkGenerator.
    private float reliefAmplitude = 1f; // multiplies the mountain/hill/detail strengths (terrain height)
    private float reliefFrequency = 1f; // multiplies the mountain/hill/detail frequencies (feature density)
    private float treeMaxProb = TREE_MAX_PROB; // tree chance per cell in the heart of a wood
    private float forestFrequency = FOREST_FREQUENCY; // forest-density field frequency (lower = bigger woods/clearings)
    // Horizontal tiling period in world units : when > 0 the heightmap is seamless and periodic in X
    // and Z with this period, producing a FINITE world whose -x/+x and -z/+z edges join perfectly
    // (a torus). 0 disables tiling (legacy infinite world). See LayeredNoise#evaluate(Vector2f,float).
    private float worldSize;
    // World ceiling (gridHeight * chunkHeight) and the derived surface altitude tier thresholds.
    private final float snowLine;
    private final float rockLine;
    // Soft ceiling : heights above peakSoftStart are smoothly compressed so they asymptote to peakCeiling
    // (just under the world ceiling) instead of being flat-cut at the top of the chunk grid. See getHeight.
    private final float peakSoftStart;
    private final float peakCeiling;
    private LayeredNoise layeredNoise;
    // Low-frequency forest-density field (tiled when worldSize > 0) and the white-noise hash used to
    // scatter trees/grass. Both are pure functions of their inputs + seed, hence thread-safe to share
    // across the chunk-generation thread pool, like layeredNoise.
    private NoiseLayer forestNoise;
    // Low-frequency field that lets each wood lean toward one broadleaf species (oak vs birch) instead
    // of a random species per tree. Tiled like the others.
    private NoiseLayer speciesNoise;
    private FastNoise scatterNoise;
    // Player edits the far-horizon renderers must honour (felled trees / reshaped relief). Null until a
    // world is opened ; consulted only by the chunk-free far paths (forEachTreeAnchor), never by the
    // voxel chunk generation (edited chunks are loaded from the save, not regenerated).
    private WorldEditOverlay worldEditOverlay;
    private final Map<Long, float[]> heightsCache =
            Collections.synchronizedMap(new HeightsCache(HEIGHTS_CACHE_CAPACITY));

    // Cached block references, resolved once on first generation to avoid a String-keyed
    // registry lookup (and, for itemGrass/waterLiquid, a String concatenation) per generated block.
    private volatile boolean blocksResolved = false;
    private Block blockRock;
    private Block blockSnow;
    private Block blockGrass;
    private Block blockSand;
    private Block blockDirt;
    private Block blockWaterSource;
    private Block blockWaterLiquid;
    private Block blockOakLog;
    private Block blockOakLeaves;
    private Block blockBirchLog;
    private Block blockBirchLeaves;
    private Block blockSpruceLog;
    private Block blockSpruceLeaves;
    private Block blockPalmLog;
    private Block blockPalmLeaves;
    private Block blockItemGrass;
    private Block blockItemMushroom;
    private Block blockItemSunflower;

    public NoiseTerrainGenerator(long seed, float waterHeight) {
        this(seed, waterHeight, DEFAULT_WORLD_HEIGHT);
    }

    public NoiseTerrainGenerator(long seed, float waterHeight, int worldHeight) {
        this(seed, waterHeight, worldHeight, 0f);
    }

    /**
     * @param worldHeight the world ceiling in blocks (gridHeight * chunkHeight), used to place the
     *                    snow and rock altitude tiers on the land surface.
     * @param worldSize   horizontal tiling period in world units (0 = infinite/non-tiling). When
     *                    &gt; 0 the terrain is seamless and periodic in X/Z with this period, i.e. a
     *                    finite world whose opposite edges join perfectly.
     */
    public NoiseTerrainGenerator(long seed, float waterHeight, int worldHeight, float worldSize) {
        this.seed = seed;
        this.waterHeight = waterHeight;
        this.worldSize = worldSize;
        this.snowLine = SNOW_LINE_RATIO * worldHeight;
        this.rockLine = ROCK_LINE_RATIO * worldHeight;
        this.peakSoftStart = PEAK_SOFT_START_RATIO * worldHeight;
        // Leave 2 blocks of headroom under the ceiling so the highest surface always has air above it
        // (it stays a normal surface column, never a flat-cut "full" chunk at the very top of the grid).
        this.peakCeiling = worldHeight - 2f;
        createWorldNoise();
    }

    public float getWorldSize() {
        return worldSize;
    }

    public void setWorldSize(float worldSize) {
        this.worldSize = worldSize;
        heightsCache.clear();
    }

    public WorldEditOverlay getWorldEditOverlay() {
        return worldEditOverlay;
    }

    /** Binds the per-world edit overlay so the far horizon reflects felled trees / reshaped relief. */
    public void setWorldEditOverlay(WorldEditOverlay worldEditOverlay) {
        this.worldEditOverlay = worldEditOverlay;
    }

    private void resolveBlocks() {
        BlockRegistry registry = BlocksConfig.getInstance().getBlockRegistry();
        blockRock = registry.get(BlockIds.ROCK);
        blockSnow = registry.get(BlockIds.SNOW);
        blockGrass = registry.get(BlockIds.GRASS);
        blockSand = registry.get(BlockIds.SAND);
        blockDirt = registry.get(BlockIds.DIRT);
        blockWaterSource = registry.get(WATER_SOURCE);
        blockWaterLiquid = registry.get(BlockIds.getName(WATER, ShapeIds.LIQUID));
        blockOakLog = registry.get(BlockIds.OAK_LOG);
        blockOakLeaves = registry.get(BlockIds.OAK_LEAVES);
        blockBirchLog = registry.get(BlockIds.BIRCH_LOG);
        blockBirchLeaves = registry.get(BlockIds.BIRCH_LEAVES);
        blockSpruceLog = registry.get(BlockIds.SPRUCE_LOG);
        blockSpruceLeaves = registry.get(BlockIds.SPRUCE_LEAVES);
        blockPalmLog = registry.get(BlockIds.PALM_TREE_LOG);
        blockPalmLeaves = registry.get(BlockIds.PALM_TREE_LEAVES);
        blockItemGrass = registry.get(BlockIds.getName(TypeIds.ITEM_GRASS, ShapeIds.CROSS_PLANE, 0));
        blockItemMushroom = registry.get(BlockIds.getName("item_mushroom", ShapeIds.CROSS_PLANE, 0));
        blockItemSunflower = registry.get(BlockIds.getName("item_sunflower", ShapeIds.CROSS_PLANE, 0));
        // Written last: a thread observing blocksResolved == true is guaranteed to see all block fields.
        blocksResolved = true;
    }

    private void ensureBlocksResolved() {
        if (!blocksResolved) {
            resolveBlocks();
        }
    }

    public long getSeed() {
        return seed;
    }

    public void setSeed(long seed) {
        this.seed = seed;
        createWorldNoise();
    }

    public float getReliefAmplitude() {
        return reliefAmplitude;
    }

    /** Multiplies the mountain/hill/detail strengths (overall terrain height). 1 = default relief. */
    public void setReliefAmplitude(float reliefAmplitude) {
        this.reliefAmplitude = reliefAmplitude;
        createWorldNoise();
    }

    public float getReliefFrequency() {
        return reliefFrequency;
    }

    /** Multiplies the mountain/hill/detail frequencies (how dense/tight the relief features are). */
    public void setReliefFrequency(float reliefFrequency) {
        this.reliefFrequency = reliefFrequency;
        createWorldNoise();
    }

    public float getTreeMaxProb() {
        return treeMaxProb;
    }

    /** Tree chance per cell in the heart of a wood (0 = no trees, 1 = maximal density). */
    public void setTreeMaxProb(float treeMaxProb) {
        this.treeMaxProb = treeMaxProb;
    }

    public float getForestFrequency() {
        return forestFrequency;
    }

    /** Forest-density field frequency : lower values yield larger woods and larger clearings. */
    public void setForestFrequency(float forestFrequency) {
        this.forestFrequency = forestFrequency;
        createWorldNoise();
    }

    @Override
    public float getWaterHeight() {
        return waterHeight;
    }

    @Override
    public void setWaterHeight(float waterHeight) {
        this.waterHeight = waterHeight;
    }

    @Override
    public Chunk generate(Vec3i location) {
        ensureBlocksResolved();
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();

        Chunk chunk = Chunk.createAt(location);
        int maxX = chunkSize.x;
        int maxY = chunkSize.y;
        int maxZ = chunkSize.z;
        int minWorldY = chunk.getLocation().y * chunkSize.y;
        int maxWorldY = chunk.getLocation().y * chunkSize.y + chunkSize.y - 1;
        int sizez = maxZ + 2 * CANOPY_RADIUS + 1;
        int sizex = maxX + 2 * CANOPY_RADIUS + 1;
        int densityBase = 2 + sizex * sizez;
        int worldOffsetX = chunk.getLocation().x * chunkSize.x;
        int worldOffsetZ = chunk.getLocation().z * chunkSize.z;
        float[] heights = getHeights(chunk, -CANOPY_RADIUS, maxX + CANOPY_RADIUS, -CANOPY_RADIUS, maxZ + CANOPY_RADIUS);

        float minh = heights[0] - 4; // Include mud depth
        // Include tree heights AND the water level : where the ground is below the water level, the
        // column is filled with water up to waterHeight (see the per-block 'horizon' below). Ignoring
        // it wrongly flags deep-water surface chunks as empty -> holes in lakes/seas.
        float maxh = Math.max(heights[1] + TREE_TRUNK_MAX + 2 * CANOPY_RADIUS, waterHeight);

        if (maxh < minWorldY) {
            // all heights are below the min Y of the chunk : the chunk is empty
            return chunk;
        }

        if (minh > maxWorldY) {
            // all heights are above the max Y of the chunk : the chunk is full with ROCK
            short[] blocks = new short[chunkSize.x * chunkSize.y * chunkSize.z];
            short rockId = blockRock.getId();
            Arrays.fill(blocks, rockId);
            chunk.setBlocks(blocks);
            chunk.setLightMap(new byte[chunkSize.x * chunkSize.y * chunkSize.z]);
            return chunk;
        }

        for (int x = 0; x < maxX; x++) {
            int rowx = (x + CANOPY_RADIUS) * sizez;
            int worldX = worldOffsetX + x;

            for (int z = 0; z < maxZ; z++) {
                int gridIndex = (z + CANOPY_RADIUS) + rowx;
                float groundh = heights[2 + gridIndex];
                float density = heights[densityBase + gridIndex];
                float horizon = Math.max(groundh, waterHeight);
                int worldZ = worldOffsetZ + z;

                for (int y = maxY - 1; y >= 0; y--) {
                    int worldY = minWorldY + y;
                    generate(chunk, x, y, z, worldY, groundh, horizon, worldX, worldZ, density);
                }
            }
        }

        generateTrees(chunk, heights);

        chunk.setDirty(true);
        return chunk;
    }

    private void generate(Chunk chunk, int x, int y, int z, int worldY, float groundh, float horizon,
                          int worldX, int worldZ, float density) {
        // The world has a solid floor at y=0 (the bottom faces there are never rendered). Keep it as
        // bedrock so very deep water columns -- whose ground noise dips below 0 -- are not bottomless
        // (otherwise you see straight through the bottom of the water to the background).
        if (worldY <= 0) {
            chunk.setSunlight(x, y, z, 0);
            chunk.addBlock(x, y, z, blockRock);
            return;
        }

        Block block;

        if (worldY > horizon) {
            block = generateAboveHorizon(chunk, x, y, z);

        } else if (worldY == (int) horizon) {
            block = generateSurface(chunk, x, y, z, worldY, groundh, worldX, worldZ, density);

        } else {
            block = generateUnderground(chunk, x, y, z, worldY, groundh, horizon);
        }

        if (block != null) {
            chunk.addBlock(x, y, z, block);
        }
    }

    private Block generateAboveHorizon(Chunk chunk, int x, int y, int z) {
        chunk.setSunlight(x, y, z, 15);
        return null;
    }

    private Block generateSurface(Chunk chunk, int x, int y, int z, int worldY, float groundh,
                                  int worldX, int worldZ, float density) {
        chunk.setSunlight(x, y, z, 0);

        Block block;
        if (worldY > waterHeight) {
            // Altitude tiers : snow caps the highest peaks, bare rock below it, grass lower down.
            if (worldY > snowLine) {
                block = blockSnow;
            } else if (worldY > rockLine) {
                block = blockRock;
            } else {
                block = blockGrass;
                placePlant(chunk, x, y, z, worldX, worldZ, density);
            }
        } else if (worldY == (int) waterHeight && worldY == (int) groundh) {
            block = blockSand;
        } else {
            block = blockWaterSource;
            chunk.setSunlight(x, y, z, 14);
        }
        return block;
    }

    /**
     * Scatters a cross-plane ground plant on a grass surface : grass everywhere, with mushrooms on the
     * shaded forest floor (high density) and sunflowers in the open meadows (low density).
     */
    private void placePlant(Chunk chunk, int x, int y, int z, int worldX, int worldZ, float density) {
        if (y >= 15) {
            // Keep the plant (placed at y+1) inside this chunk.
            return;
        }
        int wx = canonical(worldX);
        int wz = canonical(worldZ);
        if (hash01(wx, wz, CH_GRASS) >= PLANT_COVERAGE) {
            return;
        }
        float kind = hash01(wx, wz, CH_PLANT_KIND);
        Block plant;
        if (density > 0.5f) {
            plant = kind < MUSHROOM_FRACTION ? blockItemMushroom : blockItemGrass;
        } else {
            plant = kind < SUNFLOWER_FRACTION ? blockItemSunflower : blockItemGrass;
        }
        chunk.addBlock(x, y + 1, z, plant);
    }

    private Block generateUnderground(Chunk chunk, int x, int y, int z, int worldY, float groundh, float horizon) {
        Block block;
        if (worldY > groundh) {
            // Above ground but below horizon => in water. Water stays well-lit : its sunlight never drops
            // below WATER_MIN_SUNLIGHT regardless of depth (so deep water is not gloomy).
            block = blockWaterLiquid;
            chunk.setSunlight(x, y, z, Math.max(ChunkLightManager.WATER_MIN_SUNLIGHT, 14 - ((int) horizon - worldY)));

        } else {
            chunk.setSunlight(x, y, z, 0);
            if (waterHeight - worldY < 3 && worldY == (int) groundh) {
                block = blockSand;
            } else if (groundh - worldY < 3) {
                block = blockDirt;
            } else {
                block = blockRock;
            }
        }
        return block;
    }

    private float[] getHeights(Chunk chunk, int minx, int maxx, int minz, int maxz) {
        // Memorize per column : the heightmap is identical for every chunk sharing the same (x, z).
        // The returned array is treated as immutable by all callers, so it is safe to share.
        long key = (((long) chunk.getLocation().x) << 32) | (chunk.getLocation().z & 0xFFFFFFFFL);
        float[] cached = heightsCache.get(key);
        if (cached != null) {
            return cached;
        }
        float[] heights = computeHeights(chunk, minx, maxx, minz, maxz);
        heightsCache.put(key, heights);
        return heights;
    }

    private float[] computeHeights(Chunk chunk, int minx, int maxx, int minz, int maxz) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int worldOffsetX = chunk.getLocation().x * chunkSize.x;
        int worldOffsetZ = chunk.getLocation().z * chunkSize.z;
        int sizex = maxx - minx + 1;
        int sizez = maxz - minz + 1;
        int gridSize = sizex * sizez;
        // Per-column forest density is appended after the legacy heightmap region : the returned array
        // is [min, max, <height grid>, <density grid>]. Readers index density at densityBase + gridIndex.
        int densityBase = 2 + gridSize;
        float min = Float.MAX_VALUE;
        float max = Float.MIN_VALUE;
        float[] heights = new float[2 + 2 * gridSize];
        // A single Vector2f is reused for every noise sample (the noise layers never mutate it,
        // they clone internally) : one allocation per chunk instead of two per sampled column.
        Vector2f sample = new Vector2f();
        for (int x = minx; x <= maxx; x++) {
            int rowx = (x - minx) * sizez;
            for (int z = minz; z <= maxz; z++) {
                int index = (z - minz) + rowx;
                float h = getHeight((float) worldOffsetX + x, (float) worldOffsetZ + z, sample);
                // The height grid lives at [2, 2 + gridSize) -- the SAME slot every reader uses
                // (heights[2 + gridIndex] in the surface fill and generateTrees). Writing heights[index]
                // here stored it 2 slots too early, so readers picked the height 2 cells away in z : on a
                // slope that put a tree's base (and its whole canopy) at the wrong height, and near a chunk
                // z-edge the +2 wrapped into another row, so neighbouring chunks placed the same tree's
                // canopy at different heights -> orphaned "floating" leaves.
                heights[2 + index] = h;
                heights[densityBase + index] = densityAt((float) worldOffsetX + x, (float) worldOffsetZ + z, sample);
                if (h < min) {
                    min = h;
                }
                if (h > max) {
                    max = h;
                }
            }
        }
        heights[0] = min;
        heights[1] = max;
        return heights;
    }

    private void generateTrees(Chunk chunk, float[] heights) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int maxX = chunkSize.x;
        int maxZ = chunkSize.z;
        int sizez = maxZ + 2 * CANOPY_RADIUS + 1;
        int sizex = maxX + 2 * CANOPY_RADIUS + 1;
        int gridSize = sizex * sizez;
        int densityBase = 2 + gridSize;
        int chunkY = chunk.getLocation().y * chunkSize.y;
        int worldOffsetX = chunk.getLocation().x * chunkSize.x;
        int worldOffsetZ = chunk.getLocation().z * chunkSize.z;

        // One scatter cell per TREE_CELL_SIZE blocks. On the torus the hash wraps every 'cellsAcross'
        // cells (~worldSize) so the scatter stays seamless; 0 means the infinite (non-tiling) world.
        int cellsAcross = cellsAcross();
        Vector2f sample = new Vector2f(); // reused for the per-tree species noise lookup

        // Cells whose jittered position can fall inside the canopy-extended chunk footprint.
        int minCellX = Math.floorDiv(worldOffsetX - CANOPY_RADIUS, TREE_CELL_SIZE);
        int maxCellX = Math.floorDiv(worldOffsetX + maxX + CANOPY_RADIUS, TREE_CELL_SIZE);
        int minCellZ = Math.floorDiv(worldOffsetZ - CANOPY_RADIUS, TREE_CELL_SIZE);
        int maxCellZ = Math.floorDiv(worldOffsetZ + maxZ + CANOPY_RADIUS, TREE_CELL_SIZE);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                int hx = cellsAcross > 0 ? Math.floorMod(cellX, cellsAcross) : cellX;
                int hz = cellsAcross > 0 ? Math.floorMod(cellZ, cellsAcross) : cellZ;

                // Jittered tree position inside the cell (world coords), then to chunk-local coords.
                int worldX = cellX * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_X) * TREE_CELL_SIZE);
                int worldZ = cellZ * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_Z) * TREE_CELL_SIZE);
                int x = worldX - worldOffsetX;
                int z = worldZ - worldOffsetZ;
                if (x < -CANOPY_RADIUS || x > maxX + CANOPY_RADIUS
                        || z < -CANOPY_RADIUS || z > maxZ + CANOPY_RADIUS) {
                    continue;
                }

                int index = (z + CANOPY_RADIUS) + (x + CANOPY_RADIUS) * sizez;
                if (index < 2) {
                    // Cells 0/1 of the height grid hold min/max (legacy layout), not real heights.
                    continue;
                }
                // The height grid is laid out as [min, max, h(0), h(1), ...], so a column's height lives at
                // 2 + index -- the SAME slot the surface fill reads (heights[2 + gridIndex] above). Reading
                // heights[index] instead picked the height 2 cells away in z, which on a slope put the trunk
                // base above (or below) the actual ground -> trees floating 1 block over steep terrain.
                float groundh = heights[2 + index];
                int y = (int) groundh - chunkY;
                // Trees grow only on the grassy band (treeBandOk) ; the y bounds are this chunk's vertical
                // slice, so a tree whose base sits in another chunk of the column is skipped here.
                if (y <= -TREE_HEIGHT || y >= chunkSize.z || !treeBandOk(groundh)) {
                    continue;
                }

                // Forest density drives the keep probability : dense groves vs open clearings.
                float density = heights[densityBase + index];
                if (!treeKept(hx, hz, density)) {
                    continue;
                }

                // No trees on steep ground. Same 2 + index height-grid base as groundh above.
                if (slopeAt(heights, 2 + index, sizez, gridSize) > SLOPE_MAX) {
                    continue;
                }

                int trunkHeight = treeTrunkHeight(hx, hz);
                int canopyRadius = treeCanopyRadius(hx, hz);
                TreeSpecies species = selectSpecies(groundh, worldX, worldZ, hash01(hx, hz, CH_SPECIES), sample);
                createTree(chunk, x, y, z, trunkHeight, canopyRadius, species);
            }
        }
    }

    // --- Shared tree-placement rules ------------------------------------------------------------------
    // Single source of truth for WHERE a tree grows and HOW big it is, used both by the voxel generator
    // (generateTrees) and by the far-horizon billboards (forEachTreeAnchor), so the distant trees match
    // the walked-on ones exactly at the chunk-grid seam — no divergence, no double-drawn trees.

    /** Grass band where trees grow : above the shore (waterHeight + 1), at or below the barren rock line. */
    private boolean treeBandOk(float groundh) {
        return groundh > (waterHeight + 1) && groundh <= rockLine;
    }

    /** Forest-density keep test : dense woods keep most cells, clearings keep few (same hash channel). */
    private boolean treeKept(int hx, int hz, float density) {
        return hash01(hx, hz, CH_PLANT) < density * treeMaxProb;
    }

    private int treeTrunkHeight(int hx, int hz) {
        return TREE_TRUNK_MIN + (int) (hash01(hx, hz, CH_TRUNK) * (TREE_TRUNK_MAX - TREE_TRUNK_MIN + 1));
    }

    private int treeCanopyRadius(int hx, int hz) {
        return TREE_CANOPY_MIN + (int) (hash01(hx, hz, CH_CANOPY) * (CANOPY_RADIUS - TREE_CANOPY_MIN + 1));
    }

    /** Receives one scattered tree anchor (base of trunk, world coords). See {@link #forEachTreeAnchor}. */
    @FunctionalInterface
    public interface TreeAnchorConsumer {
        /**
         * @param worldX  tree trunk base, world X
         * @param worldZ  tree trunk base, world Z
         * @param groundY ground height at the trunk base (world Y of the trunk foot)
         * @param species ordinal of the tree species (0=oak, 1=birch, 2=spruce, 3=palm)
         * @param height  total tree height in blocks (trunk + canopy), for the billboard sprite scale
         */
        void accept(float worldX, float worldZ, float groundY, int species, float height);
    }

    /**
     * Enumerates the tree anchors whose (jittered) trunk base falls inside the world-space AABB
     * [minX, maxX] x [minZ, maxZ], applying the <b>same</b> scatter / density / altitude / slope / species
     * rules as {@link #generateTrees} — but without any chunk : heights and density are sampled directly
     * from the noise. Used by the far-horizon billboards so the distant forest matches the voxel one.
     *
     * <p>Allocation-light : a single {@link Vector2f} is reused for every noise lookup, and anchors are
     * pushed to the consumer rather than collected, so the caller controls storage. Safe to call off the
     * render thread (pure functions of seed + coords, like the chunk generator).
     */
    public void forEachTreeAnchor(int minX, int maxX, int minZ, int maxZ, TreeAnchorConsumer out) {
        // Same per-cell scatter as generateTrees : one candidate per TREE_CELL_SIZE cell, hash wrapping
        // every 'cellsAcross' cells on the torus so the placement is seamless at the world seam.
        int cellsAcross = cellsAcross();
        Vector2f sample = new Vector2f();

        int minCellX = Math.floorDiv(minX, TREE_CELL_SIZE);
        int maxCellX = Math.floorDiv(maxX, TREE_CELL_SIZE);
        int minCellZ = Math.floorDiv(minZ, TREE_CELL_SIZE);
        int maxCellZ = Math.floorDiv(maxZ, TREE_CELL_SIZE);

        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                int hx = cellsAcross > 0 ? Math.floorMod(cellX, cellsAcross) : cellX;
                int hz = cellsAcross > 0 ? Math.floorMod(cellZ, cellsAcross) : cellZ;

                // The player cut this tree down : skip its far billboard (cheap check, before the noise).
                if (worldEditOverlay != null && worldEditOverlay.isTreeRemoved(cellKey(hx, hz))) {
                    continue;
                }

                int worldX = cellX * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_X) * TREE_CELL_SIZE);
                int worldZ = cellZ * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_Z) * TREE_CELL_SIZE);
                if (worldX < minX || worldX > maxX || worldZ < minZ || worldZ > maxZ) {
                    continue;
                }

                float groundh = getHeight(worldX, worldZ, sample);
                if (!treeBandOk(groundh)) {
                    continue;
                }
                if (!treeKept(hx, hz, densityAt(worldX, worldZ, sample))) {
                    continue;
                }
                if (farSlope(worldX, worldZ, groundh, sample) > SLOPE_MAX) {
                    continue;
                }

                TreeSpecies species = selectSpecies(groundh, worldX, worldZ, hash01(hx, hz, CH_SPECIES), sample);
                // Mirror createTree's effective trunk/canopy so the billboard height tracks the voxel tree.
                int th = Math.max(2, treeTrunkHeight(hx, hz) + species.trunkBonus);
                int cr = Math.max(1, Math.min(CANOPY_RADIUS, treeCanopyRadius(hx, hz) + species.radiusDelta));
                out.accept(worldX, worldZ, groundh, species.ordinal(), th + 2f * cr);
            }
        }
    }

    /** Max abs height delta to the 4 one-block neighbours (the chunk-free analogue of {@link #slopeAt}). */
    private float farSlope(float worldX, float worldZ, float groundh, Vector2f sample) {
        float s = Math.abs(groundh - getHeight(worldX - 1, worldZ, sample));
        s = Math.max(s, Math.abs(groundh - getHeight(worldX + 1, worldZ, sample)));
        s = Math.max(s, Math.abs(groundh - getHeight(worldX, worldZ - 1, sample)));
        s = Math.max(s, Math.abs(groundh - getHeight(worldX, worldZ + 1, sample)));
        return s;
    }

    /** Forest density in [0, 1] at a world column : 1 in the heart of a wood, 0 in a clearing. Public so
     *  the far horizon (FarTerrainState) can tint the same woods shader-side. See {@link #densityAt}. */
    public float getForestDensity(float worldX, float worldZ) {
        return densityAt(worldX, worldZ, new Vector2f());
    }

    /**
     * Maximum absolute ground-height difference between this column and its four in-grid neighbours.
     * Skips the legacy min/max slots (indices 0/1) and out-of-grid neighbours.
     */
    private float slopeAt(float[] heights, int index, int sizez, int gridSize) {
        float h = heights[index];
        float s = 0f;
        int zpos = index % sizez;
        if (zpos > 0 && index - 1 >= 2) {
            s = Math.max(s, Math.abs(h - heights[index - 1]));
        }
        if (zpos < sizez - 1) {
            s = Math.max(s, Math.abs(h - heights[index + 1]));
        }
        if (index - sizez >= 2) {
            s = Math.max(s, Math.abs(h - heights[index - sizez]));
        }
        if (index + sizez < gridSize) {
            s = Math.max(s, Math.abs(h - heights[index + sizez]));
        }
        return s;
    }

    private float densityAt(float worldX, float worldZ, Vector2f sample) {
        // evaluate(sample, worldSize) is the tiled (seamless) path when worldSize > 0, else plain noise.
        float raw = forestNoise.evaluate(sample.set(worldX, worldZ), worldSize); // ~[-1, 1]
        float n = (raw + 1f) * 0.5f; // [0, 1], concentrated near 0.5
        // Contrast curve : carve crisp dense woods (above the threshold) and bare clearings (below it).
        return smoothstep(FOREST_THRESHOLD - FOREST_EDGE, FOREST_THRESHOLD + FOREST_EDGE, n);
    }

    private static float smoothstep(float a, float b, float x) {
        if (x <= a) {
            return 0f;
        }
        if (x >= b) {
            return 1f;
        }
        float t = (x - a) / (b - a);
        return t * t * (3f - 2f * t);
    }

    /** Deterministic hash in [0, 1) from a (a, b) coordinate and an independent draw channel. */
    private float hash01(int a, int b, int channel) {
        return (scatterNoise.GetWhiteNoiseInt(a, b, channel) + 1f) * 0.5f; // [-1, 1) -> [0, 1)
    }

    /** Wrap a world coordinate into the tiling period so per-column scatter is seamless on the torus. */
    private int canonical(int worldCoord) {
        return worldSize > 0 ? Math.floorMod(worldCoord, (int) worldSize) : worldCoord;
    }

    // --- World-edit overlay keys --------------------------------------------------------------------
    // The far renderers and WorldManager share these so an edit is keyed identically wherever it is
    // recorded or consulted. All keys are CANONICAL (wrapped to the world period) so a torus tile is
    // recorded once and the edit shows in every copy of that tile, like the chunk saves.

    /** Number of scatter cells across the world period (0 = infinite world). Single source for the scatter. */
    private int cellsAcross() {
        return worldSize > 0 ? Math.max(1, Math.round(worldSize / TREE_CELL_SIZE)) : 0;
    }

    /** Packs the canonical scatter-cell hash coords into the overlay's tree key. */
    private long cellKey(int hx, int hz) {
        return WorldEditOverlay.pack(hx, hz);
    }

    /**
     * Canonical chunk-column key (wrapped to the world period, in chunks) for the world-edit overlay's
     * "modified columns" set — so a torus tile is recorded once and shows in every copy of that tile.
     */
    public long modifiedColumnKey(int chunkX, int chunkZ) {
        if (worldSize > 0) {
            int across = Math.max(1, Math.round(worldSize / BlocksConfig.getInstance().getChunkSize().x));
            return WorldEditOverlay.pack(Math.floorMod(chunkX, across), Math.floorMod(chunkZ, across));
        }
        return WorldEditOverlay.pack(chunkX, chunkZ);
    }

    /** Canonical scatter-cell key of the cell that contains the column (worldX, worldZ). */
    public long treeCellKey(int worldX, int worldZ) {
        int across = cellsAcross();
        int cellX = Math.floorDiv(worldX, TREE_CELL_SIZE);
        int cellZ = Math.floorDiv(worldZ, TREE_CELL_SIZE);
        int hx = across > 0 ? Math.floorMod(cellX, across) : cellX;
        int hz = across > 0 ? Math.floorMod(cellZ, across) : cellZ;
        return cellKey(hx, hz);
    }

    /**
     * If a procedural tree's trunk base occupies EXACTLY the column (worldX, worldZ), returns its
     * canonical scatter-cell key ; otherwise {@code -1}. Re-applies the same scatter / jitter /
     * altitude / density / slope rules as {@link #forEachTreeAnchor}, so only a real tree's trunk
     * matches. The jitter never leaves the cell, so the containing cell is recovered by flooring the
     * column to {@link #TREE_CELL_SIZE}. Used by {@code WorldManager} to fell the far billboard when
     * the player chops a trunk log.
     */
    public long trunkAnchorCellKeyAt(int worldX, int worldZ) {
        int across = cellsAcross();
        int cellX = Math.floorDiv(worldX, TREE_CELL_SIZE);
        int cellZ = Math.floorDiv(worldZ, TREE_CELL_SIZE);
        int hx = across > 0 ? Math.floorMod(cellX, across) : cellX;
        int hz = across > 0 ? Math.floorMod(cellZ, across) : cellZ;
        int anchorX = cellX * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_X) * TREE_CELL_SIZE);
        int anchorZ = cellZ * TREE_CELL_SIZE + (int) (hash01(hx, hz, CH_JITTER_Z) * TREE_CELL_SIZE);
        if (anchorX != worldX || anchorZ != worldZ) {
            return -1L; // not the trunk column of this cell's tree
        }
        Vector2f sample = new Vector2f();
        float groundh = getHeight(anchorX, anchorZ, sample);
        if (!treeBandOk(groundh)
                || !treeKept(hx, hz, densityAt(anchorX, anchorZ, sample))
                || farSlope(anchorX, anchorZ, groundh, sample) > SLOPE_MAX) {
            return -1L; // no tree is actually scattered here
        }
        return cellKey(hx, hz);
    }

    /**
     * Picks the tree species without a biome system : spruce caps the cool highlands, palms fringe the
     * warm shore, and the middle band is broadleaf — oak or birch, chosen per wood by a low-frequency
     * (seamless) noise so a given wood leans to one species rather than being randomly mixed.
     */
    private TreeSpecies selectSpecies(float groundh, float worldX, float worldZ, float speciesHash, Vector2f sample) {
        float alt = (groundh - waterHeight) / Math.max(1f, rockLine - waterHeight); // ~[0, 1] across the band
        // Palms are a coastal accent : only in the lowest fringe, and only in scattered groves there.
        if (alt <= PALM_ALT && speciesHash < PALM_FRACTION) {
            return TreeSpecies.PALM;
        }
        if (alt >= SPRUCE_ALT) {
            return TreeSpecies.SPRUCE;
        }
        return speciesNoise.evaluate(sample.set(worldX, worldZ), worldSize) >= 0f
                ? TreeSpecies.BIRCH : TreeSpecies.OAK;
    }

    private void createTree(Chunk chunk, int x, int y, int z, int trunkHeight, int canopyRadius, TreeSpecies species) {
        Block log;
        Block leaves;
        switch (species) {
            case SPRUCE: log = blockSpruceLog; leaves = blockSpruceLeaves; break;
            case BIRCH:  log = blockBirchLog;  leaves = blockBirchLeaves;  break;
            case PALM:   log = blockPalmLog;   leaves = blockPalmLeaves;   break;
            default:     log = blockOakLog;    leaves = blockOakLeaves;    break;
        }
        int th = Math.max(2, trunkHeight + species.trunkBonus);
        int cr = Math.max(1, Math.min(CANOPY_RADIUS, canopyRadius + species.radiusDelta));
        createTrunk(chunk, x, y, z, th, log);
        switch (species.style) {
            case CONE: createConeCanopy(chunk, x, y, z, th, cr, leaves); break;
            case PALM: createPalmCanopy(chunk, x, y, z, th, cr, leaves); break;
            default:   createSphereCanopy(chunk, x, y, z, th, cr, leaves); break;
        }
        createTreeShadow(chunk, x, y, z, th, cr);
    }

    private void createTrunk(Chunk chunk, int posx, int posy, int posz, int trunkHeight, Block log) {
        Vec3i treeLocation = new Vec3i(posx, posy, posz);
        for (int y = 0; y < trunkHeight; y++) {
            addBlock(chunk, treeLocation.add(0, y, 0), log);
        }
    }

    /** Rounded broadleaf crown (oak, birch). */
    private void createSphereCanopy(Chunk chunk, int posx, int posy, int posz, int trunkHeight, int canopyRadius, Block leaves) {
        Vector3f locf = new Vector3f();
        Vec3i loci = new Vec3i();
        Vec3i canopyCenter = new Vec3i(posx, posy + trunkHeight + canopyRadius - 1, posz);

        for (int y = canopyCenter.y - canopyRadius; y <= canopyCenter.y + canopyRadius; y++) {
            for (int x = canopyCenter.x - canopyRadius; x <= canopyCenter.x + canopyRadius; x++) {
                for (int z = canopyCenter.z - canopyRadius; z <= canopyCenter.z + canopyRadius; z++) {
                    locf.set(x, y, z);
                    loci.set(x, y, z);
                    float distance = locf.distance(canopyCenter.toVector3f());
                    if (distance <= canopyRadius && y > canopyCenter.y - canopyRadius) {
                        addBlock(chunk, loci, leaves);
                    }
                }
            }
        }
    }

    /** Conical conifer crown (spruce) : disks shrinking from the trunk top to a single-block tip. */
    private void createConeCanopy(Chunk chunk, int posx, int posy, int posz, int trunkHeight, int canopyRadius, Block leaves) {
        Vec3i loc = new Vec3i();
        int baseY = posy + trunkHeight - 1; // overlap the trunk top
        int height = 2 * canopyRadius + 1;
        for (int layer = 0; layer < height; layer++) {
            int radius = Math.round(canopyRadius * (1f - (float) layer / height));
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx * dx + dz * dz <= radius * radius) {
                        addBlock(chunk, loc.set(posx + dx, baseY + layer, posz + dz), leaves);
                    }
                }
            }
        }
        addBlock(chunk, loc.set(posx, baseY + height, posz), leaves); // tip
    }

    /** Palm crown : a thin frond ring at the trunk top with a few drooping tips. */
    private void createPalmCanopy(Chunk chunk, int posx, int posy, int posz, int trunkHeight, int canopyRadius, Block leaves) {
        Vec3i loc = new Vec3i();
        int topY = posy + trunkHeight;
        int r = Math.max(2, canopyRadius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int d2 = dx * dx + dz * dz;
                if ((d2 <= r * r && d2 >= (r - 1) * (r - 1)) || (dx == 0 && dz == 0)) {
                    addBlock(chunk, loc.set(posx + dx, topY, posz + dz), leaves);
                }
            }
        }
        addBlock(chunk, loc.set(posx + r, topY - 1, posz), leaves);
        addBlock(chunk, loc.set(posx - r, topY - 1, posz), leaves);
        addBlock(chunk, loc.set(posx, topY - 1, posz + r), leaves);
        addBlock(chunk, loc.set(posx, topY - 1, posz - r), leaves);
    }

    private void createTreeShadow(Chunk chunk, int posx, int posy, int posz, int trunkHeight, int canopyRadius) {
        Vector3f locf = new Vector3f();
        Vec3i treeLocation = new Vec3i(posx, posy, posz);

        for (int y = treeLocation.y; y < treeLocation.y + trunkHeight + canopyRadius - 1; y++) {
            for (int x = treeLocation.x - canopyRadius; x <= treeLocation.x + canopyRadius; x++) {
                for (int z = treeLocation.z - canopyRadius; z <= treeLocation.z + canopyRadius; z++) {
                    if (!Chunk.isInsideChunk(x, y, z)) {
                        continue;
                    }
                    locf.set(x, y, z);
                    float distance = locf.distance(new Vector3f(treeLocation.x, y, treeLocation.z));
                    if (distance <= canopyRadius) {
                        chunk.setSunlight(x, y, z, Math.max(0, 11 + ((int) distance)));
                    }
                }
            }
        }
    }

    private void addBlock(Chunk chunk, Vec3i location, Block block) {
        if (Chunk.isInsideChunk(location.x, location.y, location.z)) {
            chunk.addBlock(location, block);
            chunk.setSunlight(location.x, location.y, location.z, 0);
        }
    }

    private void createWorldNoise() {
        // The noise definition changes : any memorized heightmap is now stale.
        heightsCache.clear();
        @SuppressWarnings("java:S2245")
        Random random = new Random(seed);
        layeredNoise = new LayeredNoise();

        // The hard floor is the "soft floor" the low-lying land settles around (the broad lowland/sea-bed
        // shelf). It is kept FIXED and INDEPENDENT of waterHeight : the water level only sets how high the
        // water FILLS the basins, it must not drag the whole terrain shelf down with it. Tying it to
        // waterHeight made a low water level collapse the lowlands into deep pits (drained lakes became
        // bedrock chasms). TERRAIN_BASE_LEVEL = the default world's water height, so that world is
        // generated exactly as before.
        layeredNoise.setHardFloor(true);
        layeredNoise.setHardFloorHeight(TERRAIN_BASE_LEVEL);
        layeredNoise.setHardFloorStrength(0.6f);

        // Local layers (hills/details) keep a floor of their strength so low relief stays rolling, not
        // flat ; only the mountains layer follows the slider fully (it drives altitude -> rock/snow).
        float localRelief = RELIEF_LOCAL_FLOOR + (1f - RELIEF_LOCAL_FLOOR) * reliefAmplitude;

        NoiseLayer mountains = new NoiseLayer("mountains");
        mountains.setSeed(random.nextInt());
        mountains.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        mountains.setStrength(100 * reliefAmplitude);
        mountains.setFrequency(mountains.getFrequency() / 8 * reliefFrequency);
        layeredNoise.addLayer(mountains);

        NoiseLayer hills = new NoiseLayer("Hills");
        hills.setSeed(random.nextInt());
        hills.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        hills.setStrength(41 * localRelief);
        hills.setFrequency(hills.getFrequency() / 3 * reliefFrequency);
        layeredNoise.addLayer(hills);

        NoiseLayer details = new NoiseLayer("Details");
        details.setSeed(random.nextInt());
        details.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        details.setStrength(21 * localRelief);
        details.setFrequency(details.getFrequency() * reliefFrequency);
        layeredNoise.addLayer(details);

        // Forest-density map : a low-frequency field clustering trees into groves and opening clearings.
        // Tiled when worldSize > 0 so it is seamless on the finite torus, exactly like the heightmap.
        forestNoise = new NoiseLayer("forest");
        forestNoise.setSeed(random.nextInt());
        forestNoise.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        forestNoise.setFrequency(forestFrequency);
        forestNoise.setFractalOctaves(FOREST_OCTAVES);

        // Per-wood broadleaf preference (oak vs birch), seamless on the torus.
        speciesNoise = new NoiseLayer("species");
        speciesNoise.setSeed(random.nextInt());
        speciesNoise.setNoiseType(FastNoise.NoiseType.SimplexFractal);
        speciesNoise.setFrequency(SPECIES_FREQUENCY);

        // Deterministic white-noise hash for per-cell/per-column scatter draws (jitter, keep/skip, size).
        scatterNoise = new FastNoise(random.nextInt());
    }

    @Override
    public float getHeight(Vector3f blockLocation) {
        return getHeight(blockLocation.x, blockLocation.z, new Vector2f());
    }

    private float getHeight(float worldX, float worldZ, Vector2f sample) {
        float h = layeredNoise.evaluate(sample.set(worldX, worldZ), worldSize) + GROUND_MIN;
        // Soft ceiling : at high relief the raw noise far exceeds the world ceiling (gridHeight*chunkHeight),
        // so peaks would be flat-cut at the top of the chunk grid. Compress everything above peakSoftStart
        // so it asymptotes smoothly to peakCeiling (just under the ceiling) -- rounded peaks instead of a
        // sliced plateau, applied to the voxel terrain AND the far horizon (single getHeight source). Below
        // peakSoftStart (well above the default-relief max) this is a no-op : the default world is unchanged.
        // C1-continuous at peakSoftStart (unit slope there), so there is no crease along the onset contour.
        if (h > peakSoftStart) {
            float range = peakCeiling - peakSoftStart;
            h = peakSoftStart + range * (1f - (float) Math.exp((peakSoftStart - h) / range));
        }
        return h;
    }

    /**
     * Bounded LRU (access-ordered) cache of per-column heightmaps. Wrapped in a synchronized map by
     * the owner, since chunk generation runs on a thread pool.
     */
    private static final class HeightsCache extends LinkedHashMap<Long, float[]> {
        private static final long serialVersionUID = 1L;
        private final transient int capacity;

        HeightsCache(int capacity) {
            super(capacity * 4 / 3 + 1, 0.75f, true);
            this.capacity = capacity;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, float[]> eldest) {
            return size() > capacity;
        }
    }

}
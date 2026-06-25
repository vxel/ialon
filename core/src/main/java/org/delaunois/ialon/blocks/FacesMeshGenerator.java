package org.delaunois.ialon.blocks;

import com.jme3.bounding.BoundingBox;
import com.jme3.bounding.BoundingVolume;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.debug.WireBox;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.jme.LayerComparator;
import org.delaunois.ialon.blocks.shapes.Liquid;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

/**
 * A chunk mesh generator that creates and combines a quad mesh for each of the visible faces of a block of the same
 * type. In short, the chunk is traversed and all faces that are not visible will not be added to the final mesh. Some
 * extra bookkeeping is done to scan through the neighbours of each block.
 * One geometry is created per type of the block in the chunk. The geometry is attached to the node, and the node is
 * positioned based on the location of the chunk.
 *
 * @author rvandoosselaer
 */
@Slf4j
@ToString(onlyExplicitlyIncluded = true)
public class FacesMeshGenerator implements ChunkMeshGenerator {

    private static final String CHUNK_MESH_TYPE_GENERIC = "generic";
    private static final String CHUNK_MESH_TYPE_WATER = "water";
    // Flat calm-water surface : its own mesh/material (a flat colour, no texture), greedy-merged.
    private static final String CHUNK_MESH_TYPE_WATER_CALM = "water_calm";
    // Fire : its own mesh/material rendered by a procedural flame shader (no atlas texture).
    private static final String CHUNK_MESH_TYPE_FIRE = "fire";
    // Lava : its own mesh/material rendered by a procedural molten-lava shader (no atlas texture).
    private static final String CHUNK_MESH_TYPE_LAVA = "lava";
    // Water shapes that do NOT emit their top face, one per liquid level : used for calm-surface cells
    // whose flat top is produced instead by the greedy calm-water mesher. Their sides/bottom keep the
    // normal textured look. Only SOURCE water (level 5) is calm-rendered ; flowing water keeps its
    // textured sloped top (the scrolling texture conveys the flow).
    private static final Liquid[] LIQUID_NO_TOP = new Liquid[Liquid.LEVEL_MAX + 1];
    static {
        for (int level = 1; level <= Liquid.LEVEL_MAX; level++) {
            LIQUID_NO_TOP[level] = new Liquid(level, false);
        }
    }
    // Cached once : Direction.values() allocates a new array on each call (avoid it in the hot loops).
    private static final Direction[] DIRECTIONS = Direction.values();

    private final WorldSettings config;
    // Built lazily and reused : the flat-colour, alpha-blended material of the calm water surface.
    private Material calmWaterMaterial;
    // Built lazily and reused : the procedural flame material shared by every fire geometry.
    private Material fireMaterial;
    // Built lazily and reused : the procedural molten-lava material shared by every lava geometry.
    private Material lavaMaterial;
    // Built lazily and reused : the flat translucent-orange material for the inside faces of lava.
    private Material lavaInsideMaterial;

    /**
     * Per-thread pool of reusable {@link ChunkMesh} buffers. Meshing runs on a fixed thread pool
     * and each thread meshes a single chunk at a time, so the (expensive, direct-buffer-backed)
     * meshes can be reused across chunks instead of being reallocated each time.
     */
    private final ThreadLocal<MeshPool> meshPool = ThreadLocal.withInitial(MeshPool::new);

    private static final class MeshPool {
        private final ChunkMesh collisionMesh = new ChunkMesh(true);
        private final Map<String, ChunkMesh> renderMeshes = new HashMap<>();
        // Shared visibility mask : faceVisible[direction.ordinal() * volume + blockIndex] for solid
        // cubes, populated by the render pass and consumed by the greedy collision mesher.
        private boolean[] visibilityMask;
        // Calm-water surface collector : per block, whether its flat top must be greedy-meshed, and the
        // (reused) light colour of that top. Populated by the render pass, consumed by the greedy pass.
        private boolean[] calmTop;
        private Vector4f[] calmColor;
        // Local-space top Y of each calm cell (per liquid level) : merged only with equal-height
        // neighbours, and emitted at this height so flowing/source/full tops keep their own level.
        private float[] calmTopY;
        // Per-layer grid of smoothed corner light, indexed gx*(sz+1)+gz. Reused across layers and
        // chunks ; rebuilt for each water layer by addCalmWaterSurfaceMesh before its greedy merge.
        private int[] calmCorner;

        ChunkMesh acquireCollision() {
            collisionMesh.clear();
            return collisionMesh;
        }

        boolean[] acquireCalmTop(int size) {
            if (calmTop == null || calmTop.length < size) {
                calmTop = new boolean[size];
                calmTopY = new float[size];
                calmColor = new Vector4f[size];
                for (int i = 0; i < size; i++) {
                    calmColor[i] = new Vector4f();
                }
            } else {
                Arrays.fill(calmTop, 0, size, false);
            }
            // calmTopY / calmColor need no reset : only entries whose calmTop flag is set are read.
            return calmTop;
        }

        int[] acquireCalmCorner(int size) {
            if (calmCorner == null || calmCorner.length < size) {
                calmCorner = new int[size];
            }
            // No fill needed : fillCornerLight rewrites every entry it later reads, layer by layer.
            return calmCorner;
        }

        boolean[] acquireVisibilityMask(int size) {
            if (visibilityMask == null || visibilityMask.length < size) {
                visibilityMask = new boolean[size];
            } else {
                Arrays.fill(visibilityMask, 0, size, false);
            }
            return visibilityMask;
        }

        ChunkMesh acquireRender(String type) {
            ChunkMesh mesh = renderMeshes.get(type);
            if (mesh == null) {
                mesh = new ChunkMesh();
                renderMeshes.put(type, mesh);
            } else {
                mesh.clear();
            }
            return mesh;
        }
    }

    public FacesMeshGenerator(WorldSettings config) {
        this.config = config;
    }

    @Override
    public Node createNode(Chunk chunk) {
        if (chunk.isEmpty()) {
            return new EmptyNode();
        }

        long start = System.nanoTime();
        ShapeRegistry shapeRegistry = BlocksConfig.getInstance().getShapeRegistry();
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        // create the map holding all the meshes of the chunk
        Map<String, ChunkMesh> meshMap = new HashMap<>();

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);

        BlockNeighborhood neighborhood = new BlockNeighborhood(blockLocation, chunk);
        for (short blockId : chunk.getBlocks()) {
            Block block = blockRegistry.get(blockId);

            // check if there is a block
            if (block != null) {
                // create a mesh for each different block type
                ChunkMesh mesh = meshMap.computeIfAbsent(getChunkMeshType(block), function -> new ChunkMesh());

                // add the block mesh to the chunk mesh
                Shape shape = shapeRegistry.get(block.getShape());
                neighborhood.setLocation(blockLocation);
                addShapeToMesh(block.getType(), shape, mesh, neighborhood);
            }

            // increment the block location
            incrementBlockLocation(blockLocation);
        }

        if (log.isTraceEnabled()) {
            log.trace("Chunk {} meshes construction took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        // create a geometry for each type of block
        meshMap.forEach((type, chunkMesh) -> {
            Geometry geometry = createGeometry(type, chunkMesh);
            if (geometry != null) {
                BlocksConfig.getInstance().getTypeRegistry().transformTextureCoords(geometry, type);
                node.attachChild(geometry);
            }
        });

        // position the node
        node.setLocalTranslation(chunk.getWorldLocation());

        // carry the face-connectivity bitset (cave-culling visibility graph) on the node
        chunk.computeFaceConnectivity();
        node.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, chunk.getFaceConnectivity() & 0xFFFF);

        if (log.isTraceEnabled()) {
            log.trace("Total chunk node generation took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
        return node;
    }

    @Override
    public Mesh createCollisionMesh(Chunk chunk) {
        long start = System.nanoTime();
        ShapeRegistry shapeRegistry = BlocksConfig.getInstance().getShapeRegistry();
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();

        // create the collision mesh
        ChunkMesh collisionMesh = new ChunkMesh(true);

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);

        for (short blockId : chunk.getBlocks()) {
            Block block = blockRegistry.get(blockId);
            if (block != null && block.isSolid()) {
                // add the block to the collision mesh
                Shape shape = shapeRegistry.get(block.getShape());
                shape.add(blockLocation, chunk, collisionMesh);
            }

            // increment the block location
            incrementBlockLocation(blockLocation);
        }

        if (log.isTraceEnabled()) {
            log.trace("Chunk {} collision mesh construction took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        Mesh mesh = collisionMesh.generateMesh();
        collisionMesh.clear();
        if (log.isTraceEnabled()) {
            log.trace("Total collision mesh generation took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        return mesh;
    }

    @Override
    public void createAndSetNodeAndCollisionMesh(Chunk chunk) {
        if (chunk.isEmpty()) {
            chunk.setNode(emptyNodeWithConnectivity(chunk));
            chunk.setCollisionMesh(null);
            return;
        }

        long start = System.nanoTime();

        short[] blocks = chunk.getBlocks();
        if (blocks == null) {
            if (log.isDebugEnabled()) {
                log.debug("Cancelling chunk {} collision mesh creation", chunk);
            }
            return;
        }

        // create the map holding all the meshes of the chunk and the collision mesh.
        // The meshes are reused from a per-thread pool to avoid reallocating direct buffers.
        MeshPool pool = meshPool.get();
        Map<String, ChunkMesh> meshMap = new HashMap<>();
        ChunkMesh collisionMesh = pool.acquireCollision();

        // Shared visibility mask : the render pass records each solid cube's visible faces here so the
        // greedy collision mesher can reuse them instead of recomputing face visibility for the chunk.
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        int volume = chunkSize.x * chunkSize.y * chunkSize.z;
        boolean[] visibilityMask = pool.acquireVisibilityMask(DIRECTIONS.length * volume);
        // Calm-water surface collector : the render pass flags the source-water cells whose flat top is
        // open to the air ; the greedy pass below merges them into the flat-coloured water_calm mesh.
        boolean[] calmTop = pool.acquireCalmTop(volume);

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);
        BlockNeighborhood neighborhood = new BlockNeighborhood(blockLocation, chunk);

        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        for (short blockId : blocks) {
            createMesh(blockRegistry.get(blockId), blockLocation, neighborhood, meshMap, collisionMesh, pool,
                    visibilityMask, volume, chunkSize);

            // increment the block location
            incrementBlockLocation(blockLocation);
        }

        if (log.isTraceEnabled()) {
            log.trace("Chunk {} meshes construction took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        // greedy-mesh the flagged calm-water tops into a single flat-coloured surface mesh (merges the
        // large flat sea/lake surfaces into a handful of quads). Skipped entirely if none were flagged.
        addCalmWaterSurfaceMesh(chunk, meshMap, pool, calmTop, volume, chunkSize);

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        // create a geometry for each type of block
        meshMap.forEach((type, chunkMesh) -> createGeometryAndAttach(type, chunkMesh, node));

        if (node.getVertexCount() == 0) {
            chunk.setNode(emptyNodeWithConnectivity(chunk));
            chunk.setCollisionMesh(null);
            return;
        }

        if (config.isDebugChunks()) {
            node.attachChild(createChunkDebugGeometry());
        }

        // position the node
        node.setLocalTranslation(chunk.getWorldLocation());

        // greedy-mesh the solid full cubes into the collision mesh (merges coplanar exposed faces),
        // reusing the visibility mask populated by the render pass above.
        addCubeCollisionMesh(chunk, collisionMesh, visibilityMask, volume);

        // compute the chunk's face-connectivity bitset (cave-culling visibility graph) and carry it
        // on the node so the renderer's BFS can read it without a chunk lookup.
        chunk.computeFaceConnectivity();
        node.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, chunk.getFaceConnectivity() & 0xFFFF);

        // set the node and collision mesh on the chunk
        chunk.setNode(node);
        chunk.setCollisionMesh(collisionMesh.generateMesh());
        collisionMesh.clear();

        if (log.isTraceEnabled()) {
            log.trace("Total chunk node generation took {}ms", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }
    }

    private void createMesh(Block block,
                            Vec3i blockLocation,
                            BlockNeighborhood neighborhood,
                            Map<String, ChunkMesh> meshMap,
                            ChunkMesh collisionMesh,
                            MeshPool pool,
                            boolean[] visibilityMask,
                            int volume,
                            Vec3i chunkSize
    ) {
        if (block == null) {
            return;
        }

        // create a mesh for each different block type
        ChunkMesh mesh = meshMap.computeIfAbsent(
                getChunkMeshType(block),
                pool::acquireRender);

        // add the block mesh to the chunk mesh
        neighborhood.setLocation(blockLocation);
        Shape shape = BlocksConfig.getInstance().getShapeRegistry().get(block.getShape());
        // Calm water surface : a WATER source block whose flat top is open to the air. Its top is rendered
        // by the greedy calm-water mesher (merged, flat-coloured quads with sky reflection) instead of one
        // textured quad per block, so swap to the no-top shape (sides/bottom still textured). Flowing water
        // keeps its textured sloped top (the scrolling texture conveys the flow).
        if (Objects.equals(block.getType(), TypeIds.WATER)) {
            Shape noTop = flagCalmTopIfExposed(block, blockLocation, neighborhood, pool, chunkSize);
            if (noTop != null) {
                shape = noTop;
            }
        }
        addShapeToMesh(block.getType(), shape, mesh, neighborhood);

        // add the block to the collision mesh.
        // Solid full cubes are deferred to the greedy collision mesher (addCubeCollisionMesh),
        // which merges coplanar exposed faces. addShapeToMesh above has just computed (via Cube.add)
        // the visibility of the 6 faces : record it into the shared mask so the greedy mesher reuses
        // it instead of rescanning the whole chunk. Non-cube solids keep their per-block faces.
        if (block.isSolid()) {
            if (isCollisionCube(block)) {
                int index = blockLocation.z + (blockLocation.y + blockLocation.x * chunkSize.y) * chunkSize.z;
                for (Direction direction : DIRECTIONS) {
                    if (neighborhood.isFaceVisible(direction)) {
                        visibilityMask[direction.ordinal() * volume + index] = true;
                    }
                }
            } else {
                shape.add(neighborhood, collisionMesh);
            }
        }

        // add water if any : a NON-liquid block carrying liquid (e.g. a pole/mushroom/structure placed in
        // water). Its water fills the cell around the block's own shape. The exposed flat top, if any, is
        // routed to the calm mesher too (same as a water block) so structures in water keep the reflective
        // surface instead of a textured matte patch ; the sides stay textured. Pure liquid blocks (water
        // AND lava) are excluded : they are meshed by their own type path above. Lava never co-habits a
        // structural block (pure-cell rule), so a structure logged with liquid is always water.
        if (block.getLiquidLevel() > 0
                && !Objects.equals(block.getType(), TypeIds.WATER)
                && !Objects.equals(block.getType(), TypeIds.LAVA)) {
            mesh = meshMap.computeIfAbsent(TypeIds.WATER, pool::acquireRender);
            Shape noTop = flagCalmTopIfExposed(block, blockLocation, neighborhood, pool, chunkSize);
            if (noTop != null) {
                shape = noTop;
            } else if (block.isLiquidSource()) {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID5);
            } else if (block.getLiquidLevel() == Block.LIQUID_FULL) {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID);
            } else {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID + "_" + block.getLiquidLevel());
            }
            addShapeToMesh(TypeIds.WATER, shape, mesh, neighborhood);
        }
    }

    /**
     * If this cell holds SOURCE water (a still surface) with a flat top open to the air, flag it for the
     * greedy calm-water mesher (record its position, level height and light) and return the no-top liquid
     * shape so the caller draws the sides/bottom but not the textured top. Returns {@code null} otherwise
     * (greedy calm off, not source, or covered by a block above), leaving the caller to draw the normal
     * textured shape. Only SOURCE water is calm-rendered : FLOWING water (levels 1-4) has a sloped top
     * (Liquid.computeHeight tilts its corners towards neighbours) that the flat calm quad cannot
     * represent, so flowing cascades must keep their textured sloped faces. Shared by water blocks
     * (Path A) and liquid-carrying non-water blocks (Path B : poles/structures placed in water).
     */
    private Shape flagCalmTopIfExposed(Block block, Vec3i blockLocation, BlockNeighborhood neighborhood,
                                       MeshPool pool, Vec3i chunkSize) {
        if (!config.isGreedyCalmWater() || pool.calmTop == null || !block.isLiquidSource()
                || neighborhood.getNeighbour(Direction.UP) != null) {
            return null;
        }
        int level = block.getLiquidLevel();
        int index = blockLocation.z + (blockLocation.y + blockLocation.x * chunkSize.y) * chunkSize.z;
        pool.calmTop[index] = true;
        pool.calmTopY[index] = Liquid.topOffset(level);
        neighborhood.getChunk().getLightLevel(blockLocation.x, blockLocation.y, blockLocation.z,
                Direction.UP, pool.calmColor[index]);
        return LIQUID_NO_TOP[level];
    }

    private static boolean isCollisionCube(Block block) {
        return block != null && block.isSolid() && ShapeIds.CUBE.equals(block.getShape());
    }

    /**
     * Greedy-meshes the calm-water surface : the source-water tops flagged by the render pass (flat,
     * open to the air) are merged, per horizontal layer, into the largest possible rectangles sharing
     * the same light colour, and emitted as flat-coloured quads into the {@code water_calm} mesh. This
     * collapses a big sea/lake surface from one quad per block into a handful of quads. The flat colour
     * (no texture, no scrolling) is what makes the merge safe : there is no per-block UV to preserve.
     */
    private void addCalmWaterSurfaceMesh(Chunk chunk, Map<String, ChunkMesh> meshMap, MeshPool pool, boolean[] calmTop,
                                         int volume, Vec3i chunkSize) {
        boolean any = false;
        for (int i = 0; i < volume && !any; i++) {
            any = calmTop[i];
        }
        if (!any) {
            return;
        }

        float blockScale = BlocksConfig.getInstance().getBlockScale();
        Vector4f[] calmColor = pool.calmColor;
        float[] calmTopY = pool.calmTopY;
        ChunkMesh mesh = pool.acquireRender(CHUNK_MESH_TYPE_WATER_CALM);
        int sx = chunkSize.x;
        int sy = chunkSize.y;
        int sz = chunkSize.z;
        // Smoothed light at each grid corner (vertex) of the current layer. Reused per layer.
        int[] corner = pool.acquireCalmCorner((sx + 1) * (sz + 1));
        Vector4f lightScratch = new Vector4f();

        for (int y = 0; y < sy; y++) {
            // Build the smoothed corner-light grid for this layer BEFORE the merge below consumes
            // (clears) calmTop, so neighbour sampling sees the full, untouched layer.
            if (!fillCornerLight(chunk, calmTop, calmColor, corner, y, sx, sy, sz, lightScratch)) {
                continue;
            }
            for (int x = 0; x < sx; x++) {
                for (int z = 0; z < sz; ) {
                    int idx = z + (y + x * sy) * sz;
                    if (!calmTop[idx]) {
                        z++;
                        continue;
                    }
                    Vector4f color = calmColor[idx];
                    float topY = calmTopY[idx];
                    // grow the run along z (same layer, contiguous, same light AND same top height :
                    // different water levels sit at different Y, so they must not merge)
                    int w = 1;
                    while (z + w < sz) {
                        int j = (z + w) + (y + x * sy) * sz;
                        if (!calmTop[j] || !sameColor(calmColor[j], color) || calmTopY[j] != topY) {
                            break;
                        }
                        w++;
                    }
                    // grow along x while the whole z-run stays set with the same light and height
                    int d = 1;
                    boolean grow = true;
                    while (x + d < sx && grow) {
                        for (int k = 0; k < w; k++) {
                            int j = (z + k) + (y + (x + d) * sy) * sz;
                            if (!calmTop[j] || !sameColor(calmColor[j], color) || calmTopY[j] != topY) {
                                grow = false;
                                break;
                            }
                        }
                        if (grow) {
                            d++;
                        }
                    }
                    emitCalmQuad(mesh, x, x + d - 1, z, z + w - 1, y, topY, blockScale, corner, sz);
                    // consume the merged cells
                    for (int dx = 0; dx < d; dx++) {
                        for (int dz = 0; dz < w; dz++) {
                            calmTop[(z + dz) + (y + (x + dx) * sy) * sz] = false;
                        }
                    }
                    z += w;
                }
            }
        }

        if (!mesh.getPositions().isEmpty()) {
            meshMap.put(CHUNK_MESH_TYPE_WATER_CALM, mesh);
        }
    }

    private void emitCalmQuad(ChunkMesh mesh, int x0, int x1, int z0, int z1, int y, float topY, float blockScale, int[] corner, int sz) {
        float xLo = (x0 - 0.5f) * blockScale;
        float xHi = (x1 + 0.5f) * blockScale;
        float zLo = (z0 - 0.5f) * blockScale;
        float zHi = (z1 + 0.5f) * blockScale;
        float yTop = (y + topY) * blockScale;

        // Per-corner smoothed light (the gradient that kills the per-block checkerboard) : the shader
        // unpacks the alpha nibbles per vertex, then the GPU interpolates the result across the quad,
        // exactly like the soft-shadowed cube faces. The grid corner at block boundary (gx, gz) lives
        // at index gx*(sz+1)+gz ; a 1x1 quad spans corners (x0..x0+1, z0..z0+1).
        int cw = sz + 1;
        float lLoLo = corner[x0 * cw + z0];
        float lHiLo = corner[(x1 + 1) * cw + z0];
        float lLoHi = corner[x0 * cw + (z1 + 1)];
        float lHiHi = corner[(x1 + 1) * cw + (z1 + 1)];

        // Vertex colour : RGB = the water tint (the shader's block-lighting overwrites AmbientSum with
        // inColor.rgb, so the tint MUST live in the vertex colour, not the material), A = the packed
        // sun/torch light level so the surface still follows the day/night cycle (no texture is used).
        ColorRGBA c = config.getCalmWaterColor();
        // One scratch per quad (a local, not a shared field : meshing runs on several threads).
        // DirectVector4fBuffer#add copies the components, so mutating w between adds is safe and
        // avoids a Vector4f per vertex.
        Vector4f vcolor = new Vector4f(c.r, c.g, c.b, 0f);

        int offset = mesh.getPositions().size();
        // Winding matches Liquid.createUp (v[3], v[2], v[7], v[6]) so the merged quad faces up.
        mesh.getPositions().add(new Vector3f(xHi, yTop, zLo));
        mesh.getPositions().add(new Vector3f(xLo, yTop, zLo));
        mesh.getPositions().add(new Vector3f(xHi, yTop, zHi));
        mesh.getPositions().add(new Vector3f(xLo, yTop, zHi));
        float[] cornerLight = { lHiLo, lLoLo, lHiHi, lLoHi }; // matches the 4 vertices above
        for (int i = 0; i < 4; i++) {
            mesh.getNormals().add(new Vector3f(0f, 1f, 0f));
            mesh.getUvs().add(new Vector2f(0f, 0f)); // unused : the flat-colour material has no texture
            vcolor.w = cornerLight[i];
            mesh.getColors().add(vcolor);
        }
        mesh.getIndices().add(offset);
        mesh.getIndices().add(offset + 1);
        mesh.getIndices().add(offset + 2);
        mesh.getIndices().add(offset + 1);
        mesh.getIndices().add(offset + 3);
        mesh.getIndices().add(offset + 2);
    }

    /**
     * Builds the smoothed corner-light grid for one water layer. Each grid corner (vertex) gets the
     * average of the (up to four) calm-water tops meeting at it — the same neighbour-averaging the
     * cube soft-shadow uses — so adjacent quads share identical corner values and the surface light
     * varies smoothly instead of stepping per block. Sun and torch nibbles are averaged separately
     * (over the cells actually present, so shores are not darkened) and repacked. Returns false when
     * the layer holds no calm top, letting the caller skip it.
     * <p>
     * Cells OUTSIDE this chunk (the border corners) are resolved through {@code chunk} (cross-chunk),
     * so the gradient is continuous across chunk boundaries : two adjacent chunks compute their shared
     * edge corners from the same world columns and get the same value — no lighting seam at the border.
     */
    private boolean fillCornerLight(Chunk chunk, boolean[] calmTop, Vector4f[] calmColor, int[] corner,
                                    int y, int sx, int sy, int sz, Vector4f scratch) {
        int cw = sz + 1;
        boolean any = false;
        for (int gx = 0; gx <= sx; gx++) {
            for (int gz = 0; gz <= sz; gz++) {
                int sunSum = 0;
                int torchSum = 0;
                int count = 0;
                for (int cx = gx - 1; cx <= gx; cx++) {
                    for (int cz = gz - 1; cz <= gz; cz++) {
                        int packed = cellTopLight(chunk, calmTop, calmColor, cx, y, cz, sx, sy, sz, scratch);
                        if (packed >= 0) {
                            sunSum += (packed >> 4) & 0xF;
                            torchSum += packed & 0xF;
                            count++;
                        }
                    }
                }
                int packed = 0;
                if (count > 0) {
                    packed = ((sunSum / count) << 4) | (torchSum / count);
                    any = true;
                }
                corner[gx * cw + gz] = packed;
            }
        }
        return any;
    }

    /**
     * Packed light (sun high nibble, torch low nibble) of the calm-water top at {@code (cx, y, cz)}, or
     * -1 if that cell is not an exposed water top (so it doesn't contribute to the corner average).
     * In-chunk cells read the cheap render-pass snapshot ({@code calmTop}/{@code calmColor}) ; out-of-chunk
     * cells are resolved through the chunk (cross-chunk aware) so corner light is continuous across chunk
     * borders. The out-of-chunk gate mirrors the in-chunk calm flag (WATER block, liquid, top open to air)
     * so water/land borders never average in land light.
     */
    private int cellTopLight(Chunk chunk, boolean[] calmTop, Vector4f[] calmColor,
                             int cx, int y, int cz, int sx, int sy, int sz, Vector4f scratch) {
        if (cx >= 0 && cx < sx && cz >= 0 && cz < sz) {
            int idx = cz + (y + cx * sy) * sz;
            if (!calmTop[idx]) {
                return -1;
            }
            // & 0xFF : the lightmap byte is signed (full sun 0xF0 arrives as -16.0f).
            return Math.round(calmColor[idx].w) & 0xFF;
        }
        Block nb = chunk.getNeighbour(cx, y, cz, 0, 0, 0);
        if (nb == null || nb.getLiquidLevel() <= 0 || !TypeIds.WATER.equals(nb.getType())
                || chunk.getNeighbour(cx, y, cz, 0, 1, 0) != null) {
            return -1;
        }
        chunk.getLightLevel(cx, y, cz, Direction.UP, scratch);
        return Math.round(scratch.w) & 0xFF;
    }

    private static boolean sameColor(Vector4f a, Vector4f b) {
        return Math.abs(a.x - b.x) < 1e-4f && Math.abs(a.y - b.y) < 1e-4f
                && Math.abs(a.z - b.z) < 1e-4f && Math.abs(a.w - b.w) < 1e-4f;
    }

    /**
     * The flat-colour calm-water surface material : a dedicated water shader (sky reflection + Fresnel
     * + sun glint + normal-perturbation waves), shared by every calm-water surface geometry. The
     * dynamic uniforms (sun direction, sky colours) are pushed each frame by the game's WaterState ;
     * the values set here are first-frame placeholders. {@code synchronized} because chunk meshing
     * runs on worker threads while WaterState fetches/updates it on the render thread.
     */
    public synchronized Material getCalmWaterMaterial() {
        if (calmWaterMaterial == null) {
            // All static tuning (waves, glint, Fresnel, blend, poly offset, placeholder sun/sky values)
            // lives in the j3m ; only the two config-driven values are overridden here.
            Material mat = BlocksConfig.getInstance().getAssetManager().loadMaterial("IalonTheme/water_calm.j3m");
            // Surface transparency at the steepest (looking-down) angle, from the configured water alpha.
            mat.setColor("Diffuse", new ColorRGBA(1f, 1f, 1f, config.getCalmWaterColor().a));
            mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
            calmWaterMaterial = mat;
        }
        return calmWaterMaterial;
    }

    /**
     * The procedural flame material, shared by every fire geometry. Each fire block is a single
     * camera-facing billboard quad expanded in the vertex shader (Fire.j3md), filled by a
     * self-contained noise-based flame shader animated by the engine clock (g_Time) — there is no
     * atlas texture and no per-frame uniform push. The flame area is alpha-blended and emissive (it
     * ignores the voxel light level since fire is a light source). {@code synchronized} because chunk
     * meshing runs on worker threads.
     */
    public synchronized Material getFireMaterial() {
        if (fireMaterial == null) {
            Material mat = BlocksConfig.getInstance().getAssetManager().loadMaterial("IalonTheme/fire.j3m");
            mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
            // Billboard size = one block in world units.
            mat.setFloat("Size", BlocksConfig.getInstance().getBlockScale());
            fireMaterial = mat;
        }
        return fireMaterial;
    }

    /**
     * The procedural molten-lava material, shared by every lava geometry. Lava is a normal opaque
     * cube whose faces are filled by a self-contained noise-based shader (Lava.j3md) animated by the
     * engine clock (g_Time) — there is no atlas texture and no per-frame uniform push. The block is
     * fully emissive (it ignores the voxel light level since lava is a light source). {@code
     * synchronized} because chunk meshing runs on worker threads.
     */
    public synchronized Material getLavaMaterial() {
        if (lavaMaterial == null) {
            Material mat = BlocksConfig.getInstance().getAssetManager().loadMaterial("IalonTheme/lava.j3m");
            mat.setBoolean("ManualSrgb", config.isManualGammaEncode());
            lavaMaterial = mat;
        }
        return lavaMaterial;
    }

    /**
     * The flat, translucent-orange material used for the INSIDE faces of lava (seen when the player is
     * submerged). Shared by every lava geometry. {@code synchronized} because chunk meshing runs on
     * worker threads.
     */
    public synchronized Material getLavaInsideMaterial() {
        if (lavaInsideMaterial == null) {
            // Flat translucent orange (Unshaded) — no procedural shader, no sRGB param needed.
            lavaInsideMaterial = BlocksConfig.getInstance().getAssetManager().loadMaterial("IalonTheme/lava_inside.j3m");
        }
        return lavaInsideMaterial;
    }

    /**
     * Greedy-meshes the solid full cubes of the chunk into the collision mesh : coplanar exposed
     * cube faces are merged into the largest possible rectangles, drastically reducing the triangle
     * count of the physics shape. Safe because the collision mesh carries no UVs/normals/colors, so
     * there is no texture-tiling or per-vertex-lighting constraint on merging.
     * Non-cube solid shapes are NOT handled here (they keep their per-block faces, added elsewhere).
     */
    // package-private for testing (CollisionMeshGreedyTest). Computes face visibility on the fly
    // (no shared mask available — used by the test and any standalone collision-only build).
    void addCubeCollisionMesh(Chunk chunk, ChunkMesh collisionMesh) {
        addCubeCollisionMesh(chunk, collisionMesh, null, 0);
    }

    /**
     * @param visibilityMask shared mask (faceVisible per direction/block) populated by the render
     *                       pass, or {@code null} to recompute face visibility on the fly.
     * @param volume         the chunk volume (used to index the shared mask); ignored if mask is null.
     */
    void addCubeCollisionMesh(Chunk chunk, ChunkMesh collisionMesh, boolean[] visibilityMask, int volume) {
        Vec3i chunkSize = BlocksConfig.getInstance().getChunkSize();
        float blockScale = BlocksConfig.getInstance().getBlockScale();
        int[] dims = { chunkSize.x, chunkSize.y, chunkSize.z };
        int maxDim = Math.max(dims[0], Math.max(dims[1], dims[2]));

        boolean[] mask = new boolean[maxDim * maxDim];
        int[] coord = new int[3];
        Vec3i loc = new Vec3i();
        Vector3f vertex = new Vector3f();

        for (Direction direction : DIRECTIONS) {
            Vec3i dir = direction.getVector();
            int faceAxis = dir.x != 0 ? 0 : (dir.y != 0 ? 1 : 2);
            int sign = dir.x + dir.y + dir.z; // +1 or -1
            int uAxis = (faceAxis == 0) ? 1 : 0;
            int vAxis = (faceAxis == 2) ? 1 : 2;
            int uSize = dims[uAxis];
            int vSize = dims[vAxis];
            int layers = dims[faceAxis];

            for (int s = 0; s < layers; s++) {
                buildCollisionMask(chunk, direction, faceAxis, uAxis, vAxis, s, uSize, vSize, coord, loc, mask,
                        visibilityMask, volume, chunkSize);
                mergeAndEmit(collisionMesh, faceAxis, uAxis, vAxis, s, sign, uSize, vSize, blockScale, mask, vertex);
            }
        }
    }

    private void buildCollisionMask(Chunk chunk, Direction direction, int faceAxis, int uAxis, int vAxis,
                                    int s, int uSize, int vSize, int[] coord, Vec3i loc, boolean[] mask,
                                    boolean[] visibilityMask, int volume, Vec3i chunkSize) {
        int dirOffset = direction.ordinal() * volume;
        for (int vv = 0; vv < vSize; vv++) {
            for (int uu = 0; uu < uSize; uu++) {
                coord[faceAxis] = s;
                coord[uAxis] = uu;
                coord[vAxis] = vv;
                boolean exposed;
                if (visibilityMask != null) {
                    // Reuse the visibility computed by the render pass.
                    int index = coord[2] + (coord[1] + coord[0] * chunkSize.y) * chunkSize.z;
                    exposed = visibilityMask[dirOffset + index];
                } else {
                    Block b = chunk.getBlock(coord[0], coord[1], coord[2]);
                    exposed = isCollisionCube(b)
                            && chunk.isFaceVisible(loc.set(coord[0], coord[1], coord[2]), direction);
                }
                mask[uu + vv * uSize] = exposed;
            }
        }
    }

    private void mergeAndEmit(ChunkMesh mesh, int faceAxis, int uAxis, int vAxis, int s, int sign,
                              int uSize, int vSize, float blockScale, boolean[] mask, Vector3f vertex) {
        for (int vv = 0; vv < vSize; vv++) {
            for (int uu = 0; uu < uSize; ) {
                if (!mask[uu + vv * uSize]) {
                    uu++;
                    continue;
                }
                // grow width along u
                int w = 1;
                while (uu + w < uSize && mask[(uu + w) + vv * uSize]) {
                    w++;
                }
                // grow height along v while the whole row stays set
                int h = 1;
                boolean grow = true;
                while (vv + h < vSize && grow) {
                    for (int k = 0; k < w; k++) {
                        if (!mask[(uu + k) + (vv + h) * uSize]) {
                            grow = false;
                            break;
                        }
                    }
                    if (grow) {
                        h++;
                    }
                }

                float faceCoord = s + 0.5f * sign;
                emitCollisionQuad(mesh, faceAxis, uAxis, vAxis, faceCoord,
                        uu - 0.5f, (uu + w - 1) + 0.5f,
                        vv - 0.5f, (vv + h - 1) + 0.5f,
                        blockScale, vertex);

                // consume the merged cells
                for (int dv = 0; dv < h; dv++) {
                    for (int du = 0; du < w; du++) {
                        mask[(uu + du) + (vv + dv) * uSize] = false;
                    }
                }
                uu += w;
            }
        }
    }

    private void emitCollisionQuad(ChunkMesh mesh, int faceAxis, int uAxis, int vAxis, float faceCoord,
                                   float uLo, float uHi, float vLo, float vHi, float blockScale, Vector3f vertex) {
        int offset = mesh.getPositions().size();
        addCollisionVertex(mesh, faceAxis, uAxis, vAxis, faceCoord, uLo, vLo, blockScale, vertex);
        addCollisionVertex(mesh, faceAxis, uAxis, vAxis, faceCoord, uHi, vLo, blockScale, vertex);
        addCollisionVertex(mesh, faceAxis, uAxis, vAxis, faceCoord, uHi, vHi, blockScale, vertex);
        addCollisionVertex(mesh, faceAxis, uAxis, vAxis, faceCoord, uLo, vHi, blockScale, vertex);
        mesh.getIndices().add(offset);
        mesh.getIndices().add(offset + 1);
        mesh.getIndices().add(offset + 2);
        mesh.getIndices().add(offset);
        mesh.getIndices().add(offset + 2);
        mesh.getIndices().add(offset + 3);
    }

    private void addCollisionVertex(ChunkMesh mesh, int faceAxis, int uAxis, int vAxis,
                                    float faceCoord, float u, float v, float blockScale, Vector3f vertex) {
        setAxis(vertex, faceAxis, faceCoord);
        setAxis(vertex, uAxis, u);
        setAxis(vertex, vAxis, v);
        vertex.multLocal(blockScale);
        mesh.getPositions().add(vertex);
    }

    private static void setAxis(Vector3f v, int axis, float value) {
        if (axis == 0) {
            v.x = value;
        } else if (axis == 1) {
            v.y = value;
        } else {
            v.z = value;
        }
    }

    private String getChunkMeshType(Block block) {
        if (block.getType().equals(TypeIds.WATER)) {
            return CHUNK_MESH_TYPE_WATER;
        }
        if (block.getType().equals(TypeIds.FIRE)) {
            return CHUNK_MESH_TYPE_FIRE;
        }
        if (block.getType().equals(TypeIds.LAVA)) {
            return CHUNK_MESH_TYPE_LAVA;
        }
        return CHUNK_MESH_TYPE_GENERIC;
    }

    private void addShapeToMesh(String textureName, Shape shape, ChunkMesh mesh, BlockNeighborhood neighborhood) {
        int position = mesh.getUvs().getInternalBuffer().position();
        shape.add(neighborhood, mesh);
        int length = mesh.getUvs().getInternalBuffer().position() - position;
        BlocksConfig.getInstance().getTypeRegistry().transformTextureCoords(textureName,
                mesh.getUvs().getInternalBuffer(),
                mesh.getUvs().getInternalBuffer(),
                position,
                length
        );
    }

    /**
     * Builds the {@link EmptyNode} used for a chunk that renders nothing, carrying its (all-connected)
     * face-connectivity bitset as userData so the cave-culling BFS still propagates visibility through it.
     */
    private static EmptyNode emptyNodeWithConnectivity(Chunk chunk) {
        chunk.computeFaceConnectivity();
        EmptyNode emptyNode = new EmptyNode();
        emptyNode.setUserData(Chunk.USERDATA_FACE_CONNECTIVITY, chunk.getFaceConnectivity() & 0xFFFF);
        return emptyNode;
    }

    private void createGeometryAndAttach(String type, ChunkMesh chunkMesh, Node node) {
        Geometry geometry = createGeometry(type, chunkMesh);
        if (geometry != null) {
            if (TypeIds.WATER.equals(type) || CHUNK_MESH_TYPE_WATER_CALM.equals(type)) {
                /*
                 * Special case for water.
                 * Water must be visible from inside and outside.
                 * Setting Face Culling to Off does not work due to incorrect sorting of the faces.
                 *
                 * The only real solution is to split the geometry into 2 objects that share the
                 * same mesh. Have one with back face culling on and one with front face culling on.
                 * Then use a custom GeometryComparator that makes sure the inside one is always
                 * drawn first : the Lemur LayerComparator, that lets Geometries use a UserData to
                 * indicate relative layers. setUserData(“layer”, 0) in
                 * the inside one and setUserData(“layer”, 1) on the outside one. (presuming they both
                 * have the same parent node).
                 */
                LayerComparator.setLayer(geometry, 2);
                Geometry inside = geometry.clone();
                LayerComparator.setLayer(inside, 1);
                inside.getMaterial().getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Front);
                node.attachChild(geometry);
                node.attachChild(inside);

            } else if (CHUNK_MESH_TYPE_LAVA.equals(type)) {
                // Lava is opaque from the outside (molten shader, back-face culled, Opaque bucket — set
                // on `geometry` by createGeometry) but translucent orange from the inside (when the
                // player is submerged). We reuse the same mesh as a front-face-culled clone with a flat
                // translucent-orange material in the Transparent bucket : the opaque outside faces write
                // depth, so the inside copy only shows where the camera is actually within the lava.
                node.attachChild(geometry);
                Geometry inside = geometry.clone();
                inside.setMaterial(getLavaInsideMaterial());
                inside.getMaterial().getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Front);
                inside.setQueueBucket(RenderQueue.Bucket.Transparent);
                node.attachChild(inside);

            } else {
                node.attachChild(geometry);
            }
        }
    }

    public Geometry createChunkDebugGeometry() {
        Material material = new Material(BlocksConfig.getInstance().getAssetManager(), "Common/MatDefs/Misc/Unshaded.j3md");
        material.setColor("Color", new ColorRGBA(0.4f, 0.4f, 0.4f, 1f));

        final float margin = 0f;
        final int chunkSize = config.getChunkSize();
        final float localTranslation = (chunkSize / 2f) - 0.5f + (margin / 2f);
        Geometry debugChunkGeometry = new Geometry("debugChunk",
                new WireBox(chunkSize / 2f - margin, chunkSize / 2f - margin, chunkSize / 2f - margin));
        debugChunkGeometry.setMaterial(material);
        debugChunkGeometry.setLocalScale(BlocksConfig.getInstance().getBlockScale());
        debugChunkGeometry.setLocalTranslation(localTranslation, localTranslation, localTranslation);
        return debugChunkGeometry;
    }

    private Geometry createGeometry(String type, ChunkMesh chunkMesh) {
        Mesh mesh = chunkMesh.generateMesh();
        if (mesh == null) {
            return null;
        }

        Geometry geometry = new Geometry(type, mesh);
        chunkMesh.clear();
        TypeRegistry typeRegistry = BlocksConfig.getInstance().getTypeRegistry();
        switch (type) {
            case CHUNK_MESH_TYPE_WATER:
                typeRegistry.applyMaterial(geometry, type);
                geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
                break;
            case CHUNK_MESH_TYPE_WATER_CALM:
                geometry.setMaterial(getCalmWaterMaterial());
                geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
                break;
            case CHUNK_MESH_TYPE_FIRE:
                geometry.setMaterial(getFireMaterial());
                geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
                // Draw fire AFTER the calm-water surface (layers 2 & 3) in the layer-sorted Transparent
                // bucket, so the emissive flame is not over-painted by the water it stands in/next to.
                // Fire keeps depth-testing (water writes depth), so the part of the flame actually
                // behind the water surface is still correctly hidden.
                LayerComparator.setLayer(geometry, 3);
                // The billboard quads collapse all 4 corners to the block centre, so the bound
                // auto-computed from the positions has no corner extent : grow it by one block so the
                // camera-expanded quad is never wrongly frustum-culled at chunk edges.
                expandBound(mesh, BlocksConfig.getInstance().getBlockScale());
                break;
            case CHUNK_MESH_TYPE_LAVA:
                // Flowing lava (liquid shapes). Outside face : opaque, emissive molten shader in the
                // default Opaque bucket (writes depth). The translucent inside face is added as a clone
                // in createGeometryAndAttach.
                geometry.setMaterial(getLavaMaterial());
                break;
            case CHUNK_MESH_TYPE_GENERIC:
                typeRegistry.applyGenericMaterial(geometry);
                geometry.getMaterial().setBoolean("ManualSrgb", config.isManualGammaEncode());
                break;
            default:
                typeRegistry.applyMaterial(geometry, type);
        }

        return geometry;
    }

    /**
     * Grows the mesh's (axis-aligned) bound by {@code margin} on every axis. Used by the fire
     * billboard, whose vertices all collapse to the block centre and whose final on-screen extent
     * only exists after the vertex shader expands the quad toward the camera.
     */
    private void expandBound(Mesh mesh, float margin) {
        BoundingVolume bv = mesh.getBound();
        if (bv instanceof BoundingBox) {
            BoundingBox bb = (BoundingBox) bv;
            bb.setXExtent(bb.getXExtent() + margin);
            bb.setYExtent(bb.getYExtent() + margin);
            bb.setZExtent(bb.getZExtent() + margin);
            mesh.setBound(bb);
        }
    }

    /**
     * Reverse calculate the index of the block in the chunk blocks array. When looping through the blocks array, this
     * method should be called once per iteration to know the location of the current block.
     * The first passed block location should be Vec3i(0, 0, 0).
     *
     * @param blockLocation the current block location in the chunk block array
     */
    private void incrementBlockLocation(Vec3i blockLocation) {
        // reverse calculate the block location, based on the position in the array.
        // eg. for a chunk(3,3,3) the index is calculated as followed:
        // [0] = block(0,0,0)
        // [1] = block(0,0,1)
        // [2] = block(0,0,2)
        // [3] = block(0,1,0)
        // ...
        // [26] = block(2,2,2)
        if (blockLocation.z + 1 >= BlocksConfig.getInstance().getChunkSize().z) {
            blockLocation.z = 0;
            if (blockLocation.y + 1 >= BlocksConfig.getInstance().getChunkSize().y) {
                blockLocation.y = 0;
                blockLocation.x++;
            } else {
                blockLocation.y++;
            }
        } else {
            blockLocation.z++;
        }
    }

}

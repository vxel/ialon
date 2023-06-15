package org.delaunois.ialon;

import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.debug.WireBox;
import com.jme3.shader.VarType;
import com.rvandoosselaer.blocks.Block;
import com.rvandoosselaer.blocks.BlockRegistry;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMesh;
import com.rvandoosselaer.blocks.ChunkMeshGenerator;
import com.rvandoosselaer.blocks.Shape;
import com.rvandoosselaer.blocks.ShapeIds;
import com.rvandoosselaer.blocks.ShapeRegistry;
import com.rvandoosselaer.blocks.TypeIds;
import com.rvandoosselaer.blocks.TypeRegistry;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.jme.LayerComparator;

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

    private final IalonConfig config;

    public FacesMeshGenerator(IalonConfig config) {
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

        for (short blockId : chunk.getBlocks()) {
            Block block = blockRegistry.get(blockId);

            // check if there is a block
            if (block != null) {
                // create a mesh for each different block type
                ChunkMesh mesh = meshMap.computeIfAbsent(block.getType(), function -> new ChunkMesh());

                // add the block mesh to the chunk mesh
                Shape shape = shapeRegistry.get(block.getShape());
                shape.add(blockLocation, chunk, mesh);
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
            chunk.setNode(new EmptyNode());
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

        // create the map holding all the meshes of the chunk and the collision mesh
        Map<String, ChunkMesh> meshMap = new HashMap<>();
        ChunkMesh collisionMesh = new ChunkMesh(true);

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);
        BlockNeighborhood neighborhood = new BlockNeighborhood(blockLocation, chunk);

        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();
        for (short blockId : blocks) {
            createMesh(blockRegistry.get(blockId), blockLocation, neighborhood, meshMap, collisionMesh);

            // increment the block location
            incrementBlockLocation(blockLocation);
        }

        if (log.isTraceEnabled()) {
            log.trace("Chunk {} meshes construction took {}ms", chunk, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        }

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        // create a geometry for each type of block
        meshMap.forEach((type, chunkMesh) -> createGeometryAndAttach(type, chunkMesh, node));

        if (node.getVertexCount() == 0) {
            chunk.setNode(new EmptyNode());
            chunk.setCollisionMesh(null);
            return;
        }

        if (config.isDebugChunks()) {
            node.attachChild(createChunkDebugGeometry());
        }

        // position the node
        node.setLocalTranslation(chunk.getWorldLocation());

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
                            ChunkMesh collisionMesh
    ) {
        if (block == null) {
            return;
        }

        // create a mesh for each different block type
        ChunkMesh mesh = meshMap.computeIfAbsent(
                getChunkMeshType(block),
                function -> new ChunkMesh());

        // add the block mesh to the chunk mesh
        Shape shape = BlocksConfig.getInstance().getShapeRegistry().get(block.getShape());
        neighborhood.setLocation(blockLocation);
        addShapeToMesh(block.getType(), shape, mesh, neighborhood);

        // add the block to the collision mesh
        if (block.isSolid()) {
            shape.add(neighborhood, collisionMesh);
        }

        // add water if any
        if (block.getLiquidLevel() > 0 && !Objects.equals(block.getType(), TypeIds.WATER)) {
            mesh = meshMap.computeIfAbsent(TypeIds.WATER, function -> new ChunkMesh());
            if (block.isLiquidSource()) {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID5);
            } else if (block.getLiquidLevel() == Block.LIQUID_FULL) {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID);
            } else {
                shape = BlocksConfig.getInstance().getShapeRegistry().get(ShapeIds.LIQUID + "_" + block.getLiquidLevel());
            }
            addShapeToMesh(TypeIds.WATER, shape, mesh, neighborhood);
        }
    }

    private String getChunkMeshType(Block block) {
        if (block.getType().equals(TypeIds.WATER)) {
            return CHUNK_MESH_TYPE_WATER;
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

    private void createGeometryAndAttach(String type, ChunkMesh chunkMesh, Node node) {
        Geometry geometry = createGeometry(type, chunkMesh);
        if (geometry != null) {
            if (TypeIds.WATER.equals(type)) {
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
            case CHUNK_MESH_TYPE_GENERIC:
                typeRegistry.applyGenericMaterial(geometry);
                if (config.getGammaCorrection() > 0) {
                    geometry.getMaterial().setParam("GammaCorrection", VarType.Float, 1.0f / config.getGammaCorrection());
                }
                break;
            default:
                typeRegistry.applyMaterial(geometry, type);
        }

        return geometry;
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

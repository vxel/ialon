package com.rvandoosselaer.blocks;

import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.scene.VertexBuffer;
import com.jme3.util.mikktspace.MikkTSpaceImpl;
import com.jme3.util.mikktspace.MikktspaceTangentGenerator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.BlockNeighborhood;

import java.util.HashMap;
import java.util.Map;
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

    @Override
    public Node createNode(Chunk chunk) {
        long start = System.nanoTime();
        ShapeRegistry shapeRegistry = BlocksConfig.getInstance().getShapeRegistry();

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        // create the map holding all the meshes of the chunk
        Map<String, ChunkMesh> meshMap = new HashMap<>();

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);

        for (Block block : chunk.getBlocks()) {
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
            node.attachChild(geometry);
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

        // create the collision mesh
        ChunkMesh collisionMesh = new ChunkMesh(true);

        // the first block location is (0, 0, 0)
        Vec3i blockLocation = new Vec3i(0, 0, 0);

        for (Block block : chunk.getBlocks()) {
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
        long start = System.nanoTime();
        ShapeRegistry shapeRegistry = BlocksConfig.getInstance().getShapeRegistry();

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        Block[] blocks = chunk.getBlocks();
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

        for (Block block : blocks) {
            // check if there is a block
            if (block != null) {
                // create a mesh for each different block type
                ChunkMesh mesh = meshMap.computeIfAbsent(block.getType(), function -> new ChunkMesh());

                // add the block mesh to the chunk mesh
                Shape shape = shapeRegistry.get(block.getShape());
                neighborhood.setLocation(blockLocation);
                shape.add(neighborhood, mesh);
                //shape.add(blockLocation, chunk, mesh);

                // add the block to the collision mesh
                if (block.isSolid()) {
                    shape.add(neighborhood, collisionMesh);
                }
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
            node.attachChild(geometry);
        });

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

    private Geometry createGeometry(String type, ChunkMesh chunkMesh) {
        Mesh mesh = chunkMesh.generateMesh();
        chunkMesh.clear();
        if (needsTangentGeneration(mesh)) {
            generateTangents(mesh);
        }
        TypeRegistry typeRegistry = BlocksConfig.getInstance().getTypeRegistry();
        Geometry geometry = new Geometry(type, mesh);
        geometry.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        typeRegistry.applyMaterial(geometry, type);

        if (geometry.getMaterial().getAdditionalRenderState().getBlendMode() == RenderState.BlendMode.Alpha) {
            if (log.isTraceEnabled()) {
                log.trace("Setting queue bucket to {} for geometry {}", RenderQueue.Bucket.Transparent, geometry);
            }
            geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            geometry.getMaterial().getAdditionalRenderState().setPolyOffset(-1.0f, -1.0f);

            // we presume that transparent blocks don't cast shadows, except for leaves blocks.
            boolean castShadows = type.contains("leaves");
            geometry.setShadowMode(castShadows ? RenderQueue.ShadowMode.CastAndReceive : RenderQueue.ShadowMode.Receive);
        }
        return geometry;
    }

    private boolean needsTangentGeneration(Mesh mesh) {
        if (mesh.getBuffer(VertexBuffer.Type.Tangent) == null) {
            return true;
        }
        int requiredTangents = mesh.getBuffer(VertexBuffer.Type.Position).getNumElements();
        int currentTangents = mesh.getBuffer(VertexBuffer.Type.Tangent).getNumElements();
        return requiredTangents != currentTangents;
    }

    private void generateTangents(Mesh mesh) {
        long tangentGeneratorStart = System.nanoTime();
        MikktspaceTangentGenerator.genTangSpaceDefault(new MikkTSpaceImpl(mesh));
        if (log.isTraceEnabled()) {
            log.trace("Generating tangents using {} took {}ms", MikktspaceTangentGenerator.class, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - tangentGeneratorStart));
        }
    }

    /**
     * Reverse calculate the index of the block in the chunk blocks array. When looping through the blocks array, this
     * method should be called once per iteration to know the location of the current block.
     * The first passed block location should be Vec3i(0, 0, 0).
     *
     * @param blockLocation the current block location in the chunk block array
     * @return the next block location in the chunk block array
     */
    private Vec3i incrementBlockLocation(Vec3i blockLocation) {
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
        return blockLocation;
    }

}

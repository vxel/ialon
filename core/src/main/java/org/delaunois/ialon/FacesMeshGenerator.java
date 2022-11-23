package org.delaunois.ialon;

import com.jme3.material.RenderState;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.Geometry;
import com.jme3.scene.Mesh;
import com.jme3.scene.Node;
import com.jme3.texture.Texture;
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
            return;
        }

        // create the node of the chunk
        Vec3i chunkLocation = chunk.getLocation();
        Node node = new Node("Chunk - " + chunkLocation);

        long start = System.nanoTime();
        ShapeRegistry shapeRegistry = BlocksConfig.getInstance().getShapeRegistry();
        BlockRegistry blockRegistry = BlocksConfig.getInstance().getBlockRegistry();

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

        for (short blockId : blocks) {
            Block block = blockRegistry.get(blockId);

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

                // add water if any
                if (!Objects.equals(block.getType(), TypeIds.WATER) && block.getLiquidLevel() > 0) {
                    mesh = meshMap.computeIfAbsent(TypeIds.WATER, function -> new ChunkMesh());
                    if (block.isLiquidSource()) {
                        shape = shapeRegistry.get(ShapeIds.LIQUID5);
                    } else if (block.getLiquidLevel() == Block.LIQUID_FULL) {
                        shape = shapeRegistry.get(ShapeIds.LIQUID);
                    } else {
                        shape = shapeRegistry.get(ShapeIds.LIQUID + "_" + block.getLiquidLevel());
                    }
                    shape.add(neighborhood, mesh);
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
            if (geometry.getVertexCount() > 0) {
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
        });

        if (node.getVertexCount() == 0) {
            chunk.setNode(new EmptyNode());
            return;
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

    private Geometry createGeometry(String type, ChunkMesh chunkMesh) {
        Mesh mesh = chunkMesh.generateMesh();
        chunkMesh.clear();
        TypeRegistry typeRegistry = BlocksConfig.getInstance().getTypeRegistry();
        Geometry geometry = new Geometry(type, mesh);
        typeRegistry.applyMaterial(geometry, type);

        if (geometry.getMaterial().getAdditionalRenderState().getBlendMode() == RenderState.BlendMode.Alpha) {
            if (TypeIds.WATER.equals(type)) {
                geometry.setQueueBucket(RenderQueue.Bucket.Transparent);
            }
        } else {
            geometry.getMaterial().getTextureParam("DiffuseMap").getTextureValue()
                    .setMinFilter(Texture.MinFilter.BilinearNearestMipMap);
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

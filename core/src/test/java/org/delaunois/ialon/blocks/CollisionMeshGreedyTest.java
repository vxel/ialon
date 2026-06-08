package org.delaunois.ialon.blocks;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.math.Vector3f;
import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;
import com.jme3.scene.mesh.IndexBuffer;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.FloatBuffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.delaunois.ialon.IalonConfig;
import org.delaunois.ialon.IalonInitializer;

/**
 * Validates the greedy collision mesher (FacesMeshGenerator#addCubeCollisionMesh) :
 * merging coplanar exposed cube faces must conserve the total surface area exactly, and must
 * actually reduce the triangle count where faces are mergeable.
 */
class CollisionMeshGreedyTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void greedyConservesAreaAndReducesTriangles() {
        BlocksConfig config = BlocksConfig.getInstance();
        // Do NOT change the chunk size : Chunk.CHUNK_SIZE is a static snapshot. Use the live value.
        Vec3i cs = config.getChunkSize();
        float blockScale = config.getBlockScale();

        Block rock = config.getBlockRegistry().get(BlockIds.ROCK);
        assertTrue(rock != null && rock.isSolid() && ShapeIds.CUBE.equals(rock.getShape()),
                "test requires ROCK to be a solid cube");

        // Build a chunk : two full floor layers (lots of mergeable faces) + one floating cube.
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        for (int x = 0; x < cs.x; x++) {
            for (int z = 0; z < cs.z; z++) {
                chunk.addBlock(x, 0, z, rock);
                chunk.addBlock(x, 1, z, rock);
            }
        }
        int fx = cs.x / 2;
        int fy = Math.min(4, cs.y - 1);
        int fz = cs.z / 2;
        chunk.addBlock(fx, fy, fz, rock);
        chunk.update();

        // Oracle : expected exposed-face area, computed face-by-face with the same visibility rule.
        float expectedArea = 0f;
        int faceCount = 0;
        Vec3i loc = new Vec3i();
        for (int x = 0; x < cs.x; x++) {
            for (int y = 0; y < cs.y; y++) {
                for (int z = 0; z < cs.z; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    if (b == null || !b.isSolid() || !ShapeIds.CUBE.equals(b.getShape())) {
                        continue;
                    }
                    for (Direction d : Direction.values()) {
                        if (chunk.isFaceVisible(loc.set(x, y, z), d)) {
                            expectedArea += blockScale * blockScale;
                            faceCount++;
                        }
                    }
                }
            }
        }
        assertTrue(faceCount > 0, "test chunk should have exposed faces");

        // Greedy collision mesh.
        FacesMeshGenerator generator = new FacesMeshGenerator(new IalonConfig());
        ChunkMesh collisionMesh = new ChunkMesh(true);
        generator.addCubeCollisionMesh(chunk, collisionMesh);
        Mesh mesh = collisionMesh.generateMesh();

        float greedyArea = triangleArea(mesh);
        assertEquals(expectedArea, greedyArea, 1e-2f,
                "greedy merging must conserve the total exposed surface area");

        int greedyTriangles = mesh.getTriangleCount();
        assertTrue(greedyTriangles < faceCount * 2,
                "greedy merging must reduce the triangle count (got " + greedyTriangles
                        + " for " + faceCount + " faces)");
    }

    /**
     * The shared visibility mask (populated by the render pass) must yield exactly the same collision
     * geometry as recomputing face visibility on the fly. Builds the mask the way the render pass does
     * (recording isFaceVisible for every solid cube face) and compares both collision meshes.
     */
    @Test
    void sharedMaskCollisionMatchesDirectComputation() {
        BlocksConfig config = BlocksConfig.getInstance();
        Vec3i cs = config.getChunkSize();
        Block rock = config.getBlockRegistry().get(BlockIds.ROCK);

        // Varied layout : two floor layers + several floating cubes -> exposed faces in all 6 directions.
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        for (int x = 0; x < cs.x; x++) {
            for (int z = 0; z < cs.z; z++) {
                chunk.addBlock(x, 0, z, rock);
                chunk.addBlock(x, 1, z, rock);
            }
        }
        chunk.addBlock(cs.x / 2, 4, cs.z / 2, rock);
        chunk.addBlock(2, 3, 2, rock);
        chunk.addBlock(2, 3, 3, rock);
        chunk.addBlock(cs.x - 1, 5, cs.z - 1, rock);
        chunk.setLightMap(new byte[cs.x * cs.y * cs.z]);
        chunk.update();

        FacesMeshGenerator generator = new FacesMeshGenerator(new IalonConfig());

        // Reproduce the mask exactly as the render pass records it.
        int volume = cs.x * cs.y * cs.z;
        boolean[] mask = new boolean[Direction.values().length * volume];
        Vec3i loc = new Vec3i();
        for (int x = 0; x < cs.x; x++) {
            for (int y = 0; y < cs.y; y++) {
                for (int z = 0; z < cs.z; z++) {
                    Block b = chunk.getBlock(x, y, z);
                    if (b == null || !b.isSolid() || !ShapeIds.CUBE.equals(b.getShape())) {
                        continue;
                    }
                    int index = z + (y + x * cs.y) * cs.z;
                    for (Direction d : Direction.values()) {
                        if (chunk.isFaceVisible(loc.set(x, y, z), d)) {
                            mask[d.ordinal() * volume + index] = true;
                        }
                    }
                }
            }
        }

        ChunkMesh sharedMaskMesh = new ChunkMesh(true);
        generator.addCubeCollisionMesh(chunk, sharedMaskMesh, mask, volume);
        Mesh meshFromMask = sharedMaskMesh.generateMesh();

        ChunkMesh directMesh = new ChunkMesh(true);
        generator.addCubeCollisionMesh(chunk, directMesh);
        Mesh meshDirect = directMesh.generateMesh();

        assertEquals(meshDirect.getTriangleCount(), meshFromMask.getTriangleCount(),
                "shared mask must produce the same triangle count as direct computation");
        assertEquals(triangleArea(meshDirect), triangleArea(meshFromMask), 1e-3f,
                "shared mask must produce the same collision surface as direct computation");
        assertTrue(meshFromMask.getTriangleCount() > 0, "test chunk should produce collision faces");
    }

    private float triangleArea(Mesh mesh) {
        FloatBuffer pos = mesh.getFloatBuffer(VertexBuffer.Type.Position);
        IndexBuffer idx = mesh.getIndexBuffer();
        Vector3f a = new Vector3f();
        Vector3f b = new Vector3f();
        Vector3f c = new Vector3f();
        float area = 0f;
        for (int i = 0; i < idx.size(); i += 3) {
            readVertex(pos, idx.get(i), a);
            readVertex(pos, idx.get(i + 1), b);
            readVertex(pos, idx.get(i + 2), c);
            // 0.5 * |(b - a) x (c - a)|
            b.subtractLocal(a);
            c.subtractLocal(a);
            area += 0.5f * b.cross(c).length();
        }
        return area;
    }

    private void readVertex(FloatBuffer pos, int index, Vector3f store) {
        store.set(pos.get(index * 3), pos.get(index * 3 + 1), pos.get(index * 3 + 2));
    }
}

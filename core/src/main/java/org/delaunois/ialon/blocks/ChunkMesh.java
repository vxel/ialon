package org.delaunois.ialon.blocks;

import com.jme3.scene.Mesh;
import com.jme3.scene.VertexBuffer;

import java.util.concurrent.TimeUnit;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * A class to help create a mesh for a specific block type of a chunk.
 *
 * @author rvandoosselaer
 */
@Slf4j
@Getter
@NoArgsConstructor
public class ChunkMesh {

    private static final int INITIAL_CAPACITY = 2000;

    // Largest vertex count addressable with 16-bit (unsigned) indices : indices 0..65535.
    private static final int MAX_USHORT_VERTICES = 65536;

    private boolean collisionMesh = false;
    private final DirectVector3fBuffer positions = new DirectVector3fBuffer(INITIAL_CAPACITY);
    private final DirectVector3fBuffer normals = new DirectVector3fBuffer(INITIAL_CAPACITY);
    private final DirectVector4fBuffer tangents = new DirectVector4fBuffer(INITIAL_CAPACITY);
    private final DirectVector2fBuffer uvs = new DirectVector2fBuffer(INITIAL_CAPACITY);
    private final DirectIntBuffer indices = new DirectIntBuffer(INITIAL_CAPACITY);
    private final DirectVector4fBuffer colors = new DirectVector4fBuffer(INITIAL_CAPACITY);
    // Per-vertex texture-array layer index (one float per vertex), populated by the TextureArray path
    // (TypeRegistry.assignLayers via FacesMeshGenerator). Empty for the atlas path and for the
    // procedural fire/lava/calm-water meshes, whose materials don't sample the block array.
    private final DirectFloatBuffer layers = new DirectFloatBuffer(INITIAL_CAPACITY);

    public ChunkMesh(boolean collisionMesh) {
        this.collisionMesh = collisionMesh;
    }

    public Mesh generateMesh() {
        long start = System.nanoTime();
        Mesh mesh = new Mesh();
        // all meshes have a position and index buffer
        mesh.setBuffer(VertexBuffer.Type.Position, 3, positions.getBuffer());

        // Index buffer : use 16-bit indices (half the memory) when the vertex count fits in an
        // unsigned short, falling back to 32-bit only for the (rare) larger meshes. A chunk index
        // references a vertex of this very mesh, so the max index is positions.size() - 1.
        if (positions.size() <= MAX_USHORT_VERTICES) {
            mesh.setBuffer(VertexBuffer.Type.Index, 1, indices.getShortBuffer());
        } else {
            mesh.setBuffer(VertexBuffer.Type.Index, 1, indices.getBuffer());
        }

        // collision meshes don't require uvs, normals and tangents
        if (!isCollisionMesh()) {
            // UVs are block-local [0,1] : store them as 2 normalized unsigned shorts (4 bytes/vertex
            // instead of 8 floats). The shader receives them back in [0,1].
            VertexBuffer uvBuffer = new VertexBuffer(VertexBuffer.Type.TexCoord);
            uvBuffer.setupData(VertexBuffer.Usage.Static, 2, VertexBuffer.Format.UnsignedShort, uvs.getShortBuffer());
            uvBuffer.setNormalized(true);
            mesh.setBuffer(uvBuffer);
            // Texture-array layer index, one per vertex (bound as TexCoord2 -> shader inTexCoord2), as a
            // NON-normalized unsigned byte (1 byte instead of 4 floats ; the shader reads the integer
            // index as a float). Only present on the array path ; the atlas path and procedural meshes
            // leave it empty.
            if (!layers.isEmpty()) {
                VertexBuffer layerBuffer = new VertexBuffer(VertexBuffer.Type.TexCoord2);
                layerBuffer.setupData(VertexBuffer.Usage.Static, 1, VertexBuffer.Format.UnsignedByte, layers.getByteBuffer());
                layerBuffer.setNormalized(false);
                mesh.setBuffer(layerBuffer);
            }
            // Normals are unit vectors : store them as 3 normalized signed bytes (3 bytes/vertex
            // instead of 12). The shader normalizes the interpolated normal, so the byte quantization
            // is imperceptible. See DirectVector3fBuffer#getByteBuffer.
            VertexBuffer normalBuffer = new VertexBuffer(VertexBuffer.Type.Normal);
            normalBuffer.setupData(VertexBuffer.Usage.Static, 3, VertexBuffer.Format.Byte, normals.getByteBuffer());
            normalBuffer.setNormalized(true);
            mesh.setBuffer(normalBuffer);
            if (!tangents.isEmpty()) {
                mesh.setBuffer(VertexBuffer.Type.Tangent, 4, tangents.getBuffer());
            }
            if (!colors.isEmpty()) {
                // Colours are stored as 4 normalized unsigned bytes (RGBA) instead of 4 floats :
                // 4 bytes/vertex instead of 16. The shader receives them back in [0, 1] (normalized).
                // See DirectVector4fBuffer#getByteBuffer for the A-channel (packed light) convention.
                VertexBuffer colorBuffer = new VertexBuffer(VertexBuffer.Type.Color);
                colorBuffer.setupData(VertexBuffer.Usage.Static, 4, VertexBuffer.Format.UnsignedByte, colors.getByteBuffer());
                colorBuffer.setNormalized(true);
                mesh.setBuffer(colorBuffer);
            }
        }
        mesh.updateBound();
        mesh.setStatic();
        long stop = System.nanoTime();
        if (log.isTraceEnabled()) {
            log.trace("Mesh generation took {}ms", TimeUnit.NANOSECONDS.toMillis(stop - start));
        }
        return mesh;
    }

    /**
     * Resets all buffers (position and size) so this instance can be reused for another chunk
     * without reallocating the underlying direct buffers. The grown capacity is retained.
     */
    public void clear() {
        positions.clear();
        normals.clear();
        tangents.clear();
        uvs.clear();
        indices.clear();
        colors.clear();
        layers.clear();
    }

}

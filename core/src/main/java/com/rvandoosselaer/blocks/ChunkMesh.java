package com.rvandoosselaer.blocks;

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

    private boolean collisionMesh = false;
    private final DirectVector3fBuffer positions = new DirectVector3fBuffer(INITIAL_CAPACITY);
    private final DirectVector3fBuffer normals = new DirectVector3fBuffer(INITIAL_CAPACITY);
    private final DirectVector4fBuffer tangents = new DirectVector4fBuffer(INITIAL_CAPACITY);
    private final DirectVector2fBuffer uvs = new DirectVector2fBuffer(INITIAL_CAPACITY);
    private final DirectIntBuffer indices = new DirectIntBuffer(INITIAL_CAPACITY);
    private final DirectVector4fBuffer colors = new DirectVector4fBuffer(INITIAL_CAPACITY);

    public ChunkMesh(boolean collisionMesh) {
        this.collisionMesh = collisionMesh;
    }

    public Mesh generateMesh() {
        long start = System.nanoTime();
        Mesh mesh = new Mesh();
        // all meshes have a position and index buffer
        mesh.setBuffer(VertexBuffer.Type.Position, 3, positions.getBuffer());
        mesh.setBuffer(VertexBuffer.Type.Index, 1, indices.getBuffer());

        // collision meshes don't require uvs, normals and tangents
        if (!isCollisionMesh()) {
            mesh.setBuffer(VertexBuffer.Type.TexCoord, 2, uvs.getBuffer());
            mesh.setBuffer(VertexBuffer.Type.Normal, 3, normals.getBuffer());
            if (!tangents.isEmpty()) {
                mesh.setBuffer(VertexBuffer.Type.Tangent, 4, tangents.getBuffer());
            }
            if (!colors.isEmpty()) {
                mesh.setBuffer(VertexBuffer.Type.Color, 4, colors.getBuffer());
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

    public void clear() {
        // Nothing to do
    }

}

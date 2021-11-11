package com.rvandoosselaer.blocks.shapes;

import com.jme3.math.Vector2f;
import com.jme3.math.Vector3f;
import com.jme3.math.Vector4f;
import com.rvandoosselaer.blocks.BlocksConfig;
import com.rvandoosselaer.blocks.Chunk;
import com.rvandoosselaer.blocks.ChunkMesh;
import com.rvandoosselaer.blocks.Shape;
import com.simsilica.mathd.Vec3i;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.ToString;

/**
 * A shape implementation for double crossing plane.
 *
 * @author Cedric de Launois
 */
@ToString
@RequiredArgsConstructor
public class CrossPlane implements Shape {

    @Override
    public void add(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        // get the block scale, we multiply it with the vertex positions
        float blockScale = BlocksConfig.getInstance().getBlockScale();

        createFirst(location, chunkMesh, blockScale);
        enlightFace(location, chunk, chunkMesh);

        createSecond(location, chunkMesh, blockScale);
        enlightFace(location, chunk, chunkMesh);
    }

    private void enlightFace(Vec3i location, Chunk chunk, ChunkMesh chunkMesh) {
        Vector4f color = chunk.getLightLevel(location);
        List<Vector4f> colors = chunkMesh.getColors();
        colors.add(color);
        colors.add(color);
        colors.add(color);
        colors.add(color);
    }

    private static void createFirst(Vec3i location, ChunkMesh chunkMesh, float blockScale) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, -0.5f), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(-1.0f, 0.0f, -1.0f).normalize());
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
        }
    }

    private static void createSecond(Vec3i location, ChunkMesh chunkMesh, float blockScale) {
        // calculate index offset, we use this to connect the triangles
        int offset = chunkMesh.getPositions().size();
        // vertices
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, -0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(-0.5f, 0.5f, -0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, -0.5f, 0.5f), location, blockScale));
        chunkMesh.getPositions().add(Shape.createVertex(new Vector3f(0.5f, 0.5f, 0.5f), location, blockScale));
        // indices
        chunkMesh.getIndices().add(offset);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 2);
        chunkMesh.getIndices().add(offset + 1);
        chunkMesh.getIndices().add(offset + 3);
        chunkMesh.getIndices().add(offset + 2);

        if (!chunkMesh.isCollisionMesh()) {
            for (int i = 0; i < 4; i++) {
                chunkMesh.getNormals().add(new Vector3f(1.0f, 0.0f, -1.0f).normalize());
            }
            // uvs
            chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(1.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 0.0f / UV_PADDING_FACTOR + UV_PADDING));
            chunkMesh.getUvs().add(new Vector2f(0.0f / UV_PADDING_FACTOR + UV_PADDING, 1.0f / UV_PADDING_FACTOR + UV_PADDING));
        }
    }


}

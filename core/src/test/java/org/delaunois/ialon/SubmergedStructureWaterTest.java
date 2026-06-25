package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;
import com.simsilica.mathd.Vec3i;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.delaunois.ialon.blocks.Chunk;
import org.delaunois.ialon.blocks.FacesMeshGenerator;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeIds;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression test for the z-fighting artifact around a non-liquid block submerged in water (a torch,
 * a ladder/scale, ...). The water co-habiting the structure's cell must cull its faces against
 * neighbouring water just like a pure water cell does : otherwise two coplanar water quads are emitted
 * at the structure↔water boundary (one from each side) and z-fight, producing the diagonal moiré the
 * user observed only when an object sits in the water.
 */
class SubmergedStructureWaterTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    @BeforeAll
    static void setUp() {
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), new IalonConfig());
    }

    @Test
    void submergedStructureAddsNoInteriorWaterFaces() {
        IalonConfig config = new IalonConfig();
        config.setGreedyCalmWater(true);

        BlocksConfig blocksConfig = BlocksConfig.getInstance();
        Block waterSource = blocksConfig.getBlockRegistry().get(BlockIds.WATER_SOURCE);
        assertNotNull(waterSource, "test requires the water source block");

        // A SOURCE-level (full) liquid co-habiting a non-cube, non-liquid structure : exactly a
        // ladder/torch/seaweed submerged in still water.
        Block structureInWater = findSubmergedStructureSource(blocksConfig);
        assertNotNull(structureInWater, "test requires a registered structure-in-water source variant");

        // Baseline : a solid water cube, the centre cell being plain source water (fully interior).
        int baselineFaces = waterTriangles(buildWaterCube(waterSource, null));
        // Same cube, centre cell replaced by the submerged structure (still carrying source water).
        int withStructure = waterTriangles(buildWaterCube(waterSource, structureInWater));

        // The structure cell is fully surrounded by water, so its co-habiting water is entirely
        // interior : it must add zero water faces, exactly like the plain-water baseline.
        assertEquals(baselineFaces, withStructure,
                "submerged structure must not emit interior water faces (z-fighting) around itself");
    }

    /** Builds a 5x5x5 source-water cube centred at (8,8,8); optionally replaces the centre cell. */
    private static Node buildWaterCube(Block water, Block centreOverride) {
        Chunk chunk = Chunk.createAt(new Vec3i(0, 0, 0));
        for (int x = 6; x <= 10; x++) {
            for (int y = 6; y <= 10; y++) {
                for (int z = 6; z <= 10; z++) {
                    chunk.addBlock(x, y, z, water);
                }
            }
        }
        if (centreOverride != null) {
            chunk.addBlock(8, 8, 8, centreOverride);
        }
        chunk.update();

        FacesMeshGenerator generator = new FacesMeshGenerator(new IalonConfig());
        generator.createAndSetNodeAndCollisionMesh(chunk);
        return chunk.getNode();
    }

    private static int waterTriangles(Node node) {
        for (Spatial child : node.getChildren()) {
            if (child instanceof Geometry && TypeIds.WATER.equals(child.getName())) {
                return ((Geometry) child).getMesh().getTriangleCount();
            }
        }
        return 0;
    }

    private static Block findSubmergedStructureSource(BlocksConfig blocksConfig) {
        for (Block b : blocksConfig.getBlockRegistry().getAll()) {
            if (b.getLiquidLevel() > 0 && b.isLiquidSource()
                    && !TypeIds.WATER.equals(b.getType())
                    && !TypeIds.LAVA.equals(b.getType())
                    && !ShapeIds.CUBE.equals(b.getShape())) {
                return b;
            }
        }
        return null;
    }
}

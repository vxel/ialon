package org.delaunois.ialon;

import com.rvandoosselaer.blocks.BlockIds;

import org.delaunois.ialon.support.BaseSceneryTest;
import org.junit.jupiter.api.Test;

class WaterTest extends BaseSceneryTest {

    @Test
    void testAddSource() {
        init("water-ut1");

        // Add a water source. Horizontal flow. No block.
        addBlock(BlockIds.WATER_SOURCE, 8, 9, 8);
        waitLiquidSimulationEnd();
        verify("expected.zblock", "the water should flow");

        // Remove the water source
        removeSourceBlock(8, 9, 8);
        waitLiquidSimulationEnd();
        verify("chunk_0_0_0.zblock", "the water should have disappeared");
    }

    @Test
    void testAddSource2() {
        init("water-ut2");

        // Add a water source. Horizontal flow. Blocks stopping flow.
        addBlock(BlockIds.WATER_SOURCE, 11, 9, 7);
        addBlock(BlockIds.WATER_SOURCE, 6, 9, 7);
        waitLiquidSimulationEnd();
        verify("expected.zblock", "the water should flow");

        // Remove the water source
        removeSourceBlock(11, 9, 7);
        removeSourceBlock(6, 9, 7);
        waitLiquidSimulationEnd();
        verify("chunk_0_0_0.zblock", "the water should have disappeared");
    }

    @Test
    void testAddSource3() {
        init("water-ut3");

        // Water cascade (vertical flow)
        addBlock(BlockIds.WATER_SOURCE, 8, 13, 7);
        waitLiquidSimulationEnd();
        verify("expected.zblock", "the water should flow");

        // Remove the water source
        removeSourceBlock(8, 13, 7);
        waitLiquidSimulationEnd();
        verify("chunk_0_0_0.zblock", "the water should have disappeared");
    }

}

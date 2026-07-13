package org.delaunois.ialon;

import com.jme3.asset.DesktopAssetManager;
import com.jme3.util.BufferAllocatorFactory;
import com.jme3.util.PrimitiveAllocator;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlocksConfig;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards that the YAML-driven {@link IalonBlockCatalog} reproduces, byte-for-byte, the block catalog
 * that the former {@code IalonBlock} enum produced. The golden fixture
 * {@code block-catalog-golden.txt} was captured from the enum path before the refactor.
 */
class BlockCatalogParityTest {

    static {
        System.setProperty(BufferAllocatorFactory.PROPERTY_BUFFER_ALLOCATOR_IMPLEMENTATION, PrimitiveAllocator.class.getName());
    }

    private List<String> dump(IalonConfig config) {
        List<String> lines = new ArrayList<>();

        Collection<Block> blocks = BlocksConfig.getInstance().getBlockRegistry().getAll();
        List<String> blockLines = new ArrayList<>();
        for (Block b : blocks) {
            blockLines.add(String.format("BLOCK|%s|%s|%s|solid=%b|transparent=%b|multi=%b|torch=%b|terrain=%b|liquid=%d",
                    b.getName(), b.getType(), b.getShape(),
                    b.isSolid(), b.isTransparent(), b.isUsingMultipleImages(), b.isTorchlight(), b.isTerrain(),
                    b.getLiquidLevelId()));
        }
        blockLines.sort(String::compareTo);
        lines.addAll(blockLines);

        List<String> typeLines = new ArrayList<>();
        for (String t : BlocksConfig.getInstance().getTypeRegistry().getAll()) {
            typeLines.add("TYPE|" + t);
        }
        typeLines.sort(String::compareTo);
        lines.addAll(typeLines);

        List<String> placeable = config.getBlockCatalog().getPlaceableBlockNames();
        for (int i = 0; i < placeable.size(); i++) {
            lines.add(String.format("PLACEABLE|%03d|%s", i, placeable.get(i)));
        }
        return lines;
    }

    @Test
    void matchesGolden() throws Exception {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);

        List<String> actual = dump(config);

        List<String> expected;
        try (InputStream in = getClass().getResourceAsStream("/block-catalog-golden.txt")) {
            assertNotNull(in, "golden fixture missing");
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            expected = new ArrayList<>(List.of(content.split("\n")));
        }

        assertEquals(String.join("\n", expected), String.join("\n", actual),
                "YAML catalog diverged from the golden enum-based catalog");
    }

    @Test
    void keySpotChecks() {
        IalonConfig config = new IalonConfig();
        IalonInitializer.configureBlocksFramework(new DesktopAssetManager(true), config);
        var registry = BlocksConfig.getInstance().getBlockRegistry();

        assertNotNull(registry.get("water-liquid_1"));
        assertNotNull(registry.get("water_source"));
        assertNotNull(registry.get("lava_source"));
        assertNotNull(registry.get("stone_bricks"));
        assertNotNull(registry.get("stone_bricks-slab_up-0"));
        assertNotNull(registry.get("stone_bricks-slab_up-7"));
        assertNotNull(registry.get("white_light-shortpole_up-0"));
        assertNotNull(registry.get("grass"));

        assertTrue(registry.get("grass").isTerrain());
        assertFalse(registry.get("grass-slab_up-0").isTerrain());

        // Every block type is now registered with its explicit texture/material — including the
        // procedural fire/lava (registered with their .j3m ; carrying no diffuse tile, they are skipped
        // by the texture array). getMaterial confirms they resolve.
        var typeRegistry = BlocksConfig.getInstance().getTypeRegistry();
        Collection<String> types = typeRegistry.getAll();
        assertTrue(types.contains("water"));
        assertTrue(types.contains("fire"));
        assertTrue(types.contains("lava"));
        assertNotNull(typeRegistry.get("fire"));
        assertNotNull(typeRegistry.get("lava"));
    }
}

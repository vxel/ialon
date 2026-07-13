/*
 * Copyright (C) 2022 Cédric de Launois
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.delaunois.ialon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.jme3.asset.AssetKey;
import com.jme3.asset.AssetManager;

import org.delaunois.ialon.blocks.Block;
import org.delaunois.ialon.blocks.BlockIds;
import org.delaunois.ialon.blocks.BlockRegistry;
import org.delaunois.ialon.blocks.ShapeIds;
import org.delaunois.ialon.blocks.TypeRegistry;
import org.delaunois.ialon.blocks.WaterLevel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * The Ialon block catalog : the single source of truth for every block, loaded from the
 * {@code Blocks/blocks.yaml} asset. It replaces the former {@code IalonBlock} / {@code IalonShapeSet}
 * enums and the hand-coded {@code IalonInitializer.registerIalonBlocks()} path.
 * <p>
 * {@link #registerAll(TypeRegistry, BlockRegistry)} performs the exact same registration as before :
 * <ul>
 *   <li>a type is registered in the {@link TypeRegistry} only when its render mode is
 *       {@link IalonBlockDef.RenderMode#ATLAS} and it isn't already registered (this generalizes the
 *       old hard-coded fire/lava skip) ;</li>
 *   <li>a catalog block fans out into one {@link Block} for the CUBE shape (carrying the
 *       {@code terrain} flag) and, for every other shape, one block per liquid level 0..7 ;</li>
 *   <li>a liquid family registers one explicitly-named block per variant.</li>
 * </ul>
 * The placeable list ({@link #getPlaceableBlockNames()}, ordered by {@code palette.order}) drives the
 * block-selection slider, replacing its former hand-maintained {@code BLOCK_IDS} array.
 * <p>
 * This class lives in the root game package (not in {@code blocks}) because it carries game concerns
 * the engine must not know about — palette/ordering (UI), render mode (atlas exclusion) and terrain.
 */
@Slf4j
public class IalonBlockCatalog {

    public static final String CATALOG_ASSET = "Blocks/blocks.yaml";

    /** Jackson binding target for the YAML root document. */
    @Getter
    @Setter
    public static class Root {
        private Map<String, List<String>> shapeSets;
        private List<IalonBlockDef> blocks;
    }

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    private final Root root;
    private final List<String> placeableBlockNames = new ArrayList<>();

    private IalonBlockCatalog(Root root) {
        this.root = root;
    }

    /**
     * Load the catalog from the {@code Blocks/blocks.yaml} asset. Uses the same cross-platform asset
     * stream mechanism as the rest of the game (desktop + Android).
     */
    public static IalonBlockCatalog load(@NonNull AssetManager assetManager) {
        try (InputStream in = assetManager.locateAsset(new AssetKey<>(CATALOG_ASSET)).openStream()) {
            Root root = MAPPER.readValue(in, Root.class);
            if (root.getBlocks() == null) {
                throw new IllegalStateException("No blocks defined in " + CATALOG_ASSET);
            }
            log.info("Loaded {} block definitions from {}", root.getBlocks().size(), CATALOG_ASSET);
            return new IalonBlockCatalog(root);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to read block catalog " + CATALOG_ASSET, e);
        }
    }

    /**
     * Register every type and block described by the catalog. Must run before the block texture
     * array is built (which reads the registered types).
     */
    public void registerAll(@NonNull TypeRegistry typeRegistry, @NonNull BlockRegistry blockRegistry) {
        // Asset paths in the catalog are relative to the folder holding blocks.yaml (e.g. "Blocks/").
        String base = CATALOG_ASSET.contains("/")
                ? CATALOG_ASSET.substring(0, CATALOG_ASSET.lastIndexOf('/') + 1)
                : "";
        Collection<String> registered = new HashSet<>();

        List<PaletteEntry> palette = new ArrayList<>();

        for (IalonBlockDef def : root.getBlocks()) {
            // Type registration from the explicit appearance : a `material` (.j3m) is loaded directly
            // (procedural fire/lava — no diffuse tile, so skipped by the texture array) ; otherwise the
            // `texture` (.png) is packed into the array. Each type is registered once (variants share it).
            if (!registered.contains(def.getType())) {
                if (def.getMaterial() != null) {
                    typeRegistry.registerMaterial(def.getType(), base + def.getMaterial());
                } else if (def.getTexture() != null) {
                    typeRegistry.registerTexture(def.getType(), base + def.getTexture());
                } else {
                    throw new IllegalStateException("Block type " + def.getType() + " has neither texture nor material");
                }
                registered.add(def.getType());
            }

            if (def.getVariants() != null && !def.getVariants().isEmpty()) {
                registerVariants(def, blockRegistry, palette);
            } else {
                registerCatalogBlock(def, blockRegistry, palette);
            }
        }

        palette.sort(Comparator.comparingInt(p -> p.order));
        placeableBlockNames.clear();
        for (PaletteEntry entry : palette) {
            placeableBlockNames.add(entry.name);
        }
    }

    private void registerVariants(IalonBlockDef def, BlockRegistry blockRegistry, List<PaletteEntry> palette) {
        for (IalonBlockDef.Variant v : def.getVariants()) {
            blockRegistry.register(Block.builder()
                    .name(v.getName())
                    .type(def.getType())
                    .shape(v.getShape())
                    .solid(def.isSolid())
                    .transparent(def.isTransparent())
                    .usingMultipleImages(def.isMultitexture())
                    .torchlight(def.isTorchlight())
                    .liquidLevel((byte) v.getLevel())
                    .build());

            if (v.getPalette() != null) {
                palette.add(new PaletteEntry(v.getPalette().getOrder(), v.getName()));
            }
        }
    }

    private void registerCatalogBlock(IalonBlockDef def, BlockRegistry blockRegistry, List<PaletteEntry> palette) {
        for (String shape : resolveShapes(def)) {
            if (ShapeIds.CUBE.equals(shape)) {
                blockRegistry.register(Block.builder()
                        .name(BlockIds.getName(def.getType(), shape))
                        .type(def.getType())
                        .shape(shape)
                        .solid(def.isSolid())
                        .transparent(def.isTransparent())
                        .usingMultipleImages(def.isMultitexture())
                        .torchlight(def.isTorchlight())
                        // terrain only applies to the CUBE form (matches the former registerIalonBlock)
                        .terrain(def.isTerrain())
                        .build());
            } else {
                for (byte level : WaterLevel.getAllLevels()) {
                    blockRegistry.register(Block.builder()
                            .name(BlockIds.getName(def.getType(), shape, level))
                            .type(def.getType())
                            .shape(shape)
                            .solid(def.isSolid())
                            .transparent(def.isTransparent())
                            .usingMultipleImages(def.isMultitexture())
                            .torchlight(def.isTorchlight())
                            .liquidLevel(level)
                            .build());
                }
            }
        }

        if (def.getPalette() != null) {
            String paletteShape = def.getPalette().getShape();
            String name = ShapeIds.CUBE.equals(paletteShape)
                    ? BlockIds.getName(def.getType(), paletteShape)
                    : BlockIds.getName(def.getType(), paletteShape, 0);
            palette.add(new PaletteEntry(def.getPalette().getOrder(), name));
        }
    }

    private List<String> resolveShapes(IalonBlockDef def) {
        List<String> shapes = new ArrayList<>();
        if (def.getShapeSet() != null) {
            List<String> set = root.getShapeSets() == null ? null : root.getShapeSets().get(def.getShapeSet());
            if (set == null) {
                throw new IllegalStateException("Unknown shapeSet '" + def.getShapeSet() + "' for type " + def.getType());
            }
            shapes.addAll(set);
        }
        if (def.getShapes() != null) {
            shapes.addAll(def.getShapes());
        }
        if (shapes.isEmpty()) {
            throw new IllegalStateException("Block type " + def.getType() + " declares no shapes");
        }
        return shapes;
    }

    /** Ordered list of placeable block names (ids), replacing the slider's former BLOCK_IDS array. */
    public List<String> getPlaceableBlockNames() {
        return Collections.unmodifiableList(placeableBlockNames);
    }

    private static final class PaletteEntry {
        private final int order;
        private final String name;

        private PaletteEntry(int order, String name) {
            this.order = order;
            this.name = name;
        }
    }
}

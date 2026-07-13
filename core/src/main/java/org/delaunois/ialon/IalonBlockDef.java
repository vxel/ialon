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

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

/**
 * A block-family entry read from {@code Blocks/blocks.yaml}. A definition is either a
 * <b>catalog block</b> (a {@code shapeSet} and/or a {@code shapes} list, fanned out into one
 * registered block per shape — and, for non-cube shapes, one per liquid level) or a
 * <b>liquid family</b> (a list of {@code variants}, each an explicitly named single-shape,
 * single-level block, as used by water and lava).
 * <p>
 * The block's appearance is stated explicitly (this replaces the former {@code render} enum and
 * the filename-convention theme lookup) : either a {@code texture} (a diffuse image packed into
 * the block texture array) or a {@code material} (a {@code .j3m} rendered directly, e.g. the
 * procedural fire/lava shaders — kept out of the array as it carries no diffuse tile). Both paths
 * are relative to the folder holding {@code blocks.yaml} (i.e. {@code Blocks/}), so the catalog +
 * its {@code Textures/} folder form a self-contained theme.
 * <p>
 * Field defaults mirror the historical {@code IalonBlock} enum : {@code solid=true},
 * {@code transparent=false}, {@code multitexture=false}, {@code torchlight=false},
 * {@code terrain=false}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IalonBlockDef {

    private String type;
    private boolean solid = true;
    private boolean transparent = false;
    private boolean multitexture = false;
    private boolean torchlight = false;
    private boolean terrain = false;

    /** Diffuse texture path (relative to the catalog folder). Atlas-array block. */
    private String texture;

    /** Material {@code .j3m} path (relative to the catalog folder). Procedural, kept out of the array. */
    private String material;

    /** Name of a reusable shape bundle declared under {@code shapeSets:}. */
    private String shapeSet;

    /** Inline shape ids (concatenated after the resolved {@code shapeSet}, if any). */
    private List<String> shapes;

    /** Liquid variants (mutually exclusive with a catalog block's shapes in practice). */
    private List<Variant> variants;

    /** Palette entry making this block placeable through the block-selection slider. */
    private Palette palette;

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Variant {
        private String name;
        private String shape;
        private int level;
        private Palette palette;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Palette {
        private int order;
        private String shape;
    }
}

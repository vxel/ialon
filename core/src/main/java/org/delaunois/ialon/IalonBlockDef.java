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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Locale;

import lombok.Getter;
import lombok.Setter;

/**
 * A block-family entry read from {@code Blocks/blocks.yaml}. A definition is either a
 * <b>catalog block</b> (a {@code shapeSet} and/or a {@code shapes} list, fanned out into one
 * registered block per shape — and, for non-cube shapes, one per liquid level) or a
 * <b>liquid family</b> (a list of {@code variants}, each an explicitly named single-shape,
 * single-level block, as used by water and lava).
 * <p>
 * Field defaults mirror the historical {@code IalonBlock} enum : {@code solid=true},
 * {@code transparent=false}, {@code multitexture=false}, {@code torchlight=false},
 * {@code terrain=false}, {@code render=ATLAS}.
 */
@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class IalonBlockDef {

    /**
     * How a block type's faces are textured. {@code ATLAS} types are registered in the
     * {@code TypeRegistry} and packed into the block texture array ; {@code FIRE} and {@code LAVA}
     * are rendered by dedicated procedural shaders and are therefore kept out of the atlas (their
     * type is not registered), leaving their shape UVs raw for the shaders.
     */
    public enum RenderMode {
        ATLAS, FIRE, LAVA;

        /** Parse the lowercase YAML value (atlas/fire/lava) case-insensitively. */
        @JsonCreator
        public static RenderMode from(String value) {
            return value == null ? ATLAS : RenderMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        }
    }

    private String type;
    private boolean solid = true;
    private boolean transparent = false;
    private boolean multitexture = false;
    private boolean torchlight = false;
    private boolean terrain = false;
    private RenderMode render = RenderMode.ATLAS;

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

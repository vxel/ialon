package org.delaunois.ialon;

public enum IalonBlock {

    TILE_RED("tile_red"),
    SLATE("slate"),
    METAL1("metal1"),
    METAL2("metal2", true, true),
    METAL3("metal3"),
    METAL4("metal4"),
    METAL5("metal5", true, true);

    private final String name;
    private final boolean solid;
    private final boolean transparent;

    IalonBlock(String name) {
        this.name = name;
        this.solid = true;
        this.transparent = false;
    }

    IalonBlock(String name, boolean solid, boolean transparent) {
        this.name = name;
        this.transparent = transparent;
        this.solid = solid;
    }

    public String getName() {
        return name;
    }

    public boolean isSolid() {
        return solid;
    }

    public boolean isTransparent() {
        return transparent;
    }
}

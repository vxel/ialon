package org.delaunois.ialon;

public enum IalonBlock {

    METAL1("metal1"),
    METAL2("metal2"),
    METAL3("metal3"),
    METAL4("metal4"),
    METAL5("metal5");

    private final String name;

    IalonBlock(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }
}

package org.delaunois.ialon;

public enum WaterLevel {

    NONE((byte)0),
    LEVEL1((byte)1),
    LEVEL2((byte)2),
    LEVEL3((byte)3),
    LEVEL4((byte)4),
    LEVEL5((byte)5),
    LEVEL6((byte)6),
    LEVEL7((byte)7);

    private final byte level;

    WaterLevel(byte level) {
        this.level = level;
    }

    public byte getLevel() {
        return level;
    }

    public static byte[] getAllLevels() {
        return new byte[] {
                NONE.level,
                LEVEL1.level,
                LEVEL2.level,
                LEVEL3.level,
                LEVEL4.level,
                LEVEL5.level,
                LEVEL6.level,
                LEVEL7.level
        };
    }
}

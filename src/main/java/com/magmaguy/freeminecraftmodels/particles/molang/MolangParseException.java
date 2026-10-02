package com.magmaguy.freeminecraftmodels.particles.molang;

public class MolangParseException extends IllegalArgumentException {
    private final int offset;

    public MolangParseException(String message, String source, int offset) {
        super(message + " at offset " + offset + " in '" + source + "'");
        this.offset = offset;
    }

    public int getOffset() {
        return offset;
    }
}

package com.bobby.bobbypipes.block;

import net.minecraft.util.StringRepresentable;

/**
 * How one face of a pipe is joined, and whether that arm should show a routing mark.
 *
 * <p>Marks follow the Logistics Pipes convention and are painted only on
 * {@link RoutedPipeBlock}s: green ({@link #DIRECT}) for a routed exit toward another
 * router across an unbranched corridor, red ({@link #INDIRECT}) for every other
 * pipe-facing arm. Inventory arms stay unmarked. Plain transport pipes use
 * {@link #INDIRECT} for pipe faces but never draw marked arm textures.
 */
public enum PipeConnection implements StringRepresentable {

    /** No neighbour on this face; draw a cap. */
    NONE("none"),

    /** Arm toward an inventory; plain texture, no routing mark. */
    INVENTORY("inventory"),

    /** Arm on a direct smart-to-smart corridor; green mark. */
    DIRECT("direct"),

    /** Arm toward a pipe that is not on a direct corridor; red mark. */
    INDIRECT("indirect");

    private final String id;

    PipeConnection(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    /** Whether this face draws an arm (anything except a cap). */
    public boolean isConnected() {
        return this != NONE;
    }

    /** Whether this face faces another pipe (marked or soon-to-be-marked). */
    public boolean isPipe() {
        return this == DIRECT || this == INDIRECT;
    }
}

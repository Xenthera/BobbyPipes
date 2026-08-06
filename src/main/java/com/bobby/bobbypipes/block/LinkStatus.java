package com.bobby.bobbypipes.block;

import net.minecraft.util.StringRepresentable;

/**
 * Visual link-channel state painted onto the link-pipe body (composited overlay).
 *
 * <p>Independent of arm connection marks: those still use {@link PipeConnection}. This
 * only answers whether the wormhole claim is idle, waiting, live, or severed by unload.
 */
public enum LinkStatus implements StringRepresentable {

    /** No channel claimed. */
    IDLE("idle"),

    /** Channel claimed, peer slot still empty. */
    WAITING("waiting"),

    /** Pair complete but peer chunk unloaded - claim held, edge severed. */
    SEVERED("severed"),

    /** Pair complete and both ends loaded - virtual edge live. */
    LIVE("live");

    private final String id;

    LinkStatus(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }
}

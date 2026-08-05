package com.bobby.bobbypipes.pipes;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * How much of an attached inventory a provider keeps back when offering stock.
 *
 * <p>First/last stack modes refer to the first and last <em>occupied</em> slots in scan
 * order within each attached inventory. Applied per inventory when a provider touches
 * several chests. Leave-one-per-stack is also per stack inside each inventory.
 */
public enum ProviderLeaveMode implements StringRepresentable {

    NORMAL("normal"),
    LEAVE_FIRST("leave_first"),
    LEAVE_LAST("leave_last"),
    LEAVE_FIRST_AND_LAST("leave_first_and_last"),
    LEAVE_ONE_PER_STACK("leave_one_per_stack");

    public static final Codec<ProviderLeaveMode> CODEC =
            StringRepresentable.fromEnum(ProviderLeaveMode::values);

    public static final StreamCodec<RegistryFriendlyByteBuf, ProviderLeaveMode> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, mode) -> buffer.writeUtf(mode.getSerializedName()),
                    buffer -> byId(buffer.readUtf()));

    private final String id;

    ProviderLeaveMode(String id) {
        this.id = id;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public ProviderLeaveMode next() {
        ProviderLeaveMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public boolean skipsFirstOccupied() {
        return this == LEAVE_FIRST || this == LEAVE_FIRST_AND_LAST;
    }

    public boolean skipsLastOccupied() {
        return this == LEAVE_LAST || this == LEAVE_FIRST_AND_LAST;
    }

    public boolean leavesOnePerStack() {
        return this == LEAVE_ONE_PER_STACK;
    }

    /**
     * Whether {@code slot} may be taken from, given the first/last occupied slot indices
     * in that inventory ({@code -1} when the inventory has no occupied slots).
     */
    public boolean allowsOccupiedSlot(int slot, int firstOccupied, int lastOccupied) {
        if (skipsFirstOccupied() && firstOccupied >= 0 && slot == firstOccupied) {
            return false;
        }
        if (skipsLastOccupied() && lastOccupied >= 0 && slot == lastOccupied) {
            return false;
        }
        return true;
    }

    public static ProviderLeaveMode byId(String id) {
        for (ProviderLeaveMode mode : values()) {
            if (mode.id.equals(id)) {
                return mode;
            }
        }
        return NORMAL;
    }
}

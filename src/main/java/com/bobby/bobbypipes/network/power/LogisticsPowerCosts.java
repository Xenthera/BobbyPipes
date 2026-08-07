package com.bobby.bobbypipes.network.power;

import net.minecraft.core.BlockPos;

/**
 * Tunable FE costs for logistics actions. Idle networks cost nothing; only spends fire.
 */
public final class LogisticsPowerCosts {

    public static final int CAPACITY = 200_000;
    /** Visual / brownout probe: component is powered when a junction can spare this much. */
    public static final int MIN_CHECK = 1;

    public static final int PROVIDER = 5;
    public static final int REQUEST = 5;
    public static final int SUPPLIER = 8;
    public static final int PASSIVE_SUPPLIER = 4;
    public static final int CRAFTING = 25;
    public static final int SATELLITE = 3;
    public static final int ENERGY_PROVIDER = 6;
    public static final int ENERGY_REQUEST = 6;
    public static final int ENERGY_SUPPLIER = 10;
    public static final int FLUID_PROVIDER = 6;
    public static final int FLUID_REQUEST = 6;
    public static final int FLUID_SUPPLIER = 10;

    public static final int LINK_FE_PER_BLOCK = 1;
    public static final int LINK_SAME_DIM_CAP = 64;
    public static final int LINK_INTERDIM = 256;

    public static final int SAMPLE_PERIOD_TICKS = 20;
    public static final int SAMPLE_HISTORY = 60;

    private LogisticsPowerCosts() {
    }

    public static int costOf(PowerSpendKind kind) {
        return switch (kind) {
            case PROVIDER -> PROVIDER;
            case REQUEST -> REQUEST;
            case SUPPLIER -> SUPPLIER;
            case PASSIVE_SUPPLIER -> PASSIVE_SUPPLIER;
            case CRAFTING -> CRAFTING;
            case SATELLITE -> SATELLITE;
            case ENERGY_PROVIDER -> ENERGY_PROVIDER;
            case ENERGY_REQUEST -> ENERGY_REQUEST;
            case ENERGY_SUPPLIER -> ENERGY_SUPPLIER;
            case FLUID_PROVIDER -> FLUID_PROVIDER;
            case FLUID_REQUEST -> FLUID_REQUEST;
            case FLUID_SUPPLIER -> FLUID_SUPPLIER;
            case LINK_SAME, LINK_INTERDIM -> 0; // computed separately
        };
    }

    /** Same-dimension live link hop cost from Manhattan distance, capped. */
    public static int linkSameDimCost(BlockPos a, BlockPos b) {
        int dist = Math.abs(a.getX() - b.getX())
                + Math.abs(a.getY() - b.getY())
                + Math.abs(a.getZ() - b.getZ());
        return Math.min(LINK_SAME_DIM_CAP, Math.max(1, dist * LINK_FE_PER_BLOCK));
    }

    public static int linkInterdimCost() {
        return LINK_INTERDIM;
    }
}

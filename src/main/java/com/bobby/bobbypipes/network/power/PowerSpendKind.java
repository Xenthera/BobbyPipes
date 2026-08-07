package com.bobby.bobbypipes.network.power;

/**
 * Logistics FE spend categories. Used both for cost lookup and the junction usage graph.
 */
public enum PowerSpendKind {
    PROVIDER,
    REQUEST,
    SUPPLIER,
    PASSIVE_SUPPLIER,
    CRAFTING,
    SATELLITE,
    ENERGY_PROVIDER,
    ENERGY_REQUEST,
    ENERGY_SUPPLIER,
    FLUID_PROVIDER,
    FLUID_REQUEST,
    FLUID_SUPPLIER,
    LINK_SAME,
    LINK_INTERDIM;

    public static final PowerSpendKind[] VALUES = values();
}

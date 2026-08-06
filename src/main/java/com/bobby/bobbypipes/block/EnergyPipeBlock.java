package com.bobby.bobbypipes.block;

/**
 * Shared base for the routed pipes that move FE.
 *
 * <p>Exists so the energy medium is declared once instead of on each of Provider, Request,
 * and Supplier. An energy pipe grows an arm toward energy storages only, never toward a
 * chest it has nothing to say to.
 */
public abstract class EnergyPipeBlock extends RoutedPipeBlock {

    protected EnergyPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public PipeMedium medium() {
        return PipeMedium.ENERGY;
    }
}

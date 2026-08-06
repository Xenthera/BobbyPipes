package com.bobby.bobbypipes.block;

/**
 * Shared base for the routed pipes that move fluid.
 *
 * <p>Same reasoning as {@link EnergyPipeBlock}: the medium is declared once, and a fluid
 * pipe only attaches to neighbours that expose a fluid handler.
 */
public abstract class FluidPipeBlock extends RoutedPipeBlock {

    protected FluidPipeBlock(Properties properties) {
        super(properties);
    }

    @Override
    public PipeMedium medium() {
        return PipeMedium.FLUID;
    }
}

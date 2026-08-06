package com.bobby.bobbypipes.block;

/**
 * A routed pipe that offers the fluid tank touching it to the network.
 *
 * <p>Passive, like {@link EnergyProviderPipeBlock}. Advertises whatever fluid is currently
 * in the attached tank and never initiates a transfer itself, something else (a Request or
 * Supplier pipe) pulls from it. No block entity needed for the same reason: there is no
 * per-pipe configuration to hold.
 */
public class FluidProviderPipeBlock extends FluidPipeBlock {

    public FluidProviderPipeBlock(Properties properties) {
        super(properties);
    }
}

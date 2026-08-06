package com.bobby.bobbypipes.block;

/**
 * A routed pipe that offers the energy storage touching it to the network.
 *
 * <p>Passive, like the item Provider. No filter and no leave mode, unlike the item
 * version, those exist for sorting between multiple item types, and there is only one
 * kind of energy. It advertises whatever FE is currently in the attached storage and
 * never initiates a transfer itself, something else (a Request or Supplier pipe) pulls
 * from it.
 *
 * <p>Needs no block entity: unlike the item Provider there is no per-pipe configuration to
 * hold, the network reads the attached energy storage directly through the capability on
 * whatever block sits next to this pipe.
 */
public class EnergyProviderPipeBlock extends EnergyPipeBlock {

    public EnergyProviderPipeBlock(Properties properties) {
        super(properties);
    }
}

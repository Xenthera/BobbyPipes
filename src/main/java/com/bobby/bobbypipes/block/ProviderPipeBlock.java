package com.bobby.bobbypipes.block;

/**
 * A pipe that offers the inventories touching it to the network.
 *
 * <p>Being a provider is opt-in. A plain pipe running past a chest does not quietly start
 * handing out its contents, which is both the expected behaviour and what stops a player's
 * personal storage being drained by a network that merely passes nearby.
 */
public class ProviderPipeBlock extends PipeBlock {

    public ProviderPipeBlock(Properties properties) {
        super(properties);
    }
}

package com.bobby.bobbypipes.block;

/**
 * A routed pipe that offers the inventories touching it to the network.
 *
 * <p>Being a provider is opt-in. A plain pipe running past a chest does not quietly start
 * handing out its contents, which is both the expected behaviour and what stops a player's
 * personal storage being drained by a network that merely passes nearby.
 *
 * <p>Basic tier send rate: {@link #ITEMS_PER_PULSE} items every
 * {@link #PULSE_INTERVAL_TICKS} ticks (a quarter-second at 20 tps). Each pulse is one
 * parcel  -  large requests drip out over time rather than leaving as a single giant stack.
 */
public class ProviderPipeBlock extends RoutedPipeBlock {

    /** Items one basic provider may extract per send pulse. */
    public static final int ITEMS_PER_PULSE = 8;

    /** Ticks between send pulses (5 ticks = 1/4 second at 20 tps). */
    public static final int PULSE_INTERVAL_TICKS = 5;

    public ProviderPipeBlock(Properties properties) {
        super(properties);
    }
}

package com.bobby.bobbypipes.logistics;

/**
 * Shared extract rate for every pipe that pulls items from an adjacent inventory.
 *
 * <p>Matches the basic provider pulse: {@link #ITEMS_PER_PULSE} items every
 * {@link #PULSE_INTERVAL_TICKS} ticks (a quarter-second at 20 tps).
 */
public final class PipeExtractRates {

    /** Items one pipe may extract per send pulse. */
    public static final int ITEMS_PER_PULSE = 8;

    /** Ticks between extract pulses. */
    public static final int PULSE_INTERVAL_TICKS = 5;

    private PipeExtractRates() {
    }
}

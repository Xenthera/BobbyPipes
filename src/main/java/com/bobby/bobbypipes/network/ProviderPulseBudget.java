package com.bobby.bobbypipes.network;

import java.util.HashMap;
import java.util.Map;

/**
 * Per-provider send allowance: {@code itemsPerPulse} items every {@code intervalTicks}.
 *
 * <p>Loader-agnostic so the refill math can be unit-tested without a world.
 *
 * @param <N> provider identity
 */
public final class ProviderPulseBudget<N> {

    private final int itemsPerPulse;
    private final int intervalTicks;
    private final Map<N, Long> nextPulseTick = new HashMap<>();
    private final Map<N, Integer> pulseRemaining = new HashMap<>();

    public ProviderPulseBudget(int itemsPerPulse, int intervalTicks) {
        if (itemsPerPulse < 1) {
            throw new IllegalArgumentException("itemsPerPulse must be at least 1, got " + itemsPerPulse);
        }
        if (intervalTicks < 1) {
            throw new IllegalArgumentException("intervalTicks must be at least 1, got " + intervalTicks);
        }
        this.itemsPerPulse = itemsPerPulse;
        this.intervalTicks = intervalTicks;
    }

    /** Items {@code source} may still send during the current pulse window. */
    public int budget(N source, long gameTime) {
        Long next = nextPulseTick.get(source);
        if (next == null || gameTime >= next) {
            nextPulseTick.put(source, gameTime + intervalTicks);
            pulseRemaining.put(source, itemsPerPulse);
            return itemsPerPulse;
        }
        return Math.max(0, pulseRemaining.getOrDefault(source, 0));
    }

    public void consume(N source, int amount) {
        if (amount <= 0) {
            return;
        }
        pulseRemaining.merge(source, -amount, Integer::sum);
    }
}

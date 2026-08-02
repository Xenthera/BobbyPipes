package com.bobby.bobbypipes.request;

/**
 * A quantity of something that is wanted.
 *
 * @param item   item identity, opaque to the planner
 * @param amount how many, always positive
 * @param <I>    item identity type
 */
public record Demand<I>(I item, int amount) {

    public Demand {
        if (amount <= 0) {
            throw new IllegalArgumentException("demand amount must be positive, got " + amount);
        }
    }

    public Demand<I> scaled(int factor) {
        return new Demand<>(item, amount * factor);
    }

    public Demand<I> plus(int extra) {
        return new Demand<>(item, amount + extra);
    }
}

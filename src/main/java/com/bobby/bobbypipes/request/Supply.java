package com.bobby.bobbypipes.request;

import java.util.List;

/**
 * What the network can offer the planner. Implemented by the world-facing layer, which
 * reads real inventories and recipes; kept as an interface here so planning can be
 * exercised against hand-written fixtures.
 *
 * @param <N> node identity
 * @param <I> item identity
 */
public interface Supply<N, I> {

    /**
     * Stock of {@code item} that providers are willing to hand over, cheapest first.
     *
     * <p>Ordering matters and is the caller's responsibility: the planner takes from the
     * front of this list, so putting the nearest provider first is what makes deliveries
     * travel the shortest distance. The routing layer's
     * {@code RouteTable.destinationsByCost} is the intended source of that ordering.
     */
    List<Stock<N, I>> available(I item);

    /**
     * Ways to make {@code item} that the network knows about.
     *
     * <p>Empty for anything that cannot be crafted, which is the normal case and the
     * reason planning terminates.
     */
    List<Craft<N, I>> recipesFor(I item);

    /**
     * Stock sitting in one provider.
     *
     * @param source the node holding it
     * @param amount how many are available, always positive
     */
    record Stock<N, I>(N source, I item, int amount) {
        public Stock {
            if (amount <= 0) {
                throw new IllegalArgumentException("stock amount must be positive, got " + amount);
            }
        }
    }

    /**
     * A recipe the network can run.
     *
     * @param crafter the node that would run it
     * @param output  what one run produces
     * @param inputs  what one run consumes
     * @param backlog runs this crafter is already committed to from earlier requests. The
     *                planner hands work to the least loaded crafter first, the way Logistics
     *                Pipes orders its crafters by outstanding to-do rather than splitting
     *                evenly and ignoring who is already busy.
     */
    record Craft<N, I>(N crafter, Demand<I> output, List<Demand<I>> inputs, int backlog) {
        public Craft {
            inputs = List.copyOf(inputs);
            backlog = Math.max(0, backlog);
        }

        /** An idle crafter, for fixtures and for callers with no workload to report. */
        public Craft(N crafter, Demand<I> output, List<Demand<I>> inputs) {
            this(crafter, output, inputs, 0);
        }
    }
}

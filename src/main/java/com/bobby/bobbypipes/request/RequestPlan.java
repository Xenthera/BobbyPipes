package com.bobby.bobbypipes.request;

import java.util.List;

/**
 * The resolved answer to a request: what to pull, what to craft, and what is missing.
 *
 * <p>A plan is produced without touching the world. Nothing is reserved and nothing moves
 * until the caller decides to commit it, which is what makes it safe to plan a request
 * purely to show the player whether it would succeed.
 *
 * @param withdrawals what to take from which provider, in the order it should be taken
 * @param crafts      recipes to run, ordered so a step's inputs are satisfied by earlier
 *                    steps and withdrawals
 * @param missing     what could not be sourced at all, empty on a satisfiable plan
 * @param <N>         node identity
 * @param <I>         item identity
 */
public record RequestPlan<N, I>(
        List<Withdrawal<N, I>> withdrawals,
        List<CraftStep<N, I>> crafts,
        List<Demand<I>> missing) {

    public RequestPlan {
        withdrawals = List.copyOf(withdrawals);
        crafts = List.copyOf(crafts);
        missing = List.copyOf(missing);
    }

    /** Take {@code amount} of {@code item} from {@code source}. */
    public record Withdrawal<N, I>(N source, I item, int amount) {
    }

    /**
     * Where a planned quantity comes from.
     *
     * <p>This is the binding that makes a plan executable. Without it the executor has to
     * ask the network again at run time, which fails for chains: an intermediate is not in
     * anyone's inventory yet, so a lookup finds nothing and the downstream step stalls.
     */
    public sealed interface Origin<N> {

        /** Stock already sitting in a provider's inventory. */
        record Stock<N>(N provider) implements Origin<N> {
        }

        /**
         * Output of another craft step in this same plan.
         *
         * <p>The executor must wait for that step rather than trying to source the item,
         * and the upstream crafter knows exactly where to send what it makes.
         */
        record Craft<N>(N crafter) implements Origin<N> {
        }
    }

    /** A quantity of an item together with where it is coming from. */
    public record Sourced<N, I>(I item, int amount, Origin<N> origin) {

        public Sourced {
            if (amount <= 0) {
                throw new IllegalArgumentException("sourced amount must be positive, got " + amount);
            }
        }

        public boolean fromCraft() {
            return origin instanceof Origin.Craft<N>;
        }
    }

    /**
     * Run a recipe on its crafter {@code runs} times.
     *
     * @param outputPerRun how many {@code output} items one successful run produces
     *                     (e.g. 4 for oak log -> planks)
     */
    public record CraftStep<N, I>(
            N crafter, I output, int outputPerRun, int runs, List<Sourced<N, I>> inputs) {
        public CraftStep {
            if (outputPerRun <= 0) {
                throw new IllegalArgumentException("outputPerRun must be positive, got " + outputPerRun);
            }
            inputs = List.copyOf(inputs);
        }

        /** Total items this step would produce if every run succeeds. */
        public int totalOutput() {
            return outputPerRun * runs;
        }

        /** Inputs aggregated per item, losing the origin. Convenience for display. */
        public List<Demand<I>> inputsConsumed() {
            java.util.LinkedHashMap<I, Integer> totals = new java.util.LinkedHashMap<>();
            for (Sourced<N, I> input : inputs) {
                totals.merge(input.item(), input.amount(), Integer::sum);
            }
            return totals.entrySet().stream()
                    .map(e -> new Demand<>(e.getKey(), e.getValue()))
                    .toList();
        }

        /** Crafters whose output this step consumes, so it cannot start before they run. */
        public java.util.List<N> dependsOn() {
            return inputs.stream()
                    .map(Sourced::origin)
                    .filter(o -> o instanceof Origin.Craft<N>)
                    .map(o -> ((Origin.Craft<N>) o).crafter())
                    .distinct()
                    .toList();
        }
    }

    /** True when nothing is missing, so committing this plan would fully satisfy the request. */
    public boolean isComplete() {
        return missing.isEmpty();
    }

    /** True when the plan would move or make nothing at all. */
    public boolean isEmpty() {
        return withdrawals.isEmpty() && crafts.isEmpty();
    }

    /**
     * Total of {@code item} pulled from provider stock anywhere in the plan, whether it
     * goes to the requester or feeds a craft step.
     *
     * <p>{@link #withdrawnTotal} counts only what heads straight for the requester, so the
     * two differ as soon as crafting is involved.
     */
    public int stockPulledTotal(I item) {
        int total = withdrawnTotal(item);
        for (CraftStep<N, I> step : crafts) {
            for (Sourced<N, I> input : step.inputs()) {
                if (input.item().equals(item)
                        && input.origin() instanceof Origin.Stock<N>) {
                    total += input.amount();
                }
            }
        }
        return total;
    }

    /** Total count of {@code item} the withdrawals would pull from providers. */
    public int withdrawnTotal(I item) {
        return withdrawals.stream()
                .filter(withdrawal -> withdrawal.item().equals(item))
                .mapToInt(Withdrawal::amount)
                .sum();
    }
}

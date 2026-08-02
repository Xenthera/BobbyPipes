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

    /** Run {@code recipe} on its crafter {@code runs} times. */
    public record CraftStep<N, I>(N crafter, I output, int runs, List<Demand<I>> inputsConsumed) {
        public CraftStep {
            inputsConsumed = List.copyOf(inputsConsumed);
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

    /** Total count of {@code item} the withdrawals would pull from providers. */
    public int withdrawnTotal(I item) {
        return withdrawals.stream()
                .filter(withdrawal -> withdrawal.item().equals(item))
                .mapToInt(Withdrawal::amount)
                .sum();
    }
}

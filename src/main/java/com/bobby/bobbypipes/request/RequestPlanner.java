package com.bobby.bobbypipes.request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves a demand into a {@link RequestPlan}: pull what exists, craft what does not.
 *
 * <p>Planning is pure. It reads a {@link Supply} and produces a plan without reserving
 * anything or touching the world, so a plan can be built purely to tell the player
 * whether their request would succeed.
 *
 * <p>Providers are drained before recipes are considered, and providers are taken in the
 * order {@link Supply#available} returns them, so ordering that list by routing cost is
 * what makes a request pull from the nearest chest rather than an arbitrary one.
 */
public final class RequestPlanner {

    /** How deep a crafting chain may go before the planner gives up. */
    public static final int DEFAULT_MAX_DEPTH = 16;

    private RequestPlanner() {
    }

    public static <N, I> RequestPlan<N, I> plan(Demand<I> demand, Supply<N, I> supply) {
        return plan(demand, supply, DEFAULT_MAX_DEPTH);
    }

    public static <N, I> RequestPlan<N, I> plan(Demand<I> demand, Supply<N, I> supply, int maxDepth) {
        Session<N, I> session = new Session<>(supply, maxDepth);
        int satisfied = session.resolve(demand.item(), demand.amount(), 0);
        int shortfall = demand.amount() - satisfied;

        List<Demand<I>> missing = shortfall > 0
                ? List.of(new Demand<>(demand.item(), shortfall))
                : List.of();

        return new RequestPlan<>(session.withdrawals, session.crafts, missing);
    }

    /** Identifies one provider's holding of one item, so repeat visits do not double-count. */
    private record StockKey<N, I>(N source, I item) {
    }

    private static final class Session<N, I> {

        private final Supply<N, I> supply;
        private final int maxDepth;
        private final List<RequestPlan.Withdrawal<N, I>> withdrawals = new ArrayList<>();
        private final List<RequestPlan.CraftStep<N, I>> crafts = new ArrayList<>();
        /** How much has already been committed from each provider during this plan. */
        private final Map<StockKey<N, I>, Integer> claimed = new HashMap<>();
        /** Items currently being resolved further up the stack, used to break cycles. */
        private final Deque<I> resolving = new ArrayDeque<>();

        Session(Supply<N, I> supply, int maxDepth) {
            this.supply = supply;
            this.maxDepth = maxDepth;
        }

        /**
         * Sources up to {@code amount} of {@code item}.
         *
         * @return how much it managed to source, which may be less than asked for
         */
        int resolve(I item, int amount, int depth) {
            int outstanding = amount - takeFromProviders(item, amount);
            if (outstanding == 0) {
                return amount;
            }
            if (depth >= maxDepth) {
                return amount - outstanding;
            }
            // A recipe that needs the item it produces, directly or through a chain,
            // would otherwise recurse forever.
            if (resolving.contains(item)) {
                return amount - outstanding;
            }

            resolving.push(item);
            try {
                for (Supply.Craft<N, I> craft : supply.recipesFor(item)) {
                    if (outstanding == 0) {
                        break;
                    }
                    outstanding -= craftUpTo(craft, outstanding, depth);
                }
            } finally {
                resolving.pop();
            }
            return amount - outstanding;
        }

        /** Claims stock from providers in the order the supply offered them. */
        private int takeFromProviders(I item, int wanted) {
            int taken = 0;
            for (Supply.Stock<N, I> stock : supply.available(item)) {
                if (taken == wanted) {
                    break;
                }
                StockKey<N, I> key = new StockKey<>(stock.source(), item);
                int alreadyClaimed = claimed.getOrDefault(key, 0);
                int free = stock.amount() - alreadyClaimed;
                if (free <= 0) {
                    continue;
                }
                int take = Math.min(free, wanted - taken);
                claimed.put(key, alreadyClaimed + take);
                withdrawals.add(new RequestPlan.Withdrawal<>(stock.source(), item, take));
                taken += take;
            }
            return taken;
        }

        /**
         * Runs {@code craft} as many times as its inputs allow, up to what would cover
         * {@code outstanding} output.
         *
         * @return how much output the committed runs produce
         */
        private int craftUpTo(Supply.Craft<N, I> craft, int outstanding, int depth) {
            int perRun = craft.output().amount();
            int runsWanted = ceilDiv(outstanding, perRun);
            if (runsWanted <= 0) {
                return 0;
            }

            // Feasibility is monotonic: if n runs can be sourced then so can n-1. Binary
            // search finds the largest workable run count without walking every value,
            // which matters when a request asks for thousands of something.
            int best = 0;
            int low = 1;
            int high = runsWanted;
            while (low <= high) {
                int mid = (low + high) >>> 1;
                Checkpoint<N, I> checkpoint = snapshot();
                boolean ok = tryRuns(craft, mid, depth);
                restore(checkpoint);
                if (ok) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            if (best == 0) {
                return 0;
            }

            // Re-run the winning count for real; the probing runs above were all undone.
            final int runs = best;
            tryRuns(craft, runs, depth);
            List<Demand<I>> consumed = craft.inputs().stream()
                    .map(input -> input.scaled(runs))
                    .toList();
            crafts.add(new RequestPlan.CraftStep<>(
                    craft.crafter(), craft.output().item(), runs, consumed));
            return Math.min(outstanding, runs * perRun);
        }

        /** Attempts to source every input for {@code runs} runs. Leaves claims in place. */
        private boolean tryRuns(Supply.Craft<N, I> craft, int runs, int depth) {
            for (Demand<I> input : craft.inputs()) {
                int needed = input.amount() * runs;
                if (resolve(input.item(), needed, depth + 1) < needed) {
                    return false;
                }
            }
            return true;
        }

        private Checkpoint<N, I> snapshot() {
            return new Checkpoint<>(withdrawals.size(), crafts.size(), new HashMap<>(claimed));
        }

        private void restore(Checkpoint<N, I> checkpoint) {
            trimTo(withdrawals, checkpoint.withdrawals());
            trimTo(crafts, checkpoint.crafts());
            claimed.clear();
            claimed.putAll(checkpoint.claimed());
        }

        private static void trimTo(List<?> list, int size) {
            while (list.size() > size) {
                list.remove(list.size() - 1);
            }
        }

        private static int ceilDiv(int value, int divisor) {
            return (value + divisor - 1) / divisor;
        }
    }

    private record Checkpoint<N, I>(int withdrawals, int crafts, Map<StockKey<N, I>, Integer> claimed) {
    }
}

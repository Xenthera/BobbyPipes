package com.bobby.bobbypipes.request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
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
        List<RequestPlan.Sourced<N, I>> rootSources = new ArrayList<>();
        int satisfied = session.resolve(demand.item(), demand.amount(), 0, rootSources);
        int shortfall = demand.amount() - satisfied;

        // Report the raw materials that actually ran out, not the thing that could not be
        // built. "Missing 1 shulker box" tells you nothing you did not already know;
        // "missing 3 oak logs" tells you what to go and get.
        List<Demand<I>> missing = List.of();
        if (shortfall > 0) {
            Map<I, Integer> leaves = new java.util.LinkedHashMap<>();
            session.explainShortfall(demand.item(), shortfall, 0, leaves);
            if (leaves.isEmpty()) {
                missing = List.of(new Demand<>(demand.item(), shortfall));
            } else {
                List<Demand<I>> built = new ArrayList<>(leaves.size());
                leaves.forEach((item, amount) -> built.add(new Demand<>(item, amount)));
                missing = built;
            }
        }

        // Only stock headed straight for the requester is a withdrawal. Stock feeding a
        // craft is bound inside that craft step so the executor knows where it must land.
        List<RequestPlan.Withdrawal<N, I>> withdrawals = new ArrayList<>();
        for (RequestPlan.Sourced<N, I> sourced : rootSources) {
            if (sourced.origin() instanceof RequestPlan.Origin.Stock<N> stock) {
                withdrawals.add(new RequestPlan.Withdrawal<>(
                        stock.provider(), sourced.item(), sourced.amount()));
            }
        }
        return new RequestPlan<>(withdrawals, session.crafts, missing);
    }

    /** Identifies one provider's holding of one item, so repeat visits do not double-count. */
    private record StockKey<N, I>(N source, I item) {
    }

    /** Overproduction from one craft step, available to satisfy other demands. */
    private record SurplusKey<N, I>(N crafter, I item) {
    }

    private static final class Session<N, I> {

        private final Supply<N, I> supply;
        private final int maxDepth;
        private final List<RequestPlan.CraftStep<N, I>> crafts = new ArrayList<>();
        /** How much has already been committed from each provider during this plan. */
        private final Map<StockKey<N, I>, Integer> claimed = new HashMap<>();
        /** Items currently being resolved further up the stack, used to break cycles. */
        private final Deque<I> resolving = new ArrayDeque<>();
        /**
         * Output a craft step will make beyond what its own demand needed.
         *
         * <p>A recipe that yields four when three are wanted leaves one over. Without
         * somewhere to record it, the next demand for that item starts a whole second
         * craft and the spare is routed away as waste.
         */
        private final Map<SurplusKey<N, I>, Integer> surplus = new java.util.LinkedHashMap<>();

        Session(Supply<N, I> supply, int maxDepth) {
            this.supply = supply;
            this.maxDepth = maxDepth;
        }

        /**
         * Sources up to {@code amount} of {@code item}, recording where each part comes
         * from into {@code out}.
         *
         * <p>The bindings are the point. A caller can tell stock pulls apart from output
         * of another craft step, which is what lets the executor wait for an upstream
         * crafter instead of hunting for an intermediate that does not exist yet.
         *
         * @return how much it managed to source, which may be less than asked for
         */
        int resolve(I item, int amount, int depth, List<RequestPlan.Sourced<N, I>> out) {
            int outstanding = amount - takeFromProviders(item, amount, out);
            if (outstanding == 0) {
                return amount;
            }
            // Spend a sibling's overproduction before starting a new craft. Stock first,
            // then surplus, then crafting.
            outstanding -= takeFromSurplus(item, outstanding, out);
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
                // Least loaded first. Logistics Pipes sorts its crafters by outstanding
                // to-do before handing out work, so a crafter that is already backed up from
                // an earlier request is not handed the same share as an idle one. Splitting
                // evenly regardless of load piles work onto a busy crafter and leaves an idle
                // one waiting, which then shows up as a request that never quite finishes.
                List<Supply.Craft<N, I>> recipes = new ArrayList<>(supply.recipesFor(item));
                recipes.sort(Comparator.comparingInt(Supply.Craft::backlog));

                // Then spread the work rather than dumping it all on the first crafter. Each
                // is offered an even share of what is left; a crafter that cannot take its
                // full share leaves more outstanding, so the next one is offered a bigger
                // share automatically.
                for (int i = 0; i < recipes.size() && outstanding > 0; i++) {
                    Supply.Craft<N, I> craft = recipes.get(i);
                    int share;
                    if (i == recipes.size() - 1) {
                        share = outstanding;
                    } else {
                        // Whole runs only. Handing each crafter a rounded up share made
                        // every one of them round up independently, so two crafters split
                        // 12 planks into four runs of four and overproduced by a third.
                        int perRun = Math.max(1, craft.output().amount());
                        int even = ceilDiv(outstanding, recipes.size() - i);
                        share = Math.min(outstanding, Math.max(perRun, (even / perRun) * perRun));
                    }
                    outstanding -= craftUpTo(craft, share, depth, out);
                }

                // Anything still short after an even split means some crafters were capped
                // by their inputs. Let whoever has capacity left pick up the remainder.
                for (Supply.Craft<N, I> craft : recipes) {
                    if (outstanding == 0) {
                        break;
                    }
                    outstanding -= craftUpTo(craft, outstanding, depth, out);
                }
            } finally {
                resolving.pop();
            }
            return amount - outstanding;
        }

        /** Claims stock from providers in the order the supply offered them. */
        private int takeFromProviders(I item, int wanted, List<RequestPlan.Sourced<N, I>> out) {
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
                out.add(new RequestPlan.Sourced<>(
                        item, take, new RequestPlan.Origin.Stock<>(stock.source())));
                taken += take;
            }
            return taken;
        }

        /**
         * Claims output another craft step is already going to overproduce.
         *
         * <p>The binding names the crafter that will make it, so the executor treats it
         * exactly like any other craft-sourced input: the producer is told it owes these
         * items and delivers them, rather than routing them away as excess.
         */
        private int takeFromSurplus(I item, int wanted, List<RequestPlan.Sourced<N, I>> out) {
            int taken = 0;
            for (Map.Entry<SurplusKey<N, I>, Integer> spare : surplus.entrySet()) {
                if (taken == wanted) {
                    break;
                }
                if (!spare.getKey().item().equals(item) || spare.getValue() <= 0) {
                    continue;
                }
                int take = Math.min(spare.getValue(), wanted - taken);
                spare.setValue(spare.getValue() - take);
                out.add(new RequestPlan.Sourced<>(
                        item, take, new RequestPlan.Origin.Craft<>(spare.getKey().crafter())));
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
        private int craftUpTo(Supply.Craft<N, I> craft,
                              int outstanding,
                              int depth,
                              List<RequestPlan.Sourced<N, I>> out) {
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
                boolean ok = tryRuns(craft, mid, depth, new ArrayList<>());
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
            List<RequestPlan.Sourced<N, I>> inputs = new ArrayList<>();
            tryRuns(craft, runs, depth, inputs);
            crafts.add(new RequestPlan.CraftStep<>(
                    craft.crafter(), craft.output().item(), perRun, runs, inputs));

            int made = runs * perRun;
            int produced = Math.min(outstanding, made);
            if (made > produced) {
                // Whole runs are indivisible, so the remainder is banked rather than
                // wasted. A later demand for the same item spends it before crafting.
                surplus.merge(new SurplusKey<>(craft.crafter(), craft.output().item()),
                        made - produced, Integer::sum);
            }
            out.add(new RequestPlan.Sourced<>(
                    craft.output().item(), produced,
                    new RequestPlan.Origin.Craft<>(craft.crafter())));
            return produced;
        }

        /**
         * Attempts to source every input for {@code runs} runs, collecting the bindings.
         *
         * <p>Claims stay in place on success. On failure the caller restores a checkpoint,
         * which is what stops a half-sourced recipe leaving stock reserved for a craft that
         * will never run.
         */
        private boolean tryRuns(Supply.Craft<N, I> craft,
                                int runs,
                                int depth,
                                List<RequestPlan.Sourced<N, I>> collected) {
            for (Demand<I> input : craft.inputs()) {
                int needed = input.amount() * runs;
                List<RequestPlan.Sourced<N, I>> got = new ArrayList<>();
                if (resolve(input.item(), needed, depth + 1, got) < needed) {
                    return false;
                }
                collected.addAll(got);
            }
            return true;
        }

        /**
         * Walks the unsatisfiable part of the request down to raw materials.
         *
         * <p>Runs after planning, against whatever stock the plan did not already claim,
         * so it never blames an item the plan successfully reserved. Deliberately a
         * separate pass: doing it during resolve would mean recording shortfalls inside
         * the run-count binary search, where a rolled back probe would leave phantom
         * entries behind.
         */
        void explainShortfall(I item, int amount, int depth, Map<I, Integer> out) {
            if (amount <= 0) {
                return;
            }
            int remaining = amount - Math.min(freeStock(item), amount);
            if (remaining <= 0) {
                return;
            }
            List<Supply.Craft<N, I>> recipes = supply.recipesFor(item);
            // Nothing can make it, it recurses, or the chain is too deep: this is as raw
            // as the explanation gets.
            if (recipes.isEmpty() || depth >= maxDepth || resolving.contains(item)) {
                out.merge(item, remaining, Integer::sum);
                return;
            }
            Supply.Craft<N, I> craft = recipes.getFirst();
            int perRun = Math.max(1, craft.output().amount());
            int runs = ceilDiv(remaining, perRun);
            resolving.push(item);
            try {
                for (Demand<I> input : craft.inputs()) {
                    explainShortfall(input.item(), input.amount() * runs, depth + 1, out);
                }
            } finally {
                resolving.pop();
            }
        }

        /** Stock of {@code item} the plan has not already reserved. */
        private int freeStock(I item) {
            int free = 0;
            for (Supply.Stock<N, I> stock : supply.available(item)) {
                free += Math.max(0,
                        stock.amount() - claimed.getOrDefault(new StockKey<>(stock.source(), item), 0));
            }
            return free;
        }

        private Checkpoint<N, I> snapshot() {
            return new Checkpoint<>(crafts.size(), new HashMap<>(claimed), new HashMap<>(surplus));
        }

        private void restore(Checkpoint<N, I> checkpoint) {
            trimTo(crafts, checkpoint.crafts());
            claimed.clear();
            claimed.putAll(checkpoint.claimed());
            surplus.clear();
            surplus.putAll(checkpoint.surplus());
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

    private record Checkpoint<N, I>(int crafts,
                                   Map<StockKey<N, I>, Integer> claimed,
                                   Map<SurplusKey<N, I>, Integer> surplus) {
    }
}

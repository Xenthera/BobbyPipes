package com.bobby.bobbypipes.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the binding a plan records for every input.
 *
 * <p>These are the guarantees the executor relies on. Before bindings existed it re-queried
 * the network at run time, which could not work for a chain: an intermediate is not in any
 * inventory yet, so the lookup found nothing and the downstream step stalled forever.
 */
class PlanBindingTest {

    private static final class FakeSupply implements Supply<String, String> {

        private final Map<String, List<Stock<String, String>>> stock = new HashMap<>();
        private final Map<String, List<Craft<String, String>>> recipes = new HashMap<>();

        FakeSupply stock(String source, String item, int amount) {
            stock.computeIfAbsent(item, k -> new ArrayList<>())
                    .add(new Stock<>(source, item, amount));
            return this;
        }

        @SafeVarargs
        final FakeSupply recipe(String crafter, String output, int out, Demand<String>... inputs) {
            recipes.computeIfAbsent(output, k -> new ArrayList<>())
                    .add(new Craft<>(crafter, new Demand<>(output, out), List.of(inputs)));
            return this;
        }

        @Override
        public List<Stock<String, String>> available(String item) {
            return stock.getOrDefault(item, List.of());
        }

        @Override
        public List<Craft<String, String>> recipesFor(String item) {
            return recipes.getOrDefault(item, List.of());
        }
    }

    private static Demand<String> want(String item, int amount) {
        return new Demand<>(item, amount);
    }

    private static RequestPlan.CraftStep<String, String> step(RequestPlan<String, String> plan,
                                                              String output) {
        return plan.crafts().stream()
                .filter(s -> s.output().equals(output))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no craft step producing " + output));
    }

    @Test
    @DisplayName("a leaf craft binds its inputs to the provider they come from")
    void leafInputsBindToStock() {
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 4), supply);

        RequestPlan.CraftStep<String, String> plankStep = step(plan, "plank");
        assertEquals(1, plankStep.inputs().size());
        RequestPlan.Sourced<String, String> log = plankStep.inputs().getFirst();
        assertEquals("log", log.item());
        assertEquals(1, log.amount());
        assertEquals(new RequestPlan.Origin.Stock<>("chestA"), log.origin());
        assertFalse(log.fromCraft());
    }

    @Test
    @DisplayName("an intermediate binds to the crafter that makes it, not to stock")
    void intermediateBindsToUpstreamCrafter() {
        // The whole reason bindings exist. Planks are not in any chest; they will be made
        // by tableA, and the stick step has to know that rather than going looking.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "stick", 4, want("plank", 2));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("stick", 4), supply);

        RequestPlan.Sourced<String, String> plank = step(plan, "stick").inputs().getFirst();
        assertEquals("plank", plank.item());
        assertTrue(plank.fromCraft(), "plank must be bound to an upstream craft");
        assertEquals(new RequestPlan.Origin.Craft<>("tableA"), plank.origin());
    }

    @Test
    @DisplayName("dependsOn names the upstream crafters so execution can be ordered")
    void dependsOnNamesUpstreamCrafters() {
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "stick", 4, want("plank", 2))
                .recipe("tableC", "ladder", 3, want("stick", 7));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("ladder", 1), supply);

        assertEquals(List.of("tableB"), step(plan, "ladder").dependsOn());
        assertEquals(List.of("tableA"), step(plan, "stick").dependsOn());
        assertEquals(List.of(), step(plan, "plank").dependsOn(),
                "a leaf craft waits on nobody");
    }

    @Test
    @DisplayName("stock feeding a craft is not also listed as a withdrawal")
    void craftInputsAreNotWithdrawals() {
        // Listing them twice is what let the executor double-source: once straight to the
        // requester and once into the crafter buffer.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 4), supply);

        assertTrue(plan.withdrawals().isEmpty(), "nothing goes straight to the requester");
        assertEquals(0, plan.withdrawnTotal("log"));
        assertEquals(1, plan.stockPulledTotal("log"), "but the log is still pulled");
    }

    @Test
    @DisplayName("a mixed request separates the direct pull from the craft input")
    void mixedRequestSeparatesDirectFromCraftInput() {
        // 2 planks on the shelf, 4 wanted, so 2 are direct and the rest is crafted.
        FakeSupply supply = new FakeSupply()
                .stock("shelf", "plank", 2)
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 4), supply);

        assertTrue(plan.isComplete());
        assertEquals(2, plan.withdrawnTotal("plank"), "the shelf stock goes direct");
        assertEquals(1, step(plan, "plank").runs(), "one run covers the remaining 2");
        assertEquals(1, plan.stockPulledTotal("log"));
    }

    @Test
    @DisplayName("every bound amount is positive and sums to what the step consumes")
    void bindingsAreConsistentWithConsumption() {
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 2));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 12), supply);

        RequestPlan.CraftStep<String, String> plankStep = step(plan, "plank");
        int bound = plankStep.inputs().stream()
                .filter(i -> i.item().equals("log"))
                .mapToInt(RequestPlan.Sourced::amount)
                .sum();
        int consumed = plankStep.inputsConsumed().stream()
                .filter(d -> d.item().equals("log"))
                .mapToInt(Demand::amount)
                .sum();
        assertEquals(consumed, bound);
        assertEquals(3 * 2, bound, "3 runs at 2 logs each");
        plankStep.inputs().forEach(i -> assertTrue(i.amount() > 0));
    }

    @Test
    @DisplayName("an ingredient repeated across grid slots is planned as one merged demand")
    void repeatedIngredientPlansAsOneDemand() {
        // A chest is eight planks in eight separate slots. If those reach the planner as
        // eight demands of one, it resolves a single plank at a time: one plank needs
        // ceil(1/4) = one run of the plank recipe, so it orders one log, makes four
        // planks, credits one, and stalls. Merged, it correctly wants two runs.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "chest", 1, want("plank", 8));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("chest", 1), supply);

        assertTrue(plan.isComplete());
        assertEquals(1, plan.crafts().stream()
                .filter(c -> c.output().equals("plank")).count(),
                "one plank step, not one per slot");
        assertEquals(2, step(plan, "plank").runs(), "8 planks at 4 per run needs 2 runs");
        assertEquals(2, plan.stockPulledTotal("log"), "2 runs needs 2 logs, ordered together");
    }

    @Test
    @DisplayName("overproduction is banked and spent by a later demand instead of crafting again")
    void surplusIsReusedAcrossTheTree() {
        // LP's extrapromises. A stick needs 2 planks and a sign needs 1, so 3 planks are
        // wanted in total. One run of the plank recipe makes 4, so the spare must satisfy
        // the sign rather than starting a second run.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "gadget", 1, want("plank", 3));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("gadget", 1), supply);

        assertTrue(plan.isComplete());
        assertEquals(1, step(plan, "plank").runs(), "one run of 4 covers 3 planks");
        assertEquals(1, plan.stockPulledTotal("log"), "no second log for a second run");
    }

    @Test
    @DisplayName("banked surplus is bound to the crafter that will make it")
    void surplusBindsToItsProducer() {
        // The binding has to name the producer, otherwise the executor would go hunting
        // for an item nobody is holding and the surplus would be routed away as excess.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "gadget", 1, want("plank", 5));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("gadget", 1), supply);

        assertTrue(plan.isComplete());
        assertEquals(2, step(plan, "plank").runs(), "5 planks needs 2 runs");
        step(plan, "gadget").inputs().forEach(input -> {
            assertTrue(input.fromCraft(), "planks come from a craft, never from thin air");
            assertEquals(new RequestPlan.Origin.Craft<>("tableA"), input.origin());
        });
        assertEquals(5, step(plan, "gadget").inputs().stream()
                .mapToInt(RequestPlan.Sourced::amount).sum());
    }

    @Test
    @DisplayName("surplus cannot be spent twice")
    void surplusIsNotDoubleSpent() {
        // Only 3 planks are ever spare, so a demand for more must still craft the rest
        // rather than conjuring planks out of the bank.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 2)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "gadget", 1, want("plank", 9));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("gadget", 1), supply);

        // 2 logs is 8 planks, one short of the 9 needed.
        assertFalse(plan.isComplete());
        assertTrue(plan.stockPulledTotal("log") <= 2, "cannot pull logs that do not exist");
    }

    @Test
    @DisplayName("an unbuildable craft reports the raw material it ran out of")
    void shortfallNamesRawMaterials() {
        // Requesting a chest with no logs anywhere should say "8 planks worth of logs",
        // not "1 chest". The top level name is the one thing the player already knows.
        FakeSupply supply = new FakeSupply()
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "chest", 1, want("plank", 8));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("chest", 1), supply);

        assertFalse(plan.isComplete());
        assertEquals(List.of("log"), plan.missing().stream().map(Demand::item).toList());
        assertEquals(2, plan.missing().getFirst().amount(), "8 planks needs 2 logs");
    }

    @Test
    @DisplayName("a partial shortfall only blames what is actually short")
    void shortfallSubtractsStockAlreadyThere() {
        // One log is present, so only the second is missing. Blaming both would send the
        // player hunting for stock that is already on the network.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 1)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "chest", 1, want("plank", 8));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("chest", 1), supply);

        assertFalse(plan.isComplete());
        assertEquals(List.of("log"), plan.missing().stream().map(Demand::item).toList());
        assertEquals(1, plan.missing().getFirst().amount(), "the log on hand is not missing");
    }

    @Test
    @DisplayName("a raw item with no recipe is reported as itself")
    void rawItemReportsItself() {
        RequestPlan<String, String> plan =
                RequestPlanner.plan(want("diamond", 5), new FakeSupply());

        assertEquals(List.of(want("diamond", 5)), plan.missing());
    }

    @Test
    @DisplayName("two crafters with the same recipe share the work evenly")
    void balancesAcrossEqualCrafters() {
        // Previously the nearest crafter absorbed the whole demand and the second sat
        // idle. Logistics Pipes levels load across crafters of equal priority.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 999)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 40), supply);

        assertTrue(plan.isComplete());
        Map<String, Integer> runs = new java.util.HashMap<>();
        plan.crafts().forEach(c -> runs.merge(c.crafter(), c.runs(), Integer::sum));
        assertEquals(5, runs.get("tableA"));
        assertEquals(5, runs.get("tableB"));
    }

    @Test
    @DisplayName("an odd split still covers the full amount")
    void oddSplitStillCoversDemand() {
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 999)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 12), supply);

        assertTrue(plan.isComplete());
        assertEquals(3, plan.crafts().stream().mapToInt(RequestPlan.CraftStep::runs).sum(),
                "12 planks at 4 per run is 3 runs however they are split");
    }

    @Test
    @DisplayName("a crafter starved of inputs hands its share to the other")
    void starvedCrafterYieldsItsShare() {
        // tableB's input is a different item nobody stocks, so it can do nothing and
        // tableA must cover the lot rather than the request falling short.
        FakeSupply supply = new FakeSupply()
                .stock("chestA", "log", 999)
                .recipe("tableA", "plank", 4, want("log", 1))
                .recipe("tableB", "plank", 4, want("unobtainium", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 40), supply);

        assertTrue(plan.isComplete());
        Map<String, Integer> runs = new java.util.HashMap<>();
        plan.crafts().forEach(c -> runs.merge(c.crafter(), c.runs(), Integer::sum));
        assertEquals(10, runs.get("tableA"));
        assertFalse(runs.containsKey("tableB"));
    }

    @Test
    @DisplayName("bindings split across providers when one cannot cover the input")
    void bindingsSplitAcrossProviders() {
        FakeSupply supply = new FakeSupply()
                .stock("near", "log", 1)
                .stock("far", "log", 64)
                .recipe("tableA", "plank", 4, want("log", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("plank", 8), supply);

        List<RequestPlan.Sourced<String, String>> logs = step(plan, "plank").inputs();
        assertEquals(2, logs.size(), "one binding per provider drawn from");
        assertEquals(new RequestPlan.Origin.Stock<>("near"), logs.getFirst().origin());
        assertEquals(new RequestPlan.Origin.Stock<>("far"), logs.get(1).origin());
        assertEquals(2, logs.stream().mapToInt(RequestPlan.Sourced::amount).sum());
    }
}

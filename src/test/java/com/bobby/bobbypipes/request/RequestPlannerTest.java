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

class RequestPlannerTest {

    /** Hand-built supply. Nodes and items are plain strings. */
    private static final class FakeSupply implements Supply<String, String> {

        private final Map<String, List<Stock<String, String>>> stock = new HashMap<>();
        private final Map<String, List<Craft<String, String>>> recipes = new HashMap<>();

        FakeSupply stock(String source, String item, int amount) {
            stock.computeIfAbsent(item, key -> new ArrayList<>())
                    .add(new Stock<>(source, item, amount));
            return this;
        }

        FakeSupply recipe(String crafter, String output, int outputCount, Demand<String>... inputs) {
            recipes.computeIfAbsent(output, key -> new ArrayList<>())
                    .add(new Craft<>(crafter, new Demand<>(output, outputCount), List.of(inputs)));
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

    @Test
    @DisplayName("a request that stock covers needs no crafting")
    void pullsFromStock() {
        FakeSupply supply = new FakeSupply().stock("chest", "iron", 64);

        RequestPlan<String, String> plan = RequestPlanner.plan(want("iron", 10), supply);

        assertTrue(plan.isComplete());
        assertEquals(1, plan.withdrawals().size());
        assertEquals(10, plan.withdrawnTotal("iron"));
        assertTrue(plan.crafts().isEmpty());
    }

    @Test
    @DisplayName("providers are drained in the order supplied, so nearest wins")
    void takesFromProvidersInOrder() {
        // The routing layer sorts by cost; the planner must respect that order rather
        // than picking whichever provider has the most.
        FakeSupply supply = new FakeSupply()
                .stock("near", "iron", 4)
                .stock("far", "iron", 64);

        RequestPlan<String, String> plan = RequestPlanner.plan(want("iron", 10), supply);

        assertEquals("near", plan.withdrawals().getFirst().source());
        assertEquals(4, plan.withdrawals().getFirst().amount());
        assertEquals("far", plan.withdrawals().get(1).source());
        assertEquals(6, plan.withdrawals().get(1).amount());
    }

    @Test
    @DisplayName("what cannot be sourced is reported as missing, not silently dropped")
    void reportsShortfall() {
        FakeSupply supply = new FakeSupply().stock("chest", "iron", 3);

        RequestPlan<String, String> plan = RequestPlanner.plan(want("iron", 10), supply);

        assertFalse(plan.isComplete());
        assertEquals(List.of(want("iron", 7)), plan.missing());
        assertEquals(3, plan.withdrawnTotal("iron"), "the partial pull is still planned");
    }

    @Test
    @DisplayName("a missing item is crafted from its inputs")
    void craftsWhenStockIsShort() {
        FakeSupply supply = new FakeSupply()
                .stock("chest", "ingot", 9)
                .recipe("crafter", "block", 1, want("ingot", 9));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("block", 1), supply);

        assertTrue(plan.isComplete());
        assertEquals(1, plan.crafts().size());
        assertEquals("crafter", plan.crafts().getFirst().crafter());
        assertEquals(1, plan.crafts().getFirst().runs());
        assertEquals(9, plan.withdrawnTotal("ingot"));
    }

    @Test
    @DisplayName("crafting recurses through a multi step chain")
    void resolvesNestedCrafting() {
        // ore -> ingot -> block, with only ore in stock.
        FakeSupply supply = new FakeSupply()
                .stock("chest", "ore", 100)
                .recipe("smelter", "ingot", 1, want("ore", 1))
                .recipe("crafter", "block", 1, want("ingot", 9));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("block", 2), supply);

        assertTrue(plan.isComplete());
        assertEquals(18, plan.withdrawnTotal("ore"));
        assertEquals(2, plan.crafts().size());
        assertEquals(18, findRuns(plan, "ingot"));
        assertEquals(2, findRuns(plan, "block"));
    }

    @Test
    @DisplayName("run count rounds up when output does not divide the request evenly")
    void roundsUpPartialRuns() {
        // Each run makes 4. Asking for 10 needs 3 runs, which makes 12.
        FakeSupply supply = new FakeSupply()
                .stock("chest", "plank", 64)
                .recipe("crafter", "stick", 4, want("plank", 2));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("stick", 10), supply);

        assertTrue(plan.isComplete());
        assertEquals(3, findRuns(plan, "stick"));
        assertEquals(6, plan.withdrawnTotal("plank"));
    }

    @Test
    @DisplayName("crafting falls back to as many runs as the inputs allow")
    void partiallyCraftsWhenInputsRunOut() {
        // Enough plank for 3 runs, request would need 5.
        FakeSupply supply = new FakeSupply()
                .stock("chest", "plank", 6)
                .recipe("crafter", "stick", 4, want("plank", 2));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("stick", 20), supply);

        assertFalse(plan.isComplete());
        assertEquals(3, findRuns(plan, "stick"));
        assertEquals(6, plan.withdrawnTotal("plank"));
        assertEquals(List.of(want("stick", 8)), plan.missing());
    }

    @Test
    @DisplayName("a recipe whose inputs are entirely absent is not planned at all")
    void skipsUnsatisfiableRecipe() {
        // Committing a craft that can never run would strand the crafter waiting forever.
        FakeSupply supply = new FakeSupply()
                .recipe("crafter", "block", 1, want("ingot", 9));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("block", 1), supply);

        assertFalse(plan.isComplete());
        assertTrue(plan.crafts().isEmpty());
        assertTrue(plan.withdrawals().isEmpty(), "no partial claims left behind");
        assertTrue(plan.isEmpty());
    }

    @Test
    @DisplayName("the same stock is never promised to two branches of one plan")
    void doesNotDoubleCountStock() {
        // Both inputs come from the same 10 iron. Only one run is actually possible.
        FakeSupply supply = new FakeSupply()
                .stock("chest", "iron", 10)
                .recipe("crafter", "gadget", 1, want("iron", 6), want("iron", 6));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("gadget", 1), supply);

        assertFalse(plan.isComplete());
        assertTrue(plan.crafts().isEmpty());
        assertTrue(plan.withdrawnTotal("iron") <= 10, "cannot claim more iron than exists");
    }

    @Test
    @DisplayName("a recipe cycle terminates instead of recursing forever")
    void handlesRecipeCycles() {
        // block -> ingot -> block. Without a cycle guard this never returns.
        FakeSupply supply = new FakeSupply()
                .recipe("crafterA", "block", 1, want("ingot", 9))
                .recipe("crafterB", "ingot", 9, want("block", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("block", 1), supply);

        assertFalse(plan.isComplete());
        assertEquals(List.of(want("block", 1)), plan.missing());
    }

    @Test
    @DisplayName("a cycle still resolves when real stock breaks it")
    void cycleResolvesWhenStockExists() {
        FakeSupply supply = new FakeSupply()
                .stock("chest", "ingot", 9)
                .recipe("crafterA", "block", 1, want("ingot", 9))
                .recipe("crafterB", "ingot", 9, want("block", 1));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("block", 1), supply);

        assertTrue(plan.isComplete());
        assertEquals(9, plan.withdrawnTotal("ingot"));
    }

    @Test
    @DisplayName("depth limit stops a very long chain")
    void respectsDepthLimit() {
        // A chain of tier0 -> tier1 -> ... each needing the one below, with no stock.
        FakeSupply supply = new FakeSupply();
        for (int i = 0; i < 30; i++) {
            supply.recipe("crafter" + i, "tier" + i, 1, want("tier" + (i + 1), 1));
        }

        RequestPlan<String, String> plan = RequestPlanner.plan(want("tier0", 1), supply, 5);

        assertFalse(plan.isComplete());
        assertTrue(plan.crafts().isEmpty());
    }

    @Test
    @DisplayName("stock is preferred over crafting even when a recipe exists")
    void prefersStockOverCrafting() {
        FakeSupply supply = new FakeSupply()
                .stock("chest", "stick", 20)
                .stock("chest2", "plank", 64)
                .recipe("crafter", "stick", 4, want("plank", 2));

        RequestPlan<String, String> plan = RequestPlanner.plan(want("stick", 10), supply);

        assertTrue(plan.isComplete());
        assertTrue(plan.crafts().isEmpty(), "no reason to craft what is already on a shelf");
        assertEquals(10, plan.withdrawnTotal("stick"));
    }

    private static int findRuns(RequestPlan<String, String> plan, String output) {
        return plan.crafts().stream()
                .filter(step -> step.output().equals(output))
                .mapToInt(RequestPlan.CraftStep::runs)
                .sum();
    }
}

package com.bobby.bobbypipes.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExtractPulseBudgetTest {

    @Test
    @DisplayName("a fresh pipe gets a full pulse immediately")
    void fullPulseOnFirstAsk() {
        ExtractPulseBudget<String> budget = new ExtractPulseBudget<>(8, 5);

        assertEquals(8, budget.budget("a", 100));
    }

    @Test
    @DisplayName("consume drains the current pulse; no refill until the interval elapses")
    void drainsUntilInterval() {
        ExtractPulseBudget<String> budget = new ExtractPulseBudget<>(8, 5);

        assertEquals(8, budget.budget("a", 0));
        budget.consume("a", 8);
        assertEquals(0, budget.budget("a", 0));
        assertEquals(0, budget.budget("a", 4));
        assertEquals(8, budget.budget("a", 5));
    }

    @Test
    @DisplayName("partial consume leaves the rest of the pulse available")
    void partialConsume() {
        ExtractPulseBudget<String> budget = new ExtractPulseBudget<>(8, 5);

        budget.budget("a", 0);
        budget.consume("a", 3);

        assertEquals(5, budget.budget("a", 0));
    }

    @Test
    @DisplayName("each pipe has its own pulse window")
    void independentSources() {
        ExtractPulseBudget<String> budget = new ExtractPulseBudget<>(8, 5);

        budget.budget("a", 0);
        budget.consume("a", 8);

        assertEquals(0, budget.budget("a", 0));
        assertEquals(8, budget.budget("b", 0));
    }

    @Test
    @DisplayName("invalid construction is rejected")
    void rejectsNonPositive() {
        assertThrows(IllegalArgumentException.class, () -> new ExtractPulseBudget<>(0, 5));
        assertThrows(IllegalArgumentException.class, () -> new ExtractPulseBudget<>(8, 0));
    }
}

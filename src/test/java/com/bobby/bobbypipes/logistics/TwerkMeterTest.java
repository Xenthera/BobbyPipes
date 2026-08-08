package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.logistics.power.TwerkMeter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TwerkMeterTest {

    private static final int FE = 50;
    private static final int COOLDOWN = 2;

    private static TwerkMeter<String> meter() {
        return new TwerkMeter<>(FE, COOLDOWN);
    }

    @Test
    @DisplayName("the first crouch after a baseline pays, and pays at all")
    void firstTransitionPays() {
        TwerkMeter<String> meter = meter();
        // Regression: a Long.MIN_VALUE "never awarded" sentinel overflowed the cooldown
        // subtraction, so this returned 0 forever and the generator never produced anything.
        assertEquals(0, meter.observe("a", false, 100), "first sighting only sets a baseline");
        assertEquals(FE, meter.observe("a", true, 101), "crouching down is work");
    }

    @Test
    @DisplayName("holding crouch earns nothing; you have to keep moving")
    void holdingEarnsNothing() {
        TwerkMeter<String> meter = meter();
        meter.observe("a", false, 0);
        assertEquals(FE, meter.observe("a", true, 10));
        assertEquals(0, meter.observe("a", true, 11));
        assertEquals(0, meter.observe("a", true, 50));
        assertEquals(FE, meter.observe("a", false, 60), "standing back up is the other half");
    }

    @Test
    @DisplayName("a participant cannot beat the cooldown by toggling faster")
    void cooldownCapsOneParticipant() {
        TwerkMeter<String> meter = meter();
        meter.observe("a", false, 0);
        assertEquals(FE, meter.observe("a", true, 10));
        // Toggling on the very next tick is inside the cooldown.
        assertEquals(0, meter.observe("a", false, 11));
        // ...and once it has elapsed, work counts again.
        assertEquals(FE, meter.observe("a", true, 12));
    }

    @Test
    @DisplayName("two players earn twice as much as one, and neither blocks the other")
    void multiplePlayersScaleProportionally() {
        TwerkMeter<String> meter = meter();
        meter.observe("a", false, 0);
        meter.observe("b", false, 0);

        // Same tick, both transition: the cooldown is per participant, not per generator.
        int tickTotal = meter.observe("a", true, 10) + meter.observe("b", true, 10);
        assertEquals(FE * 2, tickTotal, "two dancers, twice the power");

        int nextTotal = meter.observe("a", false, 12) + meter.observe("b", false, 12)
                + meter.observe("c", true, 12);
        assertEquals(FE * 2, nextTotal, "a newcomer is baselined, not paid, on arrival");
    }

    @Test
    @DisplayName("someone who leaves is re-baselined rather than paid on return")
    void leavingResetsBaseline() {
        TwerkMeter<String> meter = meter();
        meter.observe("a", false, 0);
        assertEquals(FE, meter.observe("a", true, 10));

        meter.retainOnly(Set.of());
        assertEquals(0, meter.tracked());

        // Returning mid-crouch must not pay for a change that happened out of range.
        assertEquals(0, meter.observe("a", false, 100), "back to a baseline");
        assertEquals(FE, meter.observe("a", true, 102));
    }

    @Test
    @DisplayName("a large game time does not break the cooldown arithmetic")
    void survivesLargeGameTimes() {
        TwerkMeter<String> meter = meter();
        long late = 1_000_000_000L;
        assertEquals(0, meter.observe("a", false, late));
        assertEquals(FE, meter.observe("a", true, late + 1));
    }
}

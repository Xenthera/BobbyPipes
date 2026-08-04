package com.bobby.bobbypipes.transit.drift;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriftExitTest {

    private static DriftExit.Option<String> opt(String side, DriftExit.Kind kind) {
        return new DriftExit.Option<>(side, kind);
    }

    @Test
    @DisplayName("a corridor with one way onward always takes it")
    void singleExitIsTaken() {
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.DUMB_PIPE),
                opt("south", DriftExit.Kind.NONE),
                opt("east", DriftExit.Kind.NONE));

        for (float roll : new float[]{0.0f, 0.5f, 0.99f}) {
            assertEquals("north", DriftExit.choose(options, "west", roll).side());
        }
    }

    @Test
    @DisplayName("a junction can reach every branch")
    void junctionReachesEveryBranch() {
        // Random means random: over the roll range each branch must be selectable, or
        // items would silently favour one side of a split.
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.DUMB_PIPE),
                opt("east", DriftExit.Kind.DUMB_PIPE),
                opt("south", DriftExit.Kind.INVENTORY));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(DriftExit.choose(options, "west", i / 100.0f).side());
        }
        assertEquals(Set.of("north", "east", "south"), seen);
    }

    @Test
    @DisplayName("an inventory is a normal choice, not a special case")
    void inventoryCompetesWithPipes() {
        // Picking the side with a chest is how a drifting item ends up stored, so it must
        // be in the draw rather than only used when nothing else is left.
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.INVENTORY),
                opt("east", DriftExit.Kind.DUMB_PIPE));

        Set<DriftExit.Kind> kinds = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            kinds.add(DriftExit.choose(options, "west", i / 50.0f).kind());
        }
        assertEquals(Set.of(DriftExit.Kind.INVENTORY, DriftExit.Kind.DUMB_PIPE), kinds);
    }

    @Test
    @DisplayName("an item never turns round while a forward exit exists")
    void neverBacktracksWithAForwardExit() {
        List<DriftExit.Option<String>> options = List.of(
                opt("west", DriftExit.Kind.DUMB_PIPE),
                opt("north", DriftExit.Kind.DUMB_PIPE));

        for (int i = 0; i < 50; i++) {
            assertEquals("north", DriftExit.choose(options, "west", i / 50.0f).side(),
                    "the side it arrived from is not a candidate");
        }
    }

    @Test
    @DisplayName("a pipe whose only neighbour is where the item came from is a dead end")
    void deadEndEjectsRatherThanReversing() {
        // The end of a run. Turning round would send the item back through pipe it has
        // already failed to find an exit from, so it is put down here instead.
        List<DriftExit.Option<String>> options = List.of(
                opt("west", DriftExit.Kind.DUMB_PIPE),
                opt("north", DriftExit.Kind.NONE),
                opt("south", DriftExit.Kind.NONE));

        assertNull(DriftExit.choose(options, "west", 0.5f),
                "no onward side means eject, not reverse");
    }

    @Test
    @DisplayName("a dead end still uses an inventory if one is there")
    void deadEndPrefersAnInventoryOverEjecting() {
        // Eject means "put it in the container if there is one", and that container is a
        // normal forward option, so it is chosen before the run is considered dead.
        List<DriftExit.Option<String>> options = List.of(
                opt("west", DriftExit.Kind.DUMB_PIPE),
                opt("north", DriftExit.Kind.INVENTORY));

        assertEquals("north", DriftExit.choose(options, "west", 0.5f).side());
    }

    @Test
    @DisplayName("a sealed pipe has nowhere to go at all")
    void sealedPipeHasNoExit() {
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.NONE),
                opt("south", DriftExit.Kind.NONE));

        assertNull(DriftExit.choose(options, "west", 0.5f),
                "no exit means the caller must put the item down");
    }

    @Test
    @DisplayName("a freshly inserted item may use any side")
    void freshItemHasNoBacktrack() {
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.DUMB_PIPE),
                opt("south", DriftExit.Kind.DUMB_PIPE));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            seen.add(DriftExit.choose(options, null, i / 50.0f).side());
        }
        assertEquals(Set.of("north", "south"), seen);
    }

    private static DriftExit.Option<String> routed(String side, DriftExit.Kind kind) {
        return new DriftExit.Option<>(side, kind, true);
    }

    @Test
    @DisplayName("a routed side is taken over a plain one")
    void routedBeatsUnrouted() {
        // A pipe that is on the network sends the item along it. Gambling on plain pipe
        // when a route is right there is how items wandered off a working network.
        List<DriftExit.Option<String>> options = List.of(
                opt("north", DriftExit.Kind.DUMB_PIPE),
                routed("east", DriftExit.Kind.SMART_PIPE),
                opt("south", DriftExit.Kind.DUMB_PIPE));

        for (int i = 0; i < 50; i++) {
            assertEquals("east", DriftExit.choose(options, "west", i / 50.0f).side());
        }
    }

    @Test
    @DisplayName("several routed sides are still chosen between at random")
    void routedSidesStillBranch() {
        // Preferring routed narrows the field, it does not make the choice deterministic.
        List<DriftExit.Option<String>> options = List.of(
                routed("north", DriftExit.Kind.SMART_PIPE),
                routed("east", DriftExit.Kind.SMART_PIPE),
                opt("south", DriftExit.Kind.DUMB_PIPE));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(DriftExit.choose(options, "west", i / 100.0f).side());
        }
        assertEquals(Set.of("north", "east"), seen);
    }

    @Test
    @DisplayName("an inventory still competes with a routed side")
    void inventorySurvivesTheRoutedPreference() {
        // Only unrouted pipe drops out. A container is somewhere to put the item down,
        // not a worse guess at a route, so it stays in the draw.
        List<DriftExit.Option<String>> options = List.of(
                routed("north", DriftExit.Kind.SMART_PIPE),
                opt("east", DriftExit.Kind.INVENTORY),
                opt("south", DriftExit.Kind.DUMB_PIPE));

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(DriftExit.choose(options, "west", i / 100.0f).side());
        }
        assertEquals(Set.of("north", "east"), seen);
    }

    @Test
    @DisplayName("the side an item came from does not count as a routed option")
    void routedBacktrackIsNotAPreference() {
        // The arm it arrived through is usually routed too. If that counted, the item would
        // be pushed back the way it came and every other side would be filtered out.
        List<DriftExit.Option<String>> options = List.of(
                routed("west", DriftExit.Kind.SMART_PIPE),
                opt("north", DriftExit.Kind.DUMB_PIPE));

        for (int i = 0; i < 50; i++) {
            assertEquals("north", DriftExit.choose(options, "west", i / 50.0f).side());
        }
    }

    @Test
    @DisplayName("a roll at the very top of the range stays in bounds")
    void rollOfOneIsSafe() {
        assertEquals(2, DriftExit.index(3, 1.0f));
        assertEquals(0, DriftExit.index(1, 0.999f));
        assertEquals(0, DriftExit.index(0, 0.5f));
    }
}

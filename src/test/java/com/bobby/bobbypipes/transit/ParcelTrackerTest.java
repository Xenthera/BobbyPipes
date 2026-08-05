package com.bobby.bobbypipes.transit;

import com.bobby.bobbypipes.network.RoutingSnapshot;
import com.bobby.bobbypipes.network.Topology;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParcelTrackerTest {

    private static RoutingSnapshot<String> line(long revision, String... nodes) {
        Topology.Builder<String> builder = Topology.builder();
        for (int i = 0; i < nodes.length - 1; i++) {
            builder.link(nodes[i], nodes[i + 1]);
        }
        return RoutingSnapshot.of(builder.build(), revision);
    }

    /** Ticks until something is delivered or stranded, or the budget runs out. */
    private static ParcelTracker.TickReport<String, String> runUntilSettled(
            ParcelTracker<String, String> tracker, RoutingSnapshot<String> routes, int budget) {
        for (int i = 0; i < budget; i++) {
            ParcelTracker.TickReport<String, String> report = tracker.tick(routes);
            if (!report.isQuiet()) {
                return report;
            }
        }
        throw new AssertionError("nothing settled within " + budget + " ticks");
    }

    @Test
    @DisplayName("a hop cannot be instantaneous")
    void ticksPerHopMustBePositive() {
        assertThrows(IllegalArgumentException.class, () -> new ParcelTracker<>(0));
        assertThrows(IllegalArgumentException.class, () -> new ParcelTracker<>(-1));
    }

    @Test
    @DisplayName("injecting toward an unreachable destination is refused up front")
    void refusesUnreachableDestination() {
        // Refusing here rather than mid-flight is what lets the caller keep the items.
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);
        RoutingSnapshot<String> routes = RoutingSnapshot.of(
                Topology.<String>builder().link("a", "b").link("y", "z").build(), 1);

        Optional<Long> id = tracker.inject("iron", "a", "z", routes);

        assertTrue(id.isEmpty());
        assertEquals(0, tracker.inFlight());
    }

    @Test
    @DisplayName("a parcel takes ticksPerHop ticks per link and then arrives")
    void travelsAtTheConfiguredRate() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(5);
        RoutingSnapshot<String> routes = line(1, "a", "b", "c");

        tracker.inject("iron", "a", "c", routes);

        // Two hops at five ticks each. Nothing should settle before tick 10.
        for (int tick = 1; tick < 10; tick++) {
            assertTrue(tracker.tick(routes).isQuiet(), "settled early on tick " + tick);
        }
        ParcelTracker.TickReport<String, String> report = tracker.tick(routes);

        assertEquals(1, report.delivered().size());
        assertEquals("iron", report.delivered().getFirst().payload());
        assertEquals("c", report.delivered().getFirst().destination());
        assertEquals(0, tracker.inFlight(), "delivered parcels are removed from the tracker");
    }

    @Test
    @DisplayName("a custom hop length makes specific hops take longer, authoritatively")
    void customHopLengthVariesPerHop() {
        // Double time on the very first hop (leaving "a"), base rate everywhere else.
        ParcelTracker.HopLength<String, String> length = (at, next, origin, destination, payload) ->
                at.equals(origin) ? 10 : 5;
        ParcelTracker<String, String> tracker = new ParcelTracker<>(5, length);
        RoutingSnapshot<String> routes = line(1, "a", "b", "c");
        long id = tracker.inject("iron", "a", "c", routes).orElseThrow();

        assertEquals(10, tracker.parcel(id).orElseThrow().ticksForHop(),
                "the extended hop's length is set the moment it begins, not guessed later");

        // First (extended) hop: not done at tick 9, done by tick 10.
        for (int tick = 1; tick < 10; tick++) {
            assertTrue(tracker.tick(routes).isQuiet(), "first hop settled early on tick " + tick);
        }
        tracker.tick(routes);
        assertEquals("b", tracker.parcel(id).orElseThrow().atNode(),
                "extended first hop finished exactly at its own length, not the base rate");
        assertEquals(5, tracker.parcel(id).orElseThrow().ticksForHop(),
                "an ordinary hop after the extended one reverts to the base rate");

        // Second (base-rate) hop: five more ticks to "c".
        for (int tick = 1; tick < 5; tick++) {
            assertTrue(tracker.tick(routes).isQuiet(), "second hop settled early on tick " + tick);
        }
        ParcelTracker.TickReport<String, String> report = tracker.tick(routes);
        assertEquals(1, report.delivered().size());
        assertEquals("c", report.delivered().getFirst().destination());
    }

    @Test
    @DisplayName("progress reports the fraction of the current hop covered")
    void progressInterpolates() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);
        RoutingSnapshot<String> routes = line(1, "a", "b", "c");
        long id = tracker.inject("iron", "a", "c", routes).orElseThrow();

        tracker.tick(routes);
        tracker.tick(routes);

        Parcel<String, String> parcel = tracker.parcel(id).orElseThrow();
        assertEquals(0.5f, parcel.progress(4), 0.0001f);
    }

    @Test
    @DisplayName("a parcel already at its destination is delivered rather than rejected")
    void injectingToTheCurrentNodeDelivers() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);
        RoutingSnapshot<String> routes = line(1, "a", "b");

        assertTrue(tracker.inject("iron", "a", "a", routes).isPresent());
        ParcelTracker.TickReport<String, String> report = tracker.tick(routes);

        assertEquals(1, report.delivered().size());
    }

    @Test
    @DisplayName("a parcel in flight reroutes when the network is rebuilt under it")
    void reroutesAcrossARebuild() {
        // Start down the short branch, then delete it mid-flight. The parcel should find
        // its way round the long branch instead of vanishing.
        ParcelTracker<String, String> tracker = new ParcelTracker<>(2);
        RoutingSnapshot<String> withShortcut = RoutingSnapshot.of(Topology.<String>builder()
                .link("a", "shortcut")
                .link("shortcut", "goal")
                .link("a", "long1")
                .link("long1", "long2")
                .link("long2", "goal")
                .build(), 1);

        tracker.inject("iron", "a", "goal", withShortcut);
        assertEquals("shortcut", tracker.parcels().iterator().next().nextHop());

        RoutingSnapshot<String> shortcutGone = RoutingSnapshot.of(Topology.<String>builder()
                .link("a", "long1")
                .link("long1", "long2")
                .link("long2", "goal")
                .build(), 2);

        ParcelTracker.TickReport<String, String> report =
                runUntilSettled(tracker, shortcutGone, 100);

        assertEquals(1, report.delivered().size(), "parcel survived the rebuild");
        assertTrue(report.stranded().isEmpty());
    }

    @Test
    @DisplayName("a dumb junction breaking the corridor strands in-flight parcels")
    void junctionBreakStrandsWhenCorridorGone() {
        // P - a - b - R is a corridor; branching at b removes that corridor from transit.
        // Parcels must not keep routing across the dumb junction.
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);
        RoutingSnapshot<String> line = RoutingSnapshot.of(Topology.<String>builder()
                .link("P", "a")
                .link("a", "b")
                .link("b", "R")
                .build(), 1);
        tracker.inject("iron", "P", "R", line);
        tracker.tick(line);
        tracker.tick(line);

        // Same shape as DirectCorridors.transitTopology after the branch: smarts only.
        RoutingSnapshot<String> corridorBroken = RoutingSnapshot.of(Topology.<String>builder()
                .node("P")
                .node("R")
                .build(), 2);

        ParcelTracker.TickReport<String, String> report = tracker.tick(corridorBroken);

        assertEquals(1, report.stranded().size(), "dumb junction must cut the routed network");
        assertTrue(report.delivered().isEmpty());
    }

    @Test
    @DisplayName("breaking an unrelated spur does not eject a parcel whose path is intact")
    void spurBreakDoesNotStrand() {
        // main: a - b - c - goal, spur: b - spur. Parcel a->goal, then spur is removed.
        ParcelTracker<String, String> tracker = new ParcelTracker<>(2);
        RoutingSnapshot<String> withSpur = RoutingSnapshot.of(Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("c", "goal")
                .link("b", "spur")
                .build(), 1);
        tracker.inject("iron", "a", "goal", withSpur);
        tracker.tick(withSpur);

        RoutingSnapshot<String> spurGone = RoutingSnapshot.of(Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("c", "goal")
                .build(), 2);

        ParcelTracker.TickReport<String, String> report =
                runUntilSettled(tracker, spurGone, 100);

        assertEquals(1, report.delivered().size());
        assertTrue(report.stranded().isEmpty(), "unrelated spur break must not eject transfers");
    }

    @Test
    @DisplayName("breaking the only bridge strands a parcel that can no longer reach dest")
    void bridgeBreakStrands() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(2);
        RoutingSnapshot<String> intact = RoutingSnapshot.of(Topology.<String>builder()
                .link("a", "bridge")
                .link("bridge", "goal")
                .build(), 1);
        tracker.inject("iron", "a", "goal", intact);
        tracker.tick(intact);

        // Bridge removed; a and goal remain as disconnected nodes.
        RoutingSnapshot<String> cut = RoutingSnapshot.of(Topology.<String>builder()
                .node("a")
                .node("goal")
                .build(), 2);

        ParcelTracker.TickReport<String, String> report = tracker.tick(cut);

        assertEquals(1, report.stranded().size());
        assertTrue(report.delivered().isEmpty());
    }

    @Test
    @DisplayName("a parcel whose destination disappears is stranded, not silently dropped")
    void strandsWhenDestinationIsGone() {
        // The caller needs to know so it can spit the items into the world rather than
        // deleting a player's request.
        ParcelTracker<String, String> tracker = new ParcelTracker<>(2);
        RoutingSnapshot<String> intact = line(1, "a", "b", "c");
        tracker.inject("iron", "a", "c", intact);

        RoutingSnapshot<String> broken = line(2, "a", "b");
        ParcelTracker.TickReport<String, String> report = runUntilSettled(tracker, broken, 50);

        assertEquals(1, report.stranded().size());
        ParcelTracker.Stranded<String, String> stranded = report.stranded().getFirst();
        assertEquals("iron", stranded.payload());
        assertEquals("c", stranded.destination());
        assertEquals(0, tracker.inFlight(), "stranded parcels are handed off, not retained");
    }

    @Test
    @DisplayName("cancel hands the payload back")
    void cancelReturnsPayload() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);
        RoutingSnapshot<String> routes = line(1, "a", "b", "c");
        long id = tracker.inject("iron", "a", "c", routes).orElseThrow();

        assertEquals(Optional.of("iron"), tracker.cancel(id));
        assertEquals(0, tracker.inFlight());
        assertTrue(tracker.cancel(id).isEmpty(), "cancelling twice is harmless");
    }

    @Test
    @DisplayName("many parcels travel independently")
    void tracksParcelsIndependently() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(2);
        RoutingSnapshot<String> routes = line(1, "a", "b", "c", "d");

        tracker.inject("iron", "a", "b", routes);
        tracker.inject("gold", "a", "d", routes);

        assertEquals(2, tracker.inFlight());

        // The short one lands first and the long one keeps going.
        ParcelTracker.TickReport<String, String> first = runUntilSettled(tracker, routes, 20);
        assertEquals(1, first.delivered().size());
        assertEquals("iron", first.delivered().getFirst().payload());
        assertEquals(1, tracker.inFlight());

        ParcelTracker.TickReport<String, String> second = runUntilSettled(tracker, routes, 20);
        assertEquals("gold", second.delivered().getFirst().payload());
        assertEquals(0, tracker.inFlight());
    }

    @Test
    @DisplayName("ticking an empty tracker is quiet")
    void emptyTrackerIsQuiet() {
        ParcelTracker<String, String> tracker = new ParcelTracker<>(4);

        assertTrue(tracker.tick(line(1, "a", "b")).isQuiet());
        assertFalse(tracker.parcel(999L).isPresent());
    }
}

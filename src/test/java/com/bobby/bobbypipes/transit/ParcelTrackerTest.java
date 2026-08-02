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

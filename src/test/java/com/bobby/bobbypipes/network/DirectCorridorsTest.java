package com.bobby.bobbypipes.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectCorridorsTest {

    @Test
    @DisplayName("adjacent smart pipes form a length-1 corridor")
    void adjacentSmartPipesAreDirect() {
        Topology<String> lattice = Topology.<String>builder().link("P", "R").build();
        Set<DirectCorridors.Edge<String>> exits =
                DirectCorridors.routedExits(lattice, Set.of("P", "R"));

        assertEquals(Set.of(
                new DirectCorridors.Edge<>("P", "R"),
                new DirectCorridors.Edge<>("R", "P")), exits);
    }

    @Test
    @DisplayName("straight line of plain pipes between smarts is a corridor")
    void straightCorridorIsDirect() {
        Topology<String> lattice = Topology.<String>builder()
                .link("P", "a")
                .link("a", "b")
                .link("b", "R")
                .build();
        Set<DirectCorridors.Edge<String>> exits =
                DirectCorridors.routedExits(lattice, Set.of("P", "R"));

        // Only the routed exits are painted - not intermediate plain-pipe edges.
        assertEquals(Set.of(
                new DirectCorridors.Edge<>("P", "a"),
                new DirectCorridors.Edge<>("R", "b")), exits);

        Topology<String> transit = DirectCorridors.transitTopology(lattice, Set.of("P", "R"));
        assertTrue(transit.contains("a"));
        assertTrue(transit.contains("b"));
        assertEquals(1, transit.neighbours("a").get("b"));
        assertTrue(RoutingSnapshot.of(transit, 1).canReach("P", "R"));
    }

    @Test
    @DisplayName("a T-junction of plain pipes breaks the corridor")
    void junctionIsNotDirect() {
        // P - a - b - R
        //         |
        //         c
        Topology<String> lattice = Topology.<String>builder()
                .link("P", "a")
                .link("a", "b")
                .link("b", "R")
                .link("b", "c")
                .build();
        Set<String> smart = Set.of("P", "R");

        assertTrue(DirectCorridors.routedExits(lattice, smart).isEmpty());
        Topology<String> transit = DirectCorridors.transitTopology(lattice, smart);
        assertFalse(RoutingSnapshot.of(transit, 1).canReach("P", "R"));
        assertTrue(transit.contains("P"));
        assertTrue(transit.contains("R"));
    }

    @Test
    @DisplayName("a basic router on a T-junction reconnects the arms")
    void basicOnJunctionReconnects() {
        // P - a - B - R
        //         |
        //         c - S
        Topology<String> lattice = Topology.<String>builder()
                .link("P", "a")
                .link("a", "B")
                .link("B", "R")
                .link("B", "c")
                .link("c", "S")
                .build();
        Set<String> smart = Set.of("P", "B", "R", "S");
        Topology<String> transit = DirectCorridors.transitTopology(lattice, smart);
        RoutingSnapshot<String> routes = RoutingSnapshot.of(transit, 1);

        assertTrue(routes.canReach("P", "R"));
        assertTrue(routes.canReach("P", "S"));
        assertEquals(Set.of(
                new DirectCorridors.Edge<>("P", "a"),
                new DirectCorridors.Edge<>("B", "a"),
                new DirectCorridors.Edge<>("B", "R"),
                new DirectCorridors.Edge<>("R", "B"),
                new DirectCorridors.Edge<>("B", "c"),
                new DirectCorridors.Edge<>("S", "c")),
                DirectCorridors.routedExits(lattice, smart));
    }

    @Test
    @DisplayName("smart pipe at a T-junction can still own a green arm")
    void smartAtJunctionKeepsDirectArm() {
        //   x
        //   |
        // P-+-a-R
        Topology<String> lattice = Topology.<String>builder()
                .link("P", "x")
                .link("P", "a")
                .link("a", "R")
                .build();
        Set<DirectCorridors.Edge<String>> exits =
                DirectCorridors.routedExits(lattice, Set.of("P", "R"));

        assertEquals(Set.of(
                new DirectCorridors.Edge<>("P", "a"),
                new DirectCorridors.Edge<>("R", "a")), exits);
        assertFalse(exits.contains(new DirectCorridors.Edge<>("P", "x")));
    }

    @Test
    @DisplayName("dead-end plain pipe off a smart is not a corridor")
    void deadEndIsNotDirect() {
        Topology<String> lattice = Topology.<String>builder()
                .link("P", "a")
                .build();
        Set<DirectCorridors.Edge<String>> exits =
                DirectCorridors.routedExits(lattice, Set.of("P"));

        assertTrue(exits.isEmpty());
    }

    @Test
    @DisplayName("no smart pipes means an empty transit graph")
    void plainOnlyNetworkHasNoTransit() {
        Topology<String> lattice = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .build();
        Topology<String> transit = DirectCorridors.transitTopology(lattice, Set.of());

        assertEquals(0, transit.size());
    }
}

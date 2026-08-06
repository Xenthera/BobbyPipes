package com.bobby.bobbypipes.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Link pipes insert a cost-1 lattice edge so DirectCorridors treats them like adjacent
 * dumb pipe.
 */
class LinkPipeTopologyTest {

    @Test
    @DisplayName("virtual link edge joins two corridor arms into one transit path")
    void virtualEdgeJoinsCorridors() {
        // SmartA - a - L1   ~link~   L2 - b - SmartB
        Topology<String> lattice = Topology.<String>builder()
                .link("SmartA", "a")
                .link("a", "L1")
                .link("L1", "L2") // virtual wormhole
                .link("L2", "b")
                .link("b", "SmartB")
                .build();
        Set<String> smart = Set.of("SmartA", "SmartB");
        Topology<String> transit = DirectCorridors.transitTopology(lattice, smart);
        assertTrue(RoutingSnapshot.of(transit, 1).canReach("SmartA", "SmartB"));
        assertTrue(transit.neighbours("L1").containsKey("L2"));
    }
}

package com.bobby.bobbypipes.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopologyTest {

    @Test
    @DisplayName("links go both ways")
    void linksAreUndirected() {
        Topology<String> graph = Topology.<String>builder().link("a", "b").build();

        assertTrue(graph.neighbours("a").containsKey("b"));
        assertTrue(graph.neighbours("b").containsKey("a"));
    }

    @Test
    @DisplayName("relinking the same pair keeps the cheaper cost")
    void duplicateLinkKeepsCheaperCost() {
        // A world scan can reach the same pair from either end in an arbitrary order.
        // Whichever order it happens in, the graph must not end up more expensive.
        Topology<String> graph = Topology.<String>builder()
                .link("a", "b", 9)
                .link("b", "a", 2)
                .build();

        assertEquals(2, graph.neighbours("a").get("b"));
        assertEquals(2, graph.neighbours("b").get("a"));
    }

    @Test
    @DisplayName("a node with no links is still part of the graph")
    void isolatedNodeIsRetained() {
        Topology<String> graph = Topology.<String>builder().node("lonely").build();

        assertTrue(graph.contains("lonely"));
        assertTrue(graph.neighbours("lonely").isEmpty());
        assertEquals(Set.of("lonely"), graph.componentOf("lonely"));
    }

    @Test
    @DisplayName("componentOf returns only the reachable side of a split network")
    void componentOfIsScopedToOneNetwork() {
        Topology<String> graph = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("y", "z")
                .build();

        assertEquals(Set.of("a", "b", "c"), graph.componentOf("a"));
        assertEquals(Set.of("y", "z"), graph.componentOf("y"));
    }

    @Test
    @DisplayName("components partitions every node exactly once")
    void componentsPartitionTheGraph() {
        Topology<String> graph = Topology.<String>builder()
                .link("a", "b")
                .link("y", "z")
                .node("island")
                .build();

        Set<Set<String>> components = graph.components();

        assertEquals(3, components.size());
        assertEquals(4 + 1, components.stream().mapToInt(Set::size).sum());
        assertTrue(components.contains(Set.of("island")));
    }

    @Test
    @DisplayName("componentOf an unknown node is empty rather than throwing")
    void unknownNodeHasNoComponent() {
        Topology<String> graph = Topology.<String>builder().link("a", "b").build();

        assertTrue(graph.componentOf("nowhere").isEmpty());
        assertFalse(graph.contains("nowhere"));
    }

    @Test
    @DisplayName("bad links are rejected at build time")
    void invalidLinksAreRejected() {
        Topology.Builder<String> builder = Topology.builder();

        assertThrows(IllegalArgumentException.class, () -> builder.link("a", "a"));
        assertThrows(IllegalArgumentException.class, () -> builder.link("a", "b", 0));
        assertThrows(IllegalArgumentException.class, () -> builder.link("a", "b", -1));
    }

    @Test
    @DisplayName("the built graph does not change when the builder is reused")
    void buildTakesASnapshot() {
        Topology.Builder<String> builder = Topology.<String>builder().link("a", "b");
        Topology<String> first = builder.build();

        builder.link("b", "c");

        assertFalse(first.contains("c"));
        assertEquals(2, first.size());
    }
}

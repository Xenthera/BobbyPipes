package com.bobby.bobbypipes.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteSolverTest {

    @Test
    @DisplayName("a straight line routes toward the far end one hop at a time")
    void straightLine() {
        Topology<String> line = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("c", "d")
                .build();

        RouteTable<String> fromA = RouteSolver.solve(line, "a");

        assertEquals(Optional.of("b"), fromA.nextHop("b"));
        assertEquals(Optional.of("b"), fromA.nextHop("c"));
        assertEquals(Optional.of("b"), fromA.nextHop("d"));
        assertEquals(Optional.of(3), fromA.costTo("d"));
    }

    @Test
    @DisplayName("the first hop points down the cheaper branch, not merely a reachable one")
    void picksCheaperBranch() {
        // Two ways from start to goal. Going via "long1" is three links, via "short1" two.
        Topology<String> forked = Topology.<String>builder()
                .link("start", "long1")
                .link("long1", "long2")
                .link("long2", "goal")
                .link("start", "short1")
                .link("short1", "goal")
                .build();

        RouteTable<String> table = RouteSolver.solve(forked, "start");

        assertEquals(Optional.of("short1"), table.nextHop("goal"));
        assertEquals(Optional.of(2), table.costTo("goal"));
    }

    @Test
    @DisplayName("link cost outweighs hop count")
    void weightedLinksBeatHopCount() {
        // "fast" is two hops but cheap; "slow" is one hop but expensive.
        Topology<String> weighted = Topology.<String>builder()
                .link("start", "slow", 50)
                .link("slow", "goal", 50)
                .link("start", "fast", 1)
                .link("fast", "mid", 1)
                .link("mid", "goal", 1)
                .build();

        RouteTable<String> table = RouteSolver.solve(weighted, "start");

        assertEquals(Optional.of("fast"), table.nextHop("goal"));
        assertEquals(Optional.of(3), table.costTo("goal"));
    }

    @Test
    @DisplayName("nodes in a separate component are unreachable, not merely expensive")
    void disconnectedComponentIsUnreachable() {
        Topology<String> split = Topology.<String>builder()
                .link("a", "b")
                .link("y", "z")
                .build();

        RouteTable<String> fromA = RouteSolver.solve(split, "a");

        assertTrue(fromA.canReach("b"));
        assertFalse(fromA.canReach("z"));
        assertEquals(Optional.empty(), fromA.nextHop("z"));
        assertEquals(Optional.empty(), fromA.costTo("z"));
    }

    @Test
    @DisplayName("a table never routes a node to itself")
    void originIsNotADestination() {
        Topology<String> ring = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("c", "a")
                .build();

        RouteTable<String> table = RouteSolver.solve(ring, "a");

        assertFalse(table.destinations().contains("a"));
        assertEquals(2, table.destinations().size());
    }

    @Test
    @DisplayName("a ring routes each way round to whichever side is nearer")
    void ringPrefersTheNearerDirection() {
        Topology<String> ring = Topology.<String>builder()
                .link("n0", "n1")
                .link("n1", "n2")
                .link("n2", "n3")
                .link("n3", "n4")
                .link("n4", "n5")
                .link("n5", "n0")
                .build();

        RouteTable<String> table = RouteSolver.solve(ring, "n0");

        assertEquals(Optional.of("n1"), table.nextHop("n2"));
        assertEquals(Optional.of("n5"), table.nextHop("n4"));
        assertEquals(Optional.of(3), table.costTo("n3"));
    }

    @Test
    @DisplayName("destinations sort cheapest first so the planner can pull from the nearest source")
    void destinationsOrderedByCost() {
        Topology<String> line = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .link("c", "d")
                .build();

        List<String> order = RouteSolver.solve(line, "a").destinationsByCost();

        assertEquals(List.of("b", "c", "d"), order);
    }

    @Test
    @DisplayName("solving from a node that is not on the graph yields an empty table")
    void unknownOriginIsEmpty() {
        Topology<String> graph = Topology.<String>builder().link("a", "b").build();

        RouteTable<String> table = RouteSolver.solve(graph, "nowhere");

        assertTrue(table.destinations().isEmpty());
    }

    @Test
    @DisplayName("every node gets a table when solving the whole graph")
    void solveAllCoversEveryNode() {
        Topology<String> graph = Topology.<String>builder()
                .link("a", "b")
                .link("b", "c")
                .node("island")
                .build();

        var tables = RouteSolver.solveAll(graph);

        assertEquals(4, tables.size());
        assertTrue(tables.get("island").destinations().isEmpty());
        assertEquals(Optional.of("b"), tables.get("a").nextHop("c"));
    }
}

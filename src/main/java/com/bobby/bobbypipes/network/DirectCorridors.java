package com.bobby.bobbypipes.network;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Finds unbranched corridors between routed (smart) pipes on a pipe lattice.
 *
 * <p>Minecraft-free so the walk can be unit-tested. A corridor is a path whose endpoints
 * are smart and whose every intermediate node has pipe-degree exactly two - the classic
 * Logistics Pipes "direct connection". Those corridors become the edges of the routing
 * graph; plain pipes on a corridor are transit fabric, not routers.
 */
public final class DirectCorridors {

    private DirectCorridors() {
    }

    /**
     * A directed edge on the lattice. Used for painting routed exits on smart pipes.
     */
    public record Edge<N>(N from, N to) {
        public Edge {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
        }
    }

    /**
     * One unbranched smart-to-smart corridor, including both endpoints.
     *
     * @param path nodes from the starting smart pipe to the ending smart pipe
     */
    public record Corridor<N>(List<N> path) {
        public Corridor {
            path = List.copyOf(path);
            if (path.size() < 2) {
                throw new IllegalArgumentException("corridor needs at least two nodes");
            }
        }

        public N from() {
            return path.getFirst();
        }

        public N to() {
            return path.getLast();
        }

        /** Number of hops (pipe links) along the corridor. */
        public int cost() {
            return path.size() - 1;
        }
    }

    /**
     * Every distinct corridor discovered by walking out of each smart pipe.
     *
     * <p>Each undirected corridor is reported twice (once from each end). Callers that
     * build an undirected topology can link either report; linking is idempotent.
     */
    public static <N> List<Corridor<N>> find(Topology<N> lattice, Set<N> smartNodes) {
        List<Corridor<N>> result = new ArrayList<>();
        if (smartNodes.isEmpty()) {
            return result;
        }
        for (N start : smartNodes) {
            if (!lattice.contains(start)) {
                continue;
            }
            for (N first : lattice.neighbours(start).keySet()) {
                List<N> path = walkCorridor(lattice, smartNodes, start, first);
                if (path != null) {
                    result.add(new Corridor<>(path));
                }
            }
        }
        return result;
    }

    /**
     * Directed exits from smart pipes that begin a successful corridor.
     *
     * <p>These are the green "routed exits" on logistics pipes. Intermediate plain-pipe
     * edges are intentionally omitted - dumb pipes do not show routing marks.
     */
    public static <N> Set<Edge<N>> routedExits(Topology<N> lattice, Set<N> smartNodes) {
        Set<Edge<N>> result = new HashSet<>();
        for (Corridor<N> corridor : find(lattice, smartNodes)) {
            result.add(new Edge<>(corridor.from(), corridor.path().get(1)));
        }
        return result;
    }

    /**
     * Every directed lattice edge that lies on at least one corridor (including
     * intermediate plain-pipe steps). Prefer {@link #routedExits} for arm painting.
     */
    public static <N> Set<Edge<N>> directEdges(Topology<N> lattice, Set<N> smartNodes) {
        Set<Edge<N>> result = new HashSet<>();
        for (Corridor<N> corridor : find(lattice, smartNodes)) {
            List<N> path = corridor.path();
            for (int i = 0; i < path.size() - 1; i++) {
                N a = path.get(i);
                N b = path.get(i + 1);
                result.add(new Edge<>(a, b));
                result.add(new Edge<>(b, a));
            }
        }
        return result;
    }

    /**
     * Corridor-only graph used for routing: every node on a successful smart-to-smart
     * corridor (including intermediate plain pipes) plus isolated smart nodes.
     *
     * <p>A plain-pipe junction is absent here even when the lattice still connects both
     * ends - that is intentional. Those arms are separate networks until a smart pipe
     * sits on the junction.
     */
    public static <N> Topology<N> transitTopology(Topology<N> lattice, Set<N> smartNodes) {
        Topology.Builder<N> builder = Topology.builder();
        for (N smart : smartNodes) {
            if (lattice.contains(smart)) {
                builder.node(smart);
            }
        }
        for (Corridor<N> corridor : find(lattice, smartNodes)) {
            List<N> path = corridor.path();
            for (int i = 0; i < path.size() - 1; i++) {
                builder.link(path.get(i), path.get(i + 1), 1);
            }
        }
        return builder.build();
    }

    /**
     * Walks from {@code start} through {@code first} along degree-2 nodes until another
     * smart pipe is reached. Returns the full path including both endpoints, or null if
     * the walk hits a junction, dead end, or cycle before finding a smart pipe.
     */
    static <N> List<N> walkCorridor(Topology<N> lattice, Set<N> smartNodes,
                                    N start, N first) {
        List<N> path = new ArrayList<>();
        path.add(start);
        N prev = start;
        N current = first;
        int limit = lattice.size() + 1;
        while (path.size() <= limit) {
            path.add(current);
            if (smartNodes.contains(current)) {
                return path;
            }
            Map<N, Integer> neighbours = lattice.neighbours(current);
            if (neighbours.size() != 2) {
                return null;
            }
            N next = null;
            for (N neighbour : neighbours.keySet()) {
                if (!neighbour.equals(prev)) {
                    next = neighbour;
                    break;
                }
            }
            if (next == null) {
                return null;
            }
            prev = current;
            current = next;
        }
        return null;
    }
}

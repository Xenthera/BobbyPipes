package com.bobby.bobbypipes.logistics;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Turns a {@link Topology} into precomputed {@link RouteTable}s.
 *
 * <p>Dijkstra from each origin. This is the expensive operation in the whole routing
 * layer and it runs only when the shape of the network changes, never on a tick.
 */
public final class RouteSolver {

    private RouteSolver() {
    }

    /** Solves route tables for every node in the graph. */
    public static <N> Map<N, RouteTable<N>> solveAll(Topology<N> topology) {
        Map<N, RouteTable<N>> tables = new HashMap<>(topology.size());
        for (N origin : topology.nodes()) {
            tables.put(origin, solve(topology, origin));
        }
        return Map.copyOf(tables);
    }

    /** Solves the route table for a single origin. */
    public static <N> RouteTable<N> solve(Topology<N> topology, N origin) {
        if (!topology.contains(origin)) {
            return new RouteTable<>(origin, Map.of());
        }

        // Cheapest known cost from the origin to each node.
        Map<N, Integer> cost = new HashMap<>();
        // The neighbour of the origin that each node's cheapest path starts with. This is
        // what makes the table useful for forwarding: the answer is always a single hop.
        Map<N, N> firstHop = new HashMap<>();
        Set<N> settled = new HashSet<>();

        record Pending<N>(N node, int cost) {
        }
        PriorityQueue<Pending<N>> queue =
                new PriorityQueue<>((a, b) -> Integer.compare(a.cost(), b.cost()));

        cost.put(origin, 0);
        queue.add(new Pending<>(origin, 0));

        while (!queue.isEmpty()) {
            Pending<N> current = queue.poll();
            if (!settled.add(current.node())) {
                // Already settled via a cheaper path; this is a stale queue entry.
                continue;
            }
            int currentCost = current.cost();

            for (Map.Entry<N, Integer> edge : topology.neighbours(current.node()).entrySet()) {
                N neighbour = edge.getKey();
                if (settled.contains(neighbour)) {
                    continue;
                }
                int candidate = currentCost + edge.getValue();
                Integer known = cost.get(neighbour);
                if (known != null && known <= candidate) {
                    continue;
                }
                cost.put(neighbour, candidate);
                // Inherit the origin's first hop, except one step out from the origin
                // where the neighbour is itself the first hop.
                firstHop.put(neighbour,
                        current.node().equals(origin) ? neighbour : firstHop.get(current.node()));
                queue.add(new Pending<>(neighbour, candidate));
            }
        }

        Map<N, RouteTable.Step<N>> steps = new HashMap<>();
        cost.forEach((node, total) -> {
            if (node.equals(origin)) {
                return;
            }
            steps.put(node, new RouteTable.Step<>(firstHop.get(node), total));
        });
        return new RouteTable<>(origin, Map.copyOf(steps));
    }
}

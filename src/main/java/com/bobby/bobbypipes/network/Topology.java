package com.bobby.bobbypipes.network;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * An immutable snapshot of which nodes are linked to which, plus the cost of each link.
 *
 * <p>Deliberately free of any Minecraft type so the graph and the pathfinding over it can
 * be exercised without a running game. The world-facing layer instantiates this with
 * block positions as the node type.
 *
 * <p>Links are undirected: adding a link from a to b also makes b reachable from a. Costs
 * are per-link and must be positive, which is what lets the solver stop as soon as a node
 * is settled.
 *
 * @param <N> node identity, must be usable as a hash key and stable across game reloads
 */
public final class Topology<N> {

    private final Map<N, Map<N, Integer>> links;

    private Topology(Map<N, Map<N, Integer>> links) {
        this.links = links;
    }

    public static <N> Builder<N> builder() {
        return new Builder<>();
    }

    public static <N> Topology<N> empty() {
        return new Topology<>(Map.of());
    }

    /** Every node in the graph, including isolated ones. */
    public Set<N> nodes() {
        return Collections.unmodifiableSet(links.keySet());
    }

    public boolean contains(N node) {
        return links.containsKey(node);
    }

    public int size() {
        return links.size();
    }

    /** Neighbours of {@code node} mapped to the cost of reaching them, empty if unknown. */
    public Map<N, Integer> neighbours(N node) {
        Map<N, Integer> found = links.get(node);
        return found == null ? Map.of() : found;
    }

    /**
     * The set of nodes reachable from {@code start}, including {@code start} itself.
     *
     * <p>Used to decide which nodes a topology change actually affects. Breaking one pipe
     * in a large network should not force every other network in the level to recompute.
     */
    public Set<N> componentOf(N start) {
        if (!links.containsKey(start)) {
            return Set.of();
        }
        Set<N> seen = new LinkedHashSet<>();
        Deque<N> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            N current = queue.removeFirst();
            for (N neighbour : neighbours(current).keySet()) {
                if (seen.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        return seen;
    }

    /** Every connected component in the graph. */
    public Set<Set<N>> components() {
        Set<Set<N>> components = new LinkedHashSet<>();
        Set<N> assigned = new HashSet<>();
        for (N node : links.keySet()) {
            if (assigned.add(node)) {
                Set<N> component = componentOf(node);
                assigned.addAll(component);
                components.add(component);
            }
        }
        return components;
    }

    @Override
    public boolean equals(Object obj) {
        return obj instanceof Topology<?> other && links.equals(other.links);
    }

    @Override
    public int hashCode() {
        return links.hashCode();
    }

    public static final class Builder<N> {

        private final Map<N, Map<N, Integer>> links = new HashMap<>();

        /** Registers a node with no links yet. Nodes gain links via {@link #link}. */
        public Builder<N> node(N node) {
            links.computeIfAbsent(node, key -> new HashMap<>());
            return this;
        }

        public Builder<N> link(N a, N b) {
            return link(a, b, 1);
        }

        /**
         * Links {@code a} and {@code b} in both directions.
         *
         * <p>If the pair is already linked the cheaper cost wins, so a caller walking the
         * world in an arbitrary order cannot make a route look more expensive than it is.
         */
        public Builder<N> link(N a, N b, int cost) {
            if (cost <= 0) {
                throw new IllegalArgumentException("link cost must be positive, got " + cost);
            }
            if (a.equals(b)) {
                throw new IllegalArgumentException("cannot link a node to itself: " + a);
            }
            node(a);
            node(b);
            links.get(a).merge(b, cost, Math::min);
            links.get(b).merge(a, cost, Math::min);
            return this;
        }

        public Topology<N> build() {
            Map<N, Map<N, Integer>> copy = new HashMap<>(links.size());
            links.forEach((node, neighbours) -> copy.put(node, Map.copyOf(neighbours)));
            return new Topology<>(Map.copyOf(copy));
        }
    }
}

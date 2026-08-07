package com.bobby.bobbypipes.logistics;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Precomputed routes away from one origin node.
 *
 * <p>Immutable by construction. A parcel sitting at the origin asks for the next hop
 * toward its destination and gets an answer without any graph traversal, which is the
 * whole point: pathfinding happens when the network changes, never while items move.
 *
 * @param <N> node identity
 */
public final class RouteTable<N> {

    /**
     * One entry of a route table.
     *
     * @param nextHop the neighbour to move to next, never the destination unless adjacent
     * @param cost    total cost from the origin to the destination
     */
    public record Step<N>(N nextHop, int cost) {
    }

    private final N origin;
    private final Map<N, Step<N>> steps;

    RouteTable(N origin, Map<N, Step<N>> steps) {
        this.origin = origin;
        this.steps = steps;
    }

    public N origin() {
        return origin;
    }

    /** Destinations reachable from the origin, excluding the origin itself. */
    public Set<N> destinations() {
        return Collections.unmodifiableSet(steps.keySet());
    }

    public boolean canReach(N destination) {
        return steps.containsKey(destination);
    }

    /** The next node to move toward to make progress to {@code destination}. */
    public Optional<N> nextHop(N destination) {
        return Optional.ofNullable(steps.get(destination)).map(Step::nextHop);
    }

    /** Total cost from the origin to {@code destination}, empty if unreachable. */
    public Optional<Integer> costTo(N destination) {
        return Optional.ofNullable(steps.get(destination)).map(Step::cost);
    }

    /**
     * Reachable destinations ordered cheapest first.
     *
     * <p>This is the ordering the request planner wants: when several inventories can
     * satisfy a request, pull from the nearest one so items spend the least time in
     * transit.
     */
    public List<N> destinationsByCost() {
        return steps.entrySet().stream()
                .sorted(Comparator.comparingInt(entry -> entry.getValue().cost()))
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public String toString() {
        return "RouteTable[origin=" + origin + ", destinations=" + steps.size() + "]";
    }
}

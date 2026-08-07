package com.bobby.bobbypipes.logistics;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A consistent view of the network: one topology plus the route tables solved from it.
 *
 * <p>Snapshots are never mutated. A topology change produces a whole new snapshot which
 * replaces the old one in a single reference write, so a reader either sees the entire
 * old network or the entire new one and never a half-rebuilt mixture. Parcels already in
 * flight keep using the snapshot they started with until they reach a node that exists in
 * the new one.
 *
 * @param <N> node identity
 */
public final class RoutingSnapshot<N> {

    private final Topology<N> topology;
    private final Map<N, RouteTable<N>> tables;
    private final long revision;

    private RoutingSnapshot(Topology<N> topology, Map<N, RouteTable<N>> tables, long revision) {
        this.topology = topology;
        this.tables = tables;
        this.revision = revision;
    }

    public static <N> RoutingSnapshot<N> empty() {
        return new RoutingSnapshot<>(Topology.empty(), Map.of(), 0L);
    }

    /** Solves {@code topology} and returns the result tagged with {@code revision}. */
    public static <N> RoutingSnapshot<N> of(Topology<N> topology, long revision) {
        return new RoutingSnapshot<>(topology, RouteSolver.solveAll(topology), revision);
    }

    public Topology<N> topology() {
        return topology;
    }

    /**
     * Monotonic counter identifying this snapshot.
     *
     * <p>A parcel records the revision it was routed against. When that no longer matches
     * the live snapshot the parcel knows its route may be stale and asks for a fresh one.
     */
    public long revision() {
        return revision;
    }

    public Set<N> nodes() {
        return topology.nodes();
    }

    public boolean contains(N node) {
        return topology.contains(node);
    }

    /** The route table for {@code origin}, empty if that node is not on the network. */
    public Optional<RouteTable<N>> routesFrom(N origin) {
        return Optional.ofNullable(tables.get(origin));
    }

    /**
     * The next node to move to when travelling from {@code from} to {@code to}.
     *
     * <p>Empty if either endpoint is off the network or no path exists.
     */
    public Optional<N> nextHop(N from, N to) {
        return routesFrom(from).flatMap(table -> table.nextHop(to));
    }

    public Optional<Integer> cost(N from, N to) {
        return routesFrom(from).flatMap(table -> table.costTo(to));
    }

    public boolean canReach(N from, N to) {
        return nextHop(from, to).isPresent();
    }

    @Override
    public String toString() {
        return "RoutingSnapshot[revision=" + revision + ", nodes=" + topology.size() + "]";
    }
}

package com.bobby.bobbypipes.transit;

import com.bobby.bobbypipes.network.RoutingSnapshot;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Holds the parcels in flight on one network and advances them.
 *
 * <p>Movement is a map lookup per hop, never a search: a parcel asks the routing snapshot
 * for the next node and walks to it. The expensive work happened when the network was
 * solved.
 *
 * <p>Parcels survive the network changing underneath them. Each one records the routing
 * revision its next hop was computed against; when that no longer matches the live
 * snapshot the parcel re-asks from wherever it currently sits. A parcel is stranded only
 * when its destination is no longer reachable from a still-valid perch  -  breaking an
 * unrelated spur must not eject transfers that can still finish their trip.
 *
 * @param <N> node identity
 * @param <P> payload type
 */
public final class ParcelTracker<N, P> {

    /** A parcel that reached its destination this tick. */
    public record Delivery<N, P>(long id, P payload, N destination) {
    }

    /**
     * A parcel that can no longer reach its destination.
     *
     * @param at the node it is sitting on, which is where it should be dealt with
     */
    public record Stranded<N, P>(long id, P payload, N at, N destination) {
    }

    /** What happened during one tick. Both lists are empty on a quiet tick. */
    public record TickReport<N, P>(List<Delivery<N, P>> delivered, List<Stranded<N, P>> stranded) {

        public boolean isQuiet() {
            return delivered.isEmpty() && stranded.isEmpty();
        }
    }

    private final int ticksPerHop;
    private final Map<Long, Parcel<N, P>> parcels = new LinkedHashMap<>();
    private long nextId = 1L;

    /**
     * @param ticksPerHop how many ticks a parcel spends traversing one link. Must be at
     *                    least 1; a hop cannot be instantaneous or a long network would
     *                    deliver in a single tick.
     */
    public ParcelTracker(int ticksPerHop) {
        if (ticksPerHop < 1) {
            throw new IllegalArgumentException("ticksPerHop must be at least 1, got " + ticksPerHop);
        }
        this.ticksPerHop = ticksPerHop;
    }

    public int ticksPerHop() {
        return ticksPerHop;
    }

    public int inFlight() {
        return parcels.size();
    }

    public Collection<Parcel<N, P>> parcels() {
        return Collections.unmodifiableCollection(parcels.values());
    }

    public Optional<Parcel<N, P>> parcel(long id) {
        return Optional.ofNullable(parcels.get(id));
    }

    /**
     * Puts a payload onto the network at {@code from}, headed for {@code to}.
     *
     * @return the new parcel id, or empty if {@code to} is not reachable from
     *         {@code from} right now. Refusing up front is deliberate: it lets the caller
     *         keep the items rather than discovering mid-flight that they have nowhere to
     *         go.
     */
    public Optional<Long> inject(P payload, N from, N to, RoutingSnapshot<N> routes) {
        if (from.equals(to)) {
            // Already there. Still issue a parcel so the caller gets a delivery callback
            // through the same path as any other request.
            long id = nextId++;
            parcels.put(id, new Parcel<>(id, payload, from, to, from, null, 0, routes.revision()));
            return Optional.of(id);
        }
        Optional<N> firstHop = routes.nextHop(from, to);
        if (firstHop.isEmpty()) {
            return Optional.empty();
        }
        long id = nextId++;
        parcels.put(id, new Parcel<>(id, payload, from, to, from, firstHop.get(), 0, routes.revision()));
        return Optional.of(id);
    }

    /** Removes a parcel without delivering it, returning its payload if it was present. */
    public Optional<P> cancel(long id) {
        Parcel<N, P> removed = parcels.remove(id);
        return Optional.ofNullable(removed).map(Parcel::payload);
    }

    public void clear() {
        parcels.clear();
    }

    /**
     * Advances every parcel by one tick against {@code routes}.
     *
     * <p>Delivered and stranded parcels are removed from the tracker before the report is
     * returned, so the caller owns their payloads and cannot double-handle them.
     */
    public TickReport<N, P> tick(RoutingSnapshot<N> routes) {
        if (parcels.isEmpty()) {
            return new TickReport<>(List.of(), List.of());
        }

        List<Delivery<N, P>> delivered = new ArrayList<>();
        List<Stranded<N, P>> stranded = new ArrayList<>();
        List<Long> finished = new ArrayList<>();
        List<Parcel<N, P>> updated = new ArrayList<>(parcels.size());

        for (Parcel<N, P> parcel : List.copyOf(parcels.values())) {
            Parcel<N, P> current = parcel;

            if (current.hasArrived()) {
                delivered.add(new Delivery<>(current.id(), current.payload(), current.destination()));
                finished.add(current.id());
                continue;
            }

            if (current.revision() != routes.revision()) {
                current = reseatAfterTopologyChange(current, routes);
            }

            if (current.hasArrived()) {
                delivered.add(new Delivery<>(current.id(), current.payload(), current.destination()));
                finished.add(current.id());
                continue;
            }

            if (current.isStuck()) {
                stranded.add(new Stranded<>(current.id(), current.payload(),
                        current.atNode(), current.destination()));
                finished.add(current.id());
                continue;
            }

            current = current.advanced();
            if (current.ticksIntoHop() >= ticksPerHop) {
                N arrivedAt = current.nextHop();
                if (!routes.contains(arrivedAt)) {
                    // Landing pad vanished mid-hop. Reseat from the pipe we still occupy;
                    // only strand if the destination is truly cut off.
                    current = reseatAfterTopologyChange(
                            current.withRoute(null, routes.revision()), routes);
                    if (current.hasArrived()) {
                        delivered.add(new Delivery<>(
                                current.id(), current.payload(), current.destination()));
                        finished.add(current.id());
                        continue;
                    }
                    if (current.isStuck()) {
                        stranded.add(new Stranded<>(current.id(), current.payload(),
                                current.atNode(), current.destination()));
                        finished.add(current.id());
                        continue;
                    }
                    updated.add(current);
                    continue;
                }
                if (arrivedAt.equals(current.destination())) {
                    delivered.add(new Delivery<>(current.id(), current.payload(), current.destination()));
                    finished.add(current.id());
                    continue;
                }
                N onward = routes.nextHop(arrivedAt, current.destination()).orElse(null);
                current = current.movedToNextHop(onward);
                if (current.isStuck()) {
                    stranded.add(new Stranded<>(current.id(), current.payload(),
                            current.atNode(), current.destination()));
                    finished.add(current.id());
                    continue;
                }
            }
            updated.add(current);
        }

        updated.forEach(parcel -> parcels.put(parcel.id(), parcel));
        finished.forEach(parcels::remove);
        return new TickReport<>(List.copyOf(delivered), List.copyOf(stranded));
    }

    /**
     * Recomputes the next hop after a topology rebuild.
     *
     * <p>Strands only when the destination cannot be reached from a still-valid perch.
     * If the pipe under the parcel was removed but its in-progress {@code nextHop} still
     * exists, the parcel snaps forward onto that hop and continues.
     */
    private static <N, P> Parcel<N, P> reseatAfterTopologyChange(Parcel<N, P> parcel,
                                                                 RoutingSnapshot<N> routes) {
        long revision = routes.revision();
        N at = parcel.atNode();
        N dest = parcel.destination();
        N hop = parcel.nextHop();

        if (routes.contains(at)) {
            if (at.equals(dest)) {
                return new Parcel<>(parcel.id(), parcel.payload(), parcel.origin(), dest,
                        at, null, 0, revision);
            }
            // Prefer the hop already in progress when it still leads to the destination so
            // breaking an unrelated spur does not restart interpolation mid-pipe.
            if (hop != null
                    && routes.contains(hop)
                    && routes.topology().neighbours(at).containsKey(hop)
                    && (hop.equals(dest) || routes.canReach(hop, dest))) {
                return parcel.withRoute(hop, revision);
            }
            Optional<N> next = routes.nextHop(at, dest);
            return parcel.withRoute(next.orElse(null), revision);
        }

        // Pipe under us is gone. Advance onto the hop we were already crossing if it still
        // exists and can finish the trip (or is the destination).
        if (hop != null && routes.contains(hop)) {
            if (hop.equals(dest)) {
                return new Parcel<>(parcel.id(), parcel.payload(), parcel.origin(), dest,
                        hop, null, 0, revision);
            }
            Optional<N> next = routes.nextHop(hop, dest);
            return new Parcel<>(parcel.id(), parcel.payload(), parcel.origin(), dest,
                    hop, next.orElse(null), 0, revision);
        }

        return parcel.withRoute(null, revision);
    }
}

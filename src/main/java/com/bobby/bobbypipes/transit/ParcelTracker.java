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
 * snapshot the parcel re-asks from wherever it currently sits. A parcel whose destination
 * disappeared is reported as stranded rather than silently deleted, so the caller can
 * decide whether to return it to sender or drop it in the world.
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
            parcels.put(id, new Parcel<>(id, payload, to, from, null, 0, routes.revision()));
            return Optional.of(id);
        }
        Optional<N> firstHop = routes.nextHop(from, to);
        if (firstHop.isEmpty()) {
            return Optional.empty();
        }
        long id = nextId++;
        parcels.put(id, new Parcel<>(id, payload, to, from, firstHop.get(), 0, routes.revision()));
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

            // The network may have been rebuilt since this parcel last looked. Re-ask
            // from where it actually is rather than trusting a hop that may now lead
            // into a gap.
            if (current.revision() != routes.revision()) {
                N rerouted = routes.nextHop(current.atNode(), current.destination()).orElse(null);
                current = current.withRoute(rerouted, routes.revision());
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
                if (arrivedAt.equals(current.destination())) {
                    delivered.add(new Delivery<>(current.id(), current.payload(), current.destination()));
                    finished.add(current.id());
                    continue;
                }
                N onward = routes.nextHop(arrivedAt, current.destination()).orElse(null);
                current = current.movedToNextHop(onward);
            }
            updated.add(current);
        }

        updated.forEach(parcel -> parcels.put(parcel.id(), parcel));
        finished.forEach(parcels::remove);
        return new TickReport<>(List.copyOf(delivered), List.copyOf(stranded));
    }
}

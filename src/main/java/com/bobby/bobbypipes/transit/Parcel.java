package com.bobby.bobbypipes.transit;

/**
 * One payload in transit across the network.
 *
 * <p>A parcel is network state, not an entity. It exists as a record held by the level's
 * {@link ParcelTracker} and is drawn by the client from synced data. Nothing about it
 * depends on an entity being loaded, so a parcel crossing an unloaded chunk boundary is
 * not a special case.
 *
 * <p>Immutable. Advancing a parcel produces a new one rather than mutating in place,
 * which keeps a half-updated parcel from ever being observable.
 *
 * @param id           identity, unique within the tracker that issued it
 * @param payload      what is being carried
 * @param origin       where it entered the network, so the first hop can start at the
 *                     inventory it was drawn from rather than at a pipe centre
 * @param destination  where it is headed
 * @param atNode       the node it currently occupies
 * @param nextHop      the node it is moving toward, or null if it has nowhere to go
 * @param ticksIntoHop how far along the current hop it is
 * @param revision     the routing revision {@code nextHop} was computed against
 * @param <N>          node identity
 * @param <P>          payload type
 */
public record Parcel<N, P>(
        long id,
        P payload,
        N origin,
        N destination,
        N atNode,
        N nextHop,
        int ticksIntoHop,
        long revision) {

    public boolean hasArrived() {
        return atNode.equals(destination);
    }

    public boolean isStuck() {
        return nextHop == null && !hasArrived();
    }

    /**
     * Fraction of the way along the current hop, 0 to 1.
     *
     * <p>The renderer interpolates between {@code atNode} and {@code nextHop} with this.
     */
    public float progress(int ticksPerHop) {
        if (ticksPerHop <= 0) {
            return 1.0f;
        }
        return Math.min(1.0f, (float) ticksIntoHop / ticksPerHop);
    }

    Parcel<N, P> withRoute(N newNextHop, long newRevision) {
        return new Parcel<>(id, payload, origin, destination, atNode, newNextHop, ticksIntoHop, newRevision);
    }

    Parcel<N, P> advanced() {
        return new Parcel<>(id, payload, origin, destination, atNode, nextHop, ticksIntoHop + 1, revision);
    }

    Parcel<N, P> movedToNextHop(N newNextHop) {
        return new Parcel<>(id, payload, origin, destination, nextHop, newNextHop, 0, revision);
    }
}

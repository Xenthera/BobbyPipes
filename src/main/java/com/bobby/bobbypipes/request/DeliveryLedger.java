package com.bobby.bobbypipes.request;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks what the network has promised but not yet delivered.
 *
 * <p>A committed plan turns into promises: this provider owes this many of this item to
 * this requester. Until a promise settles the items are neither in the requester's
 * inventory nor freely available to the next request, and something has to remember that.
 * Without it, two requests made a tick apart would both plan against the same stock and
 * one would silently come up short.
 *
 * <p>Promises expire. A provider that is broken, unloaded, or simply never delivers would
 * otherwise hold its reservation forever, so every promise carries a deadline and
 * {@link #expire} releases the ones that have passed it.
 *
 * @param <N> node identity
 * @param <I> item identity
 */
public final class DeliveryLedger<N, I> {

    /** How a promise ended. */
    public enum Outcome {
        /** Everything promised arrived. */
        FULFILLED,
        /** Some arrived before the promise was closed out. */
        PARTIAL,
        /** Nothing arrived before the deadline. */
        EXPIRED,
        /** Closed deliberately, for instance because the requester went away. */
        CANCELLED
    }

    /**
     * One outstanding obligation.
     *
     * @param id        identity within this ledger
     * @param source    who owes the items
     * @param requester who is waiting for them
     * @param delivered how many have arrived so far
     * @param deadline  tick after which the promise is considered failed
     */
    public record Promise<N, I>(
            long id, N source, N requester, I item, int amount, int delivered, long deadline) {

        public int outstanding() {
            return amount - delivered;
        }

        public boolean isComplete() {
            return delivered >= amount;
        }

        Promise<N, I> withDelivered(int newDelivered) {
            return new Promise<>(id, source, requester, item, amount, newDelivered, deadline);
        }
    }

    /** A promise that has ended, and how. */
    public record Settlement<N, I>(Promise<N, I> promise, Outcome outcome) {
    }

    private final Map<Long, Promise<N, I>> open = new LinkedHashMap<>();
    private long nextId = 1L;

    /**
     * Records that {@code source} owes {@code requester} the given items.
     *
     * @param deadline tick after which the promise expires
     */
    public long promise(N source, N requester, I item, int amount, long deadline) {
        if (amount <= 0) {
            throw new IllegalArgumentException("promise amount must be positive, got " + amount);
        }
        long id = nextId++;
        open.put(id, new Promise<>(id, source, requester, item, amount, 0, deadline));
        return id;
    }

    public int openCount() {
        return open.size();
    }

    public Collection<Promise<N, I>> promises() {
        return Collections.unmodifiableCollection(open.values());
    }

    public Optional<Promise<N, I>> promise(long id) {
        return Optional.ofNullable(open.get(id));
    }

    /**
     * How much of {@code item} is still owed from {@code source} on open promises
     * (already extracted into transit).
     *
     * <p>Free stock uses the send queue for not-yet-extracted claims; this figure is for
     * promise accounting and diagnostics, not for subtracting from chest counts again.
     */
    public int reserved(N source, I item) {
        return saturate(open.values().stream()
                .filter(promise -> promise.source().equals(source) && promise.item().equals(item))
                .mapToLong(Promise::outstanding)
                .sum());
    }

    /** How much of {@code item} is inbound to {@code requester} across all promises. */
    public int inbound(N requester, I item) {
        return saturate(open.values().stream()
                .filter(promise -> promise.requester().equals(requester) && promise.item().equals(item))
                .mapToLong(Promise::outstanding)
                .sum());
    }

    /**
     * Clamps a summed total into {@code int} range.
     *
     * <p>Both sums stay {@code int} on the way out because every caller does {@code int}
     * arithmetic against item counts, FE, or mB, all of which are {@code int} at the
     * capability boundary anyway. But the sum itself is taken in {@code long}: energy
     * promises are individually large enough now (see
     * {@link com.bobby.bobbypipes.transit.ParcelTier}) that enough in flight to one
     * destination could wrap an {@code int} accumulator, and a wrapped negative inbound
     * reads as "there is room for more" and pulls even harder. Clamping high is the safe
     * direction to be wrong in: it says the destination is fuller than it is, which
     * throttles, and it cannot be reached without a genuinely absurd number of parcels.
     */
    private static int saturate(long total) {
        return total > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) total;
    }

    /**
     * Records an arrival against a promise.
     *
     * @return the settlement if this completed the promise, otherwise empty
     */
    public Optional<Settlement<N, I>> recordDelivery(long id, int amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("delivered amount must be positive, got " + amount);
        }
        Promise<N, I> promise = open.get(id);
        if (promise == null) {
            return Optional.empty();
        }
        // Over-delivery is clamped rather than rejected: an extra stack arriving is not a
        // reason to leave the promise open forever.
        int delivered = Math.min(promise.amount(), promise.delivered() + amount);
        Promise<N, I> updated = promise.withDelivered(delivered);

        if (updated.isComplete()) {
            open.remove(id);
            return Optional.of(new Settlement<>(updated, Outcome.FULFILLED));
        }
        open.put(id, updated);
        return Optional.empty();
    }

    /** Closes a promise early. */
    public Optional<Settlement<N, I>> cancel(long id) {
        Promise<N, I> promise = open.remove(id);
        if (promise == null) {
            return Optional.empty();
        }
        Outcome outcome = promise.delivered() > 0 ? Outcome.PARTIAL : Outcome.CANCELLED;
        return Optional.of(new Settlement<>(promise, outcome));
    }

    /**
     * Closes every promise whose deadline has passed.
     *
     * <p>Call once per tick. A promise that received something before timing out settles
     * as {@link Outcome#PARTIAL} so the caller can tell a slow provider from a dead one.
     */
    public List<Settlement<N, I>> expire(long currentTick) {
        List<Settlement<N, I>> settled = new ArrayList<>();
        for (Promise<N, I> promise : List.copyOf(open.values())) {
            if (currentTick > promise.deadline()) {
                open.remove(promise.id());
                settled.add(new Settlement<>(promise,
                        promise.delivered() > 0 ? Outcome.PARTIAL : Outcome.EXPIRED));
            }
        }
        return List.copyOf(settled);
    }

    public void clear() {
        open.clear();
    }
}

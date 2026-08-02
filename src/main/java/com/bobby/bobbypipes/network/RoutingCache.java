package com.bobby.bobbypipes.network;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Holds the live {@link RoutingSnapshot} and swaps in replacements atomically.
 *
 * <p>Readers call {@link #current()} and are guaranteed a fully solved snapshot. Writers
 * mark the cache dirty when the world changes and the rebuild happens once, later, off
 * the back of a tick rather than inside the block update that caused it. Placing a long
 * run of pipes therefore triggers one solve, not one per pipe.
 *
 * @param <N> node identity
 */
public final class RoutingCache<N> {

    private final AtomicReference<RoutingSnapshot<N>> live =
            new AtomicReference<>(RoutingSnapshot.empty());
    private final AtomicReference<Supplier<Topology<N>>> pending = new AtomicReference<>();
    private final AtomicLong revisions = new AtomicLong();

    /** The current snapshot. Never null, empty before the first rebuild. */
    public RoutingSnapshot<N> current() {
        return live.get();
    }

    public boolean isDirty() {
        return pending.get() != null;
    }

    public long revision() {
        return live.get().revision();
    }

    /**
     * Records that the world changed and the graph needs re-reading.
     *
     * <p>The supplier is invoked at rebuild time, not now, so it observes the world after
     * all the block updates in the current batch have settled. Marking twice before a
     * rebuild keeps only the latest supplier, which collapses a burst of changes into one
     * solve.
     */
    public void invalidate(Supplier<Topology<N>> topologySupplier) {
        pending.set(topologySupplier);
    }

    /**
     * Rebuilds if dirty and returns true if a new snapshot was published.
     *
     * <p>Call from the level tick. Cheap when clean: one reference read.
     */
    public boolean rebuildIfDirty() {
        Supplier<Topology<N>> supplier = pending.getAndSet(null);
        if (supplier == null) {
            return false;
        }
        publish(supplier.get());
        return true;
    }

    /** Solves {@code topology} and swaps it in immediately, bypassing the dirty flag. */
    public RoutingSnapshot<N> publish(Topology<N> topology) {
        RoutingSnapshot<N> snapshot = RoutingSnapshot.of(topology, revisions.incrementAndGet());
        live.set(snapshot);
        return snapshot;
    }

    /** Drops all routing state. Used when a level unloads. */
    public void clear() {
        pending.set(null);
        live.set(RoutingSnapshot.empty());
    }
}

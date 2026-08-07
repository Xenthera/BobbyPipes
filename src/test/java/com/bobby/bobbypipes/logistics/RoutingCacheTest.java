package com.bobby.bobbypipes.logistics;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoutingCacheTest {

    private static Topology<String> line(String... nodes) {
        Topology.Builder<String> builder = Topology.builder();
        for (int i = 0; i < nodes.length - 1; i++) {
            builder.link(nodes[i], nodes[i + 1]);
        }
        return builder.build();
    }

    @Test
    @DisplayName("a fresh cache is empty rather than null")
    void startsEmpty() {
        RoutingCache<String> cache = new RoutingCache<>();

        assertTrue(cache.current().nodes().isEmpty());
        assertFalse(cache.isDirty());
        assertEquals(0L, cache.revision());
    }

    @Test
    @DisplayName("rebuild is a no-op when nothing was invalidated")
    void rebuildIsCheapWhenClean() {
        RoutingCache<String> cache = new RoutingCache<>();
        cache.publish(line("a", "b"));
        RoutingSnapshot<String> before = cache.current();

        assertFalse(cache.rebuildIfDirty());
        assertSameSnapshot(before, cache.current());
    }

    @Test
    @DisplayName("a burst of changes collapses into one solve")
    void repeatedInvalidationSolvesOnce() {
        // This is the behaviour that stops placing a run of pipes from triggering one
        // full solve per pipe placed.
        RoutingCache<String> cache = new RoutingCache<>();
        AtomicInteger solves = new AtomicInteger();

        for (int i = 0; i < 10; i++) {
            cache.invalidate(() -> {
                solves.incrementAndGet();
                return line("a", "b", "c");
            });
        }

        assertTrue(cache.isDirty());
        assertTrue(cache.rebuildIfDirty());

        assertEquals(1, solves.get());
        assertFalse(cache.isDirty());
    }

    @Test
    @DisplayName("the supplier runs at rebuild time, not at invalidate time")
    void supplierIsDeferred() {
        // The world is still mid-update when invalidate is called, so reading it then
        // would see a half-applied change.
        RoutingCache<String> cache = new RoutingCache<>();
        AtomicInteger reads = new AtomicInteger();

        cache.invalidate(() -> {
            reads.incrementAndGet();
            return line("a", "b");
        });
        assertEquals(0, reads.get());

        cache.rebuildIfDirty();
        assertEquals(1, reads.get());
    }

    @Test
    @DisplayName("each published snapshot gets a new revision")
    void revisionAdvancesOnPublish() {
        RoutingCache<String> cache = new RoutingCache<>();

        cache.publish(line("a", "b"));
        long first = cache.revision();
        cache.publish(line("a", "b", "c"));
        long second = cache.revision();

        assertTrue(second > first, "revision must advance so parcels can spot a stale route");
    }

    @Test
    @DisplayName("swapping in a new snapshot leaves the old one intact for in-flight readers")
    void oldSnapshotSurvivesTheSwap() {
        // A parcel that grabbed the old snapshot must keep getting consistent answers
        // from it even after the network was rebuilt underneath it.
        RoutingCache<String> cache = new RoutingCache<>();
        cache.publish(line("a", "b", "c"));
        RoutingSnapshot<String> held = cache.current();

        cache.publish(line("a", "b"));

        assertNotSame(held, cache.current());
        assertTrue(held.canReach("a", "c"), "old snapshot still routes to the removed node");
        assertFalse(cache.current().canReach("a", "c"), "new snapshot does not");
    }

    @Test
    @DisplayName("clear drops both the snapshot and any pending rebuild")
    void clearResetsEverything() {
        RoutingCache<String> cache = new RoutingCache<>();
        cache.publish(line("a", "b"));
        cache.invalidate(() -> line("a", "b", "c"));

        cache.clear();

        assertFalse(cache.isDirty());
        assertTrue(cache.current().nodes().isEmpty());
    }

    private static void assertSameSnapshot(RoutingSnapshot<String> a, RoutingSnapshot<String> b) {
        assertEquals(a.revision(), b.revision());
        assertEquals(a.nodes(), b.nodes());
    }
}

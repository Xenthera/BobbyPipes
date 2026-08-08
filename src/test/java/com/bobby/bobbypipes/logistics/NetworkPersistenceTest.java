package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.Parcel;
import com.bobby.bobbypipes.transit.ParcelCodecs;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Capture/restore for the state that used to be discarded on world unload.
 *
 * <p>These exercise the in-memory halves only; the codecs themselves need a registry access to
 * round-trip an {@code ItemResource}, which a plain unit test does not have. What is covered
 * here is the part that was actually getting things wrong: id seeding, and restoring without
 * losing or duplicating entries.
 */
class NetworkPersistenceTest {

    private static final BlockPos A = new BlockPos(0, 64, 0);
    private static final BlockPos B = new BlockPos(4, 64, 0);
    private static final BlockPos C = new BlockPos(8, 64, 0);

    /** A tracker whose hops are a fixed length, so no world is needed. */
    private static ParcelTracker<BlockPos, String> tracker() {
        return new ParcelTracker<>(8);
    }

    @Test
    @DisplayName("restored parcels are all present and keep their identity")
    void parcelsRoundTrip() {
        ParcelTracker<BlockPos, String> source = tracker();
        List<Parcel<BlockPos, String>> saved = List.of(
                new Parcel<>(7L, "one", A, C, B, C, 3, 8, 123L),
                new Parcel<>(9L, "two", A, C, A, B, 0, 8, 123L));

        ParcelTracker<BlockPos, String> loaded = tracker();
        loaded.restore(saved);

        assertEquals(2, loaded.inFlight());
        assertTrue(loaded.parcel(7L).isPresent());
        assertEquals("two", loaded.parcel(9L).orElseThrow().payload());
        assertEquals(3, loaded.parcel(7L).orElseThrow().ticksIntoHop(),
                "a parcel resumes part-way through its hop, not from the start");
        assertEquals(0, source.inFlight(), "restoring one tracker must not touch another");
    }

    @Test
    @DisplayName("a restored tracker never reissues a loaded parcel id")
    void parcelIdsAreSeeded() {
        ParcelTracker<BlockPos, String> loaded = tracker();
        loaded.restore(List.of(new Parcel<>(50L, "x", A, C, A, B, 0, 8, 1L)));

        // Reusing 50 would let a later cancel or delivery settle the wrong parcel.
        long fresh = loaded.injectWithFirstHop("y", A, C, B, 1L).orElseThrow();
        assertTrue(fresh > 50L, "expected an id past the restored one, got " + fresh);
    }

    @Test
    @DisplayName("loaded parcels carry a stale revision so the first tick re-solves their hop")
    void loadedParcelsAreReseated() {
        // The routing snapshot is rebuilt from the world on load and gets a fresh revision, so
        // a saved one would leave parcels trusting a routing table that no longer exists.
        assertNotEquals(0L, ParcelCodecs.STALE_REVISION);
        assertEquals(Long.MIN_VALUE, ParcelCodecs.STALE_REVISION);
    }

    @Test
    @DisplayName("open promises survive with their partial deliveries intact")
    void promisesRoundTrip() {
        DeliveryLedger<BlockPos, String> ledger = new DeliveryLedger<>();
        ledger.restore(List.of(
                new DeliveryLedger.Promise<>(3L, A, B, "iron", 64, 20, 500L),
                new DeliveryLedger.Promise<>(4L, A, C, "iron", 10, 0, 500L)));

        assertEquals(2, ledger.openCount());
        DeliveryLedger.Promise<BlockPos, String> partial = ledger.promise(3L).orElseThrow();
        assertEquals(20, partial.delivered(), "a half-delivered promise stays half-delivered");
        assertEquals(44, partial.outstanding());
        // 64 of iron promised from A, of which 20 already arrived.
        assertEquals(54, ledger.reserved(A, "iron"), "44 outstanding to B plus 10 to C");
    }

    @Test
    @DisplayName("a restored ledger never reissues a loaded promise id")
    void promiseIdsAreSeeded() {
        DeliveryLedger<BlockPos, String> ledger = new DeliveryLedger<>();
        ledger.restore(List.of(new DeliveryLedger.Promise<>(99L, A, B, "iron", 5, 0, 100L)));

        long fresh = ledger.promise(A, C, "iron", 1, 200L);
        assertTrue(fresh > 99L, "expected an id past the restored one, got " + fresh);
        assertTrue(ledger.promise(99L).isPresent(), "and the restored promise is still open");
    }

    @Test
    @DisplayName("restoring replaces rather than merges, so a double load cannot duplicate")
    void restoreReplaces() {
        DeliveryLedger<BlockPos, String> ledger = new DeliveryLedger<>();
        List<DeliveryLedger.Promise<BlockPos, String>> saved =
                List.of(new DeliveryLedger.Promise<>(1L, A, B, "iron", 5, 0, 100L));
        ledger.restore(saved);
        ledger.restore(saved);
        assertEquals(1, ledger.openCount());
    }
}

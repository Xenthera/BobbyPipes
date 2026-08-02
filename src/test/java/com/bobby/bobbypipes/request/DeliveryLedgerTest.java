package com.bobby.bobbypipes.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryLedgerTest {

    private static DeliveryLedger<String, String> ledger() {
        return new DeliveryLedger<>();
    }

    @Test
    @DisplayName("an open promise reserves stock at its source")
    void openPromiseReservesStock() {
        // This is what stops a second request planning against items the first claimed.
        DeliveryLedger<String, String> ledger = ledger();
        ledger.promise("chest", "player", "iron", 10, 100);

        assertEquals(10, ledger.reserved("chest", "iron"));
        assertEquals(0, ledger.reserved("chest", "gold"));
        assertEquals(0, ledger.reserved("other", "iron"));
    }

    @Test
    @DisplayName("reservations shrink as deliveries arrive")
    void reservationShrinksOnDelivery() {
        DeliveryLedger<String, String> ledger = ledger();
        long id = ledger.promise("chest", "player", "iron", 10, 100);

        ledger.recordDelivery(id, 4);

        assertEquals(6, ledger.reserved("chest", "iron"));
        assertEquals(6, ledger.inbound("player", "iron"));
    }

    @Test
    @DisplayName("a fully delivered promise settles as fulfilled and stops reserving")
    void fullDeliverySettles() {
        DeliveryLedger<String, String> ledger = ledger();
        long id = ledger.promise("chest", "player", "iron", 10, 100);

        assertTrue(ledger.recordDelivery(id, 6).isEmpty(), "partial delivery does not settle");
        Optional<DeliveryLedger.Settlement<String, String>> settlement = ledger.recordDelivery(id, 4);

        assertTrue(settlement.isPresent());
        assertEquals(DeliveryLedger.Outcome.FULFILLED, settlement.get().outcome());
        assertEquals(0, ledger.openCount());
        assertEquals(0, ledger.reserved("chest", "iron"));
    }

    @Test
    @DisplayName("over-delivery closes the promise rather than leaving it open")
    void overDeliveryIsClamped() {
        DeliveryLedger<String, String> ledger = ledger();
        long id = ledger.promise("chest", "player", "iron", 10, 100);

        Optional<DeliveryLedger.Settlement<String, String>> settlement = ledger.recordDelivery(id, 999);

        assertTrue(settlement.isPresent());
        assertEquals(10, settlement.get().promise().delivered(), "clamped to what was promised");
        assertEquals(0, ledger.openCount());
    }

    @Test
    @DisplayName("a promise nothing arrived for expires")
    void expiresWhenNothingArrives() {
        // A provider that was broken or unloaded would otherwise hold its reservation
        // forever and quietly starve every later request.
        DeliveryLedger<String, String> ledger = ledger();
        ledger.promise("chest", "player", "iron", 10, 100);

        assertTrue(ledger.expire(100).isEmpty(), "not yet past the deadline");

        List<DeliveryLedger.Settlement<String, String>> settled = ledger.expire(101);

        assertEquals(1, settled.size());
        assertEquals(DeliveryLedger.Outcome.EXPIRED, settled.getFirst().outcome());
        assertEquals(0, ledger.reserved("chest", "iron"));
    }

    @Test
    @DisplayName("a promise that half arrived expires as partial, not as a dead provider")
    void partialDeliveryExpiresAsPartial() {
        DeliveryLedger<String, String> ledger = ledger();
        long id = ledger.promise("chest", "player", "iron", 10, 100);
        ledger.recordDelivery(id, 3);

        List<DeliveryLedger.Settlement<String, String>> settled = ledger.expire(200);

        assertEquals(DeliveryLedger.Outcome.PARTIAL, settled.getFirst().outcome());
        assertEquals(3, settled.getFirst().promise().delivered());
    }

    @Test
    @DisplayName("cancelling distinguishes an untouched promise from a half filled one")
    void cancelReportsWhetherAnythingArrived() {
        DeliveryLedger<String, String> ledger = ledger();
        long untouched = ledger.promise("chest", "player", "iron", 10, 100);
        long started = ledger.promise("chest2", "player", "gold", 10, 100);
        ledger.recordDelivery(started, 2);

        assertEquals(DeliveryLedger.Outcome.CANCELLED, ledger.cancel(untouched).orElseThrow().outcome());
        assertEquals(DeliveryLedger.Outcome.PARTIAL, ledger.cancel(started).orElseThrow().outcome());
        assertEquals(0, ledger.openCount());
    }

    @Test
    @DisplayName("reservations from several promises add up per source and item")
    void reservationsAccumulate() {
        DeliveryLedger<String, String> ledger = ledger();
        ledger.promise("chest", "playerA", "iron", 10, 100);
        ledger.promise("chest", "playerB", "iron", 5, 100);

        assertEquals(15, ledger.reserved("chest", "iron"));
        assertEquals(10, ledger.inbound("playerA", "iron"));
        assertEquals(5, ledger.inbound("playerB", "iron"));
    }

    @Test
    @DisplayName("operations on an unknown promise are harmless")
    void unknownPromiseIsHarmless() {
        DeliveryLedger<String, String> ledger = ledger();

        assertTrue(ledger.recordDelivery(999L, 1).isEmpty());
        assertTrue(ledger.cancel(999L).isEmpty());
        assertFalse(ledger.promise(999L).isPresent());
    }

    @Test
    @DisplayName("nonsensical amounts are rejected")
    void rejectsBadAmounts() {
        DeliveryLedger<String, String> ledger = ledger();

        assertThrows(IllegalArgumentException.class,
                () -> ledger.promise("chest", "player", "iron", 0, 100));
        long id = ledger.promise("chest", "player", "iron", 5, 100);
        assertThrows(IllegalArgumentException.class, () -> ledger.recordDelivery(id, 0));
    }
}

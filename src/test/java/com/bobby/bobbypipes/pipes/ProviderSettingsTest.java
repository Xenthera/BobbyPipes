package com.bobby.bobbypipes.pipes;

import net.neoforged.neoforge.transfer.item.ItemResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderSettingsTest {

    @Test
    @DisplayName("default settings have an empty filter and normal leave mode")
    void defaults() {
        assertTrue(ProviderSettings.EMPTY.filterEmpty());
        assertTrue(ProviderSettings.EMPTY.include());
        assertEquals(ProviderLeaveMode.NORMAL, ProviderSettings.EMPTY.leaveMode());
    }

    @Test
    @DisplayName("empty item is never accepted")
    void rejectsEmptyItem() {
        assertFalse(ProviderSettings.EMPTY.accepts(ItemResource.EMPTY));
    }

    @Test
    @DisplayName("leave mode cycles through every option")
    void leaveModeCycles() {
        ProviderLeaveMode mode = ProviderLeaveMode.NORMAL;
        for (int i = 0; i < ProviderLeaveMode.values().length; i++) {
            mode = mode.next();
        }
        assertEquals(ProviderLeaveMode.NORMAL, mode);
    }

    @Test
    @DisplayName("leave-last skips only the last occupied stack, not empty trailing slots")
    void leaveLastSkipsLastOccupied() {
        // Occupied stacks in slots 0, 2, 5: last occupied is 5, not size-1.
        ProviderLeaveMode mode = ProviderLeaveMode.LEAVE_LAST;
        assertTrue(mode.allowsOccupiedSlot(0, 0, 5));
        assertTrue(mode.allowsOccupiedSlot(2, 0, 5));
        assertFalse(mode.allowsOccupiedSlot(5, 0, 5));
        assertTrue(mode.allowsOccupiedSlot(26, 0, 5)); // empty trailing; mask only cares about bounds
    }

    @Test
    @DisplayName("leave-first skips only the first occupied stack")
    void leaveFirstSkipsFirstOccupied() {
        ProviderLeaveMode mode = ProviderLeaveMode.LEAVE_FIRST;
        assertFalse(mode.allowsOccupiedSlot(2, 2, 8));
        assertTrue(mode.allowsOccupiedSlot(3, 2, 8));
        assertTrue(mode.allowsOccupiedSlot(8, 2, 8));
    }

    @Test
    @DisplayName("leave-first-and-last keeps middle occupied stacks")
    void leaveFirstAndLastKeepsMiddle() {
        ProviderLeaveMode mode = ProviderLeaveMode.LEAVE_FIRST_AND_LAST;
        assertFalse(mode.allowsOccupiedSlot(1, 1, 9));
        assertFalse(mode.allowsOccupiedSlot(9, 1, 9));
        assertTrue(mode.allowsOccupiedSlot(4, 1, 9));
    }

    @Test
    @DisplayName("a single occupied stack is skipped by both first and last modes")
    void singleOccupiedStackReserved() {
        assertFalse(ProviderLeaveMode.LEAVE_LAST.allowsOccupiedSlot(3, 3, 3));
        assertFalse(ProviderLeaveMode.LEAVE_FIRST.allowsOccupiedSlot(3, 3, 3));
        assertFalse(ProviderLeaveMode.LEAVE_FIRST_AND_LAST.allowsOccupiedSlot(3, 3, 3));
    }
}

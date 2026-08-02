package com.bobby.bobbypipes.filter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FilterListTest {

    @Test
    @DisplayName("an allow list passes only what it names")
    void allowListPassesOnlyListed() {
        FilterList<String> filter = FilterList.allowing("iron", "gold");

        assertTrue(filter.acceptsExactly("iron"));
        assertTrue(filter.acceptsExactly("gold"));
        assertFalse(filter.acceptsExactly("dirt"));
    }

    @Test
    @DisplayName("a deny list passes everything it does not name")
    void denyListPassesUnlisted() {
        FilterList<String> filter = FilterList.denying("dirt");

        assertFalse(filter.acceptsExactly("dirt"));
        assertTrue(filter.acceptsExactly("iron"));
    }

    @Test
    @DisplayName("an empty allow list passes nothing")
    void emptyAllowPassesNothing() {
        // Follows from the mode rather than being special cased: an allow list names what
        // is permitted, and an empty one permits nothing. A freshly placed Item Sink with
        // no configuration should sit idle, not swallow the whole network.
        FilterList<String> filter = FilterList.allowNothing();

        assertFalse(filter.acceptsExactly("iron"));
        assertTrue(filter.isEmpty());
    }

    @Test
    @DisplayName("an empty deny list passes everything")
    void emptyDenyPassesEverything() {
        FilterList<String> filter = FilterList.allowEverything();

        assertTrue(filter.acceptsExactly("iron"));
        assertTrue(filter.acceptsExactly("anything at all"));
    }

    @Test
    @DisplayName("flipping the mode inverts what passes")
    void modeFlipInverts() {
        FilterList<String> allow = FilterList.allowing("iron");
        FilterList<String> denied = allow.withMode(MatchMode.DENY);

        assertTrue(allow.acceptsExactly("iron"));
        assertFalse(denied.acceptsExactly("iron"));
        assertTrue(denied.acceptsExactly("gold"));
        assertEquals(allow.entries(), denied.entries(), "entries are untouched by a mode flip");
    }

    @Test
    @DisplayName("a candidate matching any one entry passes an allow list")
    void anyEntryMatchIsEnough() {
        // The predicate form is what lets a tag entry match many different items.
        FilterList<String> filter = FilterList.allowing("wood", "stone");

        assertTrue(filter.accepts(entry -> entry.startsWith("w")));
        assertFalse(filter.accepts(entry -> entry.startsWith("z")));
    }

    @Test
    @DisplayName("adding is idempotent so a UI can add freely")
    void addingIsIdempotent() {
        FilterList<String> filter = FilterList.allowing("iron");

        FilterList<String> again = filter.with("iron");

        assertSame(filter, again, "no new list allocated for a duplicate");
        assertEquals(1, again.size());
    }

    @Test
    @DisplayName("removing an absent entry is harmless")
    void removingAbsentEntryIsHarmless() {
        FilterList<String> filter = FilterList.allowing("iron");

        assertSame(filter, filter.without("gold"));
        assertEquals(0, filter.without("iron").size());
    }

    @Test
    @DisplayName("edits produce new lists rather than mutating")
    void editsAreImmutable() {
        FilterList<String> original = FilterList.allowing("iron");

        FilterList<String> extended = original.with("gold");

        assertEquals(1, original.size(), "original untouched");
        assertEquals(2, extended.size());
        assertThrows(UnsupportedOperationException.class, () -> original.entries().add("dirt"));
    }

    @Test
    @DisplayName("clearing keeps the mode")
    void clearingKeepsMode() {
        FilterList<String> filter = FilterList.denying("dirt", "gravel").cleared();

        assertEquals(MatchMode.DENY, filter.mode());
        assertTrue(filter.acceptsExactly("anything"), "an empty deny list still passes everything");
    }

    @Test
    @DisplayName("the constructor defends against a caller mutating the list afterwards")
    void constructorCopiesEntries() {
        List<String> mutable = new java.util.ArrayList<>(List.of("iron"));
        FilterList<String> filter = new FilterList<>(mutable, MatchMode.ALLOW);

        mutable.add("gold");

        assertEquals(1, filter.size());
    }

    @Test
    @DisplayName("mode ids round trip and unknown ids are rejected")
    void modeIdsRoundTrip() {
        for (MatchMode mode : MatchMode.values()) {
            assertEquals(mode, MatchMode.byId(mode.id()));
        }
        assertEquals(MatchMode.DENY, MatchMode.ALLOW.opposite());
        assertThrows(IllegalArgumentException.class, () -> MatchMode.byId("sideways"));
    }
}

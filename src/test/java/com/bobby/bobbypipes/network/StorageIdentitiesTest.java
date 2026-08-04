package com.bobby.bobbypipes.network;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageIdentitiesTest {

    @Test
    @DisplayName("shared list identity collapses two providers on one channel")
    void sharedListIdentityDedupes() {
        List<String> channelItems = new ArrayList<>();
        Object a = StorageIdentities.refKey(channelItems);
        Object b = StorageIdentities.refKey(channelItems);
        Object other = StorageIdentities.refKey(new ArrayList<>());

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, other);

        Set<Object> claimed = new HashSet<>();
        assertTrue(claimed.add(a));
        assertTrue(!claimed.add(b), "second provider on the same list must not claim again");
        assertTrue(claimed.add(other));
    }
}

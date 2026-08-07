package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.compat.BobbyChestsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Stable identity for an inventory attached to a pipe.
 *
 * <p>Default is the container {@link BlockPos}. BobbyChests networked (GLOBAL) chests that
 * share one backing item list resolve to that list's identity instead, so two provider pipes
 * on the same channel do not report the stock twice.
 */
public final class StorageIdentities {

    private StorageIdentities() {
    }

    /**
     * Identity key for the inventory at {@code inventoryPos}.
     *
     * <p>Safe to use in a {@link java.util.HashSet}: bobbychests shared lists compare by
     * reference, block positions by value.
     */
    public static Object of(ServerLevel level, BlockPos inventoryPos) {
        if (!level.hasChunkAt(inventoryPos)) {
            return inventoryPos.immutable();
        }
        BlockEntity be = level.getBlockEntity(inventoryPos);
        if (be != null) {
            Object shared = BobbyChestsCompat.sharedItemsIdentity(be);
            if (shared != null) {
                return shared;
            }
        }
        return inventoryPos.immutable();
    }

    /**
     * Identity-hash key for a shared backing store (e.g. a GLOBAL chest item list).
     *
     * <p>Package-visible for tests.
     */
    public static Object refKey(Object sharedStore) {
        return new RefKey(sharedStore);
    }

    private record RefKey(Object ref) {
        private RefKey {
            if (ref == null) {
                throw new NullPointerException("ref");
            }
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof RefKey key && ref == key.ref;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(ref);
        }
    }
}

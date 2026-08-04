package com.bobby.bobbypipes.compat;

import com.bobby.bobbypipes.network.StorageIdentities;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;

/**
 * Soft bridge to BobbyChests without a compile-time dependency.
 *
 * <p>Networked chests expose the same {@code getActiveItems()} list across every block on a
 * channel; that list instance is the storage identity pipes should dedupe on.
 */
public final class BobbyChestsCompat {

    private static final String CHEST_PREFIX = "com.bobby.bobbychests.";
    private static final Method GET_ACTIVE_ITEMS = findActiveItems();

    private BobbyChestsCompat() {
    }

    /**
     * Identity for a BobbyChests shared item list, or {@code null} when {@code be} is not a
     * BobbyChests chest / the soft bridge is unavailable.
     */
    public static Object sharedItemsIdentity(BlockEntity be) {
        if (be == null || GET_ACTIVE_ITEMS == null) {
            return null;
        }
        if (!be.getClass().getName().startsWith(CHEST_PREFIX)) {
            return null;
        }
        try {
            Object items = GET_ACTIVE_ITEMS.invoke(be);
            return items == null ? null : StorageIdentities.refKey(items);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Method findActiveItems() {
        try {
            Class<?> type = Class.forName(
                    "com.bobby.bobbychests.chest.blockentity.AbstractTieredChestBlockEntity");
            Method method = type.getMethod("getActiveItems");
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}

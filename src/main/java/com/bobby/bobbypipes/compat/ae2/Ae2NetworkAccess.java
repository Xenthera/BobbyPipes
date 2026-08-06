package com.bobby.bobbypipes.compat.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import com.bobby.bobbypipes.compat.digital.DigitalNetworkStore;
import com.bobby.bobbypipes.network.StorageIdentities;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * AE2 grid access for provider pipes (full ME network via an Interface node).
 *
 * <p>Uses {@link GridHelper#getExposedNode} so both the Interface block and a cable
 * Interface part facing the pipe work. Only Interface-owned nodes are accepted so a
 * Provider on a storage bus / other part does not silently drain the whole network.
 */
final class Ae2NetworkAccess {

    private Ae2NetworkAccess() {
    }

    static Optional<DigitalNetworkStore> tryAttach(ServerLevel level,
                                                   BlockPos neighbour,
                                                   Direction sideFacingPipe) {
        IGridNode node = GridHelper.getExposedNode(level, neighbour, sideFacingPipe);
        if (node == null || !node.hasGridBooted() || !node.isActive()) {
            return Optional.empty();
        }
        if (!isInterfaceOwner(node.getOwner())) {
            return Optional.empty();
        }
        IGrid grid = node.getGrid();
        if (grid == null) {
            return Optional.empty();
        }
        IStorageService storage = grid.getStorageService();
        return Optional.of(new Store(grid, storage));
    }

    /**
     * Interface block entity, cable Interface part, or their logic host - detected by
     * class name so we stay on the public API jar (Interface* types are impl-only).
     */
    private static boolean isInterfaceOwner(Object owner) {
        if (owner == null) {
            return false;
        }
        String name = owner.getClass().getName();
        return name.contains("Interface");
    }

    private static int clamp(long amount) {
        if (amount <= 0L) {
            return 0;
        }
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    private record Store(IGrid grid, IStorageService storage) implements DigitalNetworkStore {

        @Override
        public Object identity() {
            return StorageIdentities.refKey(grid);
        }

        @Override
        public Map<ItemResource, Integer> summarize() {
            Map<ItemResource, Integer> out = new LinkedHashMap<>();
            KeyCounter stacks = storage.getCachedInventory();
            for (Object2LongMap.Entry<AEKey> entry : stacks) {
                if (!(entry.getKey() instanceof AEItemKey itemKey)) {
                    continue;
                }
                int amount = clamp(entry.getLongValue());
                if (amount <= 0) {
                    continue;
                }
                out.merge(itemKey.toResource(), amount, (a, b) -> clamp((long) a + b));
            }
            return out;
        }

        @Override
        public int count(ItemResource item) {
            if (item.isEmpty()) {
                return 0;
            }
            AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                return 0;
            }
            long cached = storage.getCachedInventory().get(key);
            if (cached > 0L) {
                return clamp(cached);
            }
            MEStorage inventory = storage.getInventory();
            return clamp(inventory.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, IActionSource.empty()));
        }

        @Override
        public int extract(ItemResource item, int amount) {
            if (item.isEmpty() || amount <= 0) {
                return 0;
            }
            AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                return 0;
            }
            MEStorage inventory = storage.getInventory();
            return clamp(inventory.extract(key, amount, Actionable.MODULATE, IActionSource.empty()));
        }
    }
}

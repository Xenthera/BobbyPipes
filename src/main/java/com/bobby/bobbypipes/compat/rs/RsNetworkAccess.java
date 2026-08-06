package com.bobby.bobbypipes.compat.rs;

import com.bobby.bobbypipes.compat.digital.DigitalNetworkStore;
import com.bobby.bobbypipes.network.StorageIdentities;
import com.refinedmods.refinedstorage.api.core.Action;
import com.refinedmods.refinedstorage.api.network.Network;
import com.refinedmods.refinedstorage.api.network.storage.StorageNetworkComponent;
import com.refinedmods.refinedstorage.api.resource.ResourceAmount;
import com.refinedmods.refinedstorage.api.resource.ResourceKey;
import com.refinedmods.refinedstorage.api.storage.Actor;
import com.refinedmods.refinedstorage.common.iface.InterfaceBlockEntity;
import com.refinedmods.refinedstorage.neoforge.support.resource.VariantUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Refined Storage network access for provider pipes (full network via an Interface).
 *
 * <p>{@code Capabilities.Item.BLOCK} on the Interface is only the export buffer; this
 * path uses {@link StorageNetworkComponent} instead.
 */
final class RsNetworkAccess {

    private RsNetworkAccess() {
    }

    static Optional<DigitalNetworkStore> tryAttach(ServerLevel level,
                                                   BlockPos neighbour,
                                                   Direction sideFacingPipe) {
        BlockEntity be = level.getBlockEntity(neighbour);
        if (!(be instanceof InterfaceBlockEntity iface)) {
            return Optional.empty();
        }
        Network network = iface.getNetworkForItem();
        if (network == null) {
            return Optional.empty();
        }
        StorageNetworkComponent storage = network.getComponent(StorageNetworkComponent.class);
        if (storage == null) {
            return Optional.empty();
        }
        return Optional.of(new Store(network, storage));
    }

    private static int clamp(long amount) {
        if (amount <= 0L) {
            return 0;
        }
        return amount > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) amount;
    }

    private record Store(Network network, StorageNetworkComponent storage)
            implements DigitalNetworkStore {

        @Override
        public Object identity() {
            return StorageIdentities.refKey(network);
        }

        @Override
        public Map<ItemResource, Integer> summarize() {
            Map<ItemResource, Integer> out = new LinkedHashMap<>();
            for (ResourceAmount amount : storage.getAll()) {
                ItemResource item = VariantUtil.optionalItemToPlatform(amount.resource());
                if (item == null || item.isEmpty()) {
                    continue;
                }
                int capped = clamp(amount.amount());
                if (capped <= 0) {
                    continue;
                }
                out.merge(item, capped, (a, b) -> clamp((long) a + b));
            }
            return out;
        }

        @Override
        public int count(ItemResource item) {
            if (item.isEmpty()) {
                return 0;
            }
            ResourceKey key = VariantUtil.ofPlatform(item);
            return clamp(storage.get(key));
        }

        @Override
        public int extract(ItemResource item, int amount) {
            if (item.isEmpty() || amount <= 0) {
                return 0;
            }
            ResourceKey key = VariantUtil.ofPlatform(item);
            return clamp(storage.extract(key, amount, Action.EXECUTE, Actor.EMPTY));
        }
    }
}

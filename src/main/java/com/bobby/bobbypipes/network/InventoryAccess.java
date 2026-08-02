package com.bobby.bobbypipes.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * Reads and moves items in the inventories touching a pipe.
 *
 * <p>All the capability and transaction handling lives here so the rest of the network
 * layer deals in plain counts.
 */
public final class InventoryAccess {

    private InventoryAccess() {
    }

    /** Total count of {@code item} in inventories touching {@code pipe}. */
    public static int count(ServerLevel level, BlockPos pipe, ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Direction direction : Direction.values()) {
            ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.size(); slot++) {
                if (item.equals(handler.getResource(slot))) {
                    total += handler.getAmountAsInt(slot);
                }
            }
        }
        return total;
    }

    /**
     * Takes up to {@code wanted} of {@code item} out of inventories touching {@code pipe}.
     *
     * <p>Committed immediately. Extracting less than asked for is normal and the caller
     * must use the returned count rather than assuming it got everything, otherwise items
     * would be conjured out of nothing.
     *
     * @return how many were actually removed
     */
    public static int extract(ServerLevel level, BlockPos pipe, ItemResource item, int wanted) {
        if (item.isEmpty() || wanted <= 0) {
            return 0;
        }
        int taken = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (taken >= wanted) {
                    break;
                }
                ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                taken += handler.extract(item, wanted - taken, transaction);
            }
            transaction.commit();
        }
        return taken;
    }

    /**
     * Puts up to {@code count} of {@code item} into inventories touching {@code pipe}.
     *
     * @return how many were accepted, which may be zero if everything nearby is full
     */
    public static int insert(ServerLevel level, BlockPos pipe, ItemResource item, int count) {
        if (item.isEmpty() || count <= 0) {
            return 0;
        }
        int placed = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (placed >= count) {
                    break;
                }
                ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                placed += handler.insert(item, count - placed, transaction);
            }
            transaction.commit();
        }
        return placed;
    }

    /**
     * Puts items into an inventory touching {@code pipe}, spilling the remainder into the
     * world.
     *
     * <p>Items are never destroyed. A full destination or a broken pipe means they end up
     * on the floor, which a player can recover, rather than quietly disappearing.
     */
    public static void insertOrDrop(ServerLevel level, BlockPos pipe, ItemResource item, int count) {
        int placed = insert(level, pipe, item, count);
        int leftover = count - placed;
        while (leftover > 0) {
            int stackSize = Math.min(leftover, item.toStack(1).getMaxStackSize());
            Block.popResource(level, pipe, item.toStack(stackSize));
            leftover -= stackSize;
        }
    }

    private static ResourceHandler<ItemResource> handlerAt(ServerLevel level, BlockPos pipe,
                                                           Direction direction) {
        BlockPos neighbour = pipe.relative(direction);
        if (!level.hasChunkAt(neighbour)) {
            return null;
        }
        return level.getCapability(Capabilities.Item.BLOCK, neighbour, direction.getOpposite());
    }
}

package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads and moves items in the inventories touching a pipe.
 *
 * <p>All the capability and transaction handling lives here so the rest of the network
 * layer deals in plain counts.
 */
public final class InventoryAccess {

    private InventoryAccess() {
    }

    /**
     * Merged contents of inventories touching {@code pipe}, keyed by exact item identity.
     *
     * <p>Empty slots are skipped. Order follows face then slot scan order; callers that need
     * a stable browse order should sort the result themselves.
     */
    public static Map<ItemResource, Integer> summarize(ServerLevel level, BlockPos pipe) {
        return summarizeUnclaimed(level, pipe, new HashSet<>());
    }

    /**
     * Like {@link #summarize}, but skips any attached storage whose identity is already in
     * {@code claimed} and records newly seen identities there.
     *
     * <p>Used by network stock aggregation so two providers on the same BobbyChests channel
     * (or the same physical chest) only contribute once.
     */
    public static Map<ItemResource, Integer> summarizeUnclaimed(ServerLevel level,
                                                                BlockPos pipe,
                                                                Set<Object> claimed) {
        Map<ItemResource, Integer> totals = new LinkedHashMap<>();
        forEachUnclaimed(level, pipe, claimed, (handler, ignored) -> {
            for (int slot = 0; slot < handler.size(); slot++) {
                ItemResource resource = handler.getResource(slot);
                if (resource.isEmpty()) {
                    continue;
                }
                int amount = handler.getAmountAsInt(slot);
                if (amount > 0) {
                    totals.merge(resource, amount, Integer::sum);
                }
            }
        });
        return totals;
    }

    /** Total count of {@code item} in inventories touching {@code pipe}. */
    public static int count(ServerLevel level, BlockPos pipe, ItemResource item) {
        return countUnclaimed(level, pipe, item, new HashSet<>());
    }

    /**
     * Like {@link #count}, claiming storage identities into {@code claimed} so siblings on
     * the same shared store are not counted again.
     */
    public static int countUnclaimed(ServerLevel level,
                                     BlockPos pipe,
                                     ItemResource item,
                                     Set<Object> claimed) {
        if (item.isEmpty()) {
            return 0;
        }
        int[] total = {0};
        forEachUnclaimed(level, pipe, claimed, (handler, ignored) -> {
            for (int slot = 0; slot < handler.size(); slot++) {
                if (item.equals(handler.getResource(slot))) {
                    total[0] += handler.getAmountAsInt(slot);
                }
            }
        });
        return total[0];
    }

    @FunctionalInterface
    interface HandlerVisitor {
        void visit(ResourceHandler<ItemResource> handler, Object identity);
    }

    /**
     * Visits each distinct storage touching {@code pipe} that is not already in
     * {@code claimed}, then adds its identity to {@code claimed}.
     */
    static void forEachUnclaimed(ServerLevel level,
                                 BlockPos pipe,
                                 Set<Object> claimed,
                                 HandlerVisitor visitor) {
        forEachUnclaimed(level, pipe, claimed, Set.of(), visitor);
    }

    /**
     * As {@link #forEachUnclaimed(ServerLevel, BlockPos, Set, HandlerVisitor)}, skipping
     * neighbours in {@code skipPositions} (e.g. AE2/RS Interfaces already offered as a
     * full digital network so their export buffer is not also counted).
     */
    static void forEachUnclaimed(ServerLevel level,
                                 BlockPos pipe,
                                 Set<Object> claimed,
                                 Set<BlockPos> skipPositions,
                                 HandlerVisitor visitor) {
        Set<Object> seenOnPipe = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pipe.relative(direction);
            if (skipPositions.contains(neighbour)) {
                continue;
            }
            ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            Object identity = StorageIdentities.of(level, neighbour);
            // Same chest on two faces of one pipe, or already offered by a nearer provider.
            if (!seenOnPipe.add(identity) || !claimed.add(identity)) {
                continue;
            }
            visitor.visit(handler, identity);
        }
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
    /**
     * Puts items into a neighbouring container, ignoring one side, spilling the rest.
     *
     * <p>The ignored side is where the item came from. Ejecting back into the hopper that
     * fed the pipe makes the hopper push it straight back in, so the item bounces in and
     * out forever instead of being put down.
     */
    public static void insertOrDropExcluding(ServerLevel level, BlockPos pipe, ItemResource item,
                                             int count, Direction excluded) {
        if (item.isEmpty() || count <= 0) {
            return;
        }
        int placed = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (placed >= count || direction == excluded) {
                    continue;
                }
                ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                placed += handler.insert(item, count - placed, transaction);
            }
            transaction.commit();
        }
        int leftover = count - placed;
        while (leftover > 0) {
            int stackSize = Math.min(leftover, item.toStack(1).getMaxStackSize());
            Block.popResource(level, pipe, item.toStack(stackSize));
            leftover -= stackSize;
        }
    }

    /**
     * Puts items into the container on one specific side of {@code pipe}.
     *
     * <p>Used once an item has finished travelling down that arm, so it lands in the
     * container it was animated into rather than whichever neighbour happens to be first.
     *
     * @return how many were accepted
     */
    public static int insertTo(ServerLevel level, BlockPos pipe, Direction side,
                               ItemResource item, int count) {
        if (item.isEmpty() || count <= 0) {
            return 0;
        }
        ResourceHandler<ItemResource> handler = handlerAt(level, pipe, side);
        if (handler == null) {
            return 0;
        }
        int placed;
        try (Transaction transaction = Transaction.openRoot()) {
            placed = handler.insert(item, count, transaction);
            transaction.commit();
        }
        return placed;
    }

    public static void insertOrDrop(ServerLevel level, BlockPos pipe, ItemResource item, int count) {
        int placed = insert(level, pipe, item, count);
        drop(level, pipe, item, count - placed);
    }

    /** Spills {@code count} of {@code item} into the world at {@code pipe}. */
    public static void drop(ServerLevel level, BlockPos pipe, ItemResource item, int count) {
        if (item.isEmpty() || count <= 0) {
            return;
        }
        int leftover = count;
        while (leftover > 0) {
            int stackSize = Math.min(leftover, item.toStack(1).getMaxStackSize());
            Block.popResource(level, pipe, item.toStack(stackSize));
            leftover -= stackSize;
        }
    }

    /** Whether any attached inventory could accept at least one of {@code item}. */
    /**
     * Whether the block on one specific side of {@code pipe} is a container at all.
     *
     * <p>Used to classify a drifting item's options. An empty {@code item} asks only "is
     * there an inventory here", which is what a plain pipe needs to know before deciding
     * whether turning that way means putting the item away.
     */
    public static boolean canInsertFrom(ServerLevel level, BlockPos pipe, Direction side,
                                        ItemResource item) {
        ResourceHandler<ItemResource> handler = handlerAt(level, pipe, side);
        if (handler == null) {
            return false;
        }
        if (item.isEmpty()) {
            return handler.size() > 0;
        }
        try (Transaction probe = Transaction.openRoot()) {
            return handler.insert(item, 1, probe) > 0;
        }
    }

    public static boolean canInsert(ServerLevel level, BlockPos pipe, ItemResource item) {
        return insertable(level, pipe, item, 1) > 0;
    }

    /**
     * How many of {@code item} attached inventories could accept right now.
     *
     * <p>Uses a rollback transaction so nothing is actually moved.
     */
    public static int insertable(ServerLevel level, BlockPos pipe, ItemResource item, int wanted) {
        if (item.isEmpty() || wanted <= 0) {
            return 0;
        }
        int accepted = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (accepted >= wanted) {
                    break;
                }
                ResourceHandler<ItemResource> handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                accepted += handler.insert(item, wanted - accepted, transaction);
            }
            // Roll back - this is a capacity probe only.
        }
        return accepted;
    }

    /**
     * The side of {@code pipe} an inventory sits on, if any.
     *
     * <p>Used to draw a parcel entering and leaving through the pipe arm that actually
     * touches the container, rather than winking in and out at the pipe centre.
     */
    public static java.util.Optional<Direction> inventorySide(ServerLevel level, BlockPos pipe) {
        for (Direction direction : Direction.values()) {
            if (handlerAt(level, pipe, direction) != null) {
                return java.util.Optional.of(direction);
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * Storage identities of every inventory touching {@code pipe}.
     *
     * <p>Supplier pipes pass this into {@link NetworkSupply} so restock requests cannot
     * source from the chest they are trying to fill.
     */
    public static Set<Object> attachedIdentities(ServerLevel level, BlockPos pipe) {
        Set<Object> identities = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pipe.relative(direction);
            if (handlerAt(level, pipe, direction) == null) {
                continue;
            }
            identities.add(StorageIdentities.of(level, neighbour));
        }
        return identities;
    }

    /**
     * The side of {@code pipe} holding {@code item}, if any.
     *
     * <p>Call this before extracting. A pipe can touch several containers, and the first
     * one that happens to exist is not the one the item necessarily comes out of, so
     * picking by contents is what makes the departure arm match where the item was.
     */
    public static java.util.Optional<Direction> sideHolding(ServerLevel level,
                                                            BlockPos pipe,
                                                            ItemResource item) {
        if (item.isEmpty()) {
            return java.util.Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            if (countIn(handlerAt(level, pipe, direction), item) > 0) {
                return java.util.Optional.of(direction);
            }
        }
        return java.util.Optional.empty();
    }

    /** The side of {@code pipe} that would take {@code item} right now, if any. */
    public static java.util.Optional<Direction> sideAccepting(ServerLevel level,
                                                              BlockPos pipe,
                                                              ItemResource item) {
        for (Direction direction : Direction.values()) {
            if (canInsertFrom(level, pipe, direction, item)) {
                return java.util.Optional.of(direction);
            }
        }
        return inventorySide(level, pipe);
    }

    /**
     * True when a neighbour holds {@code item} but only exposes it on a face this pipe is
     * not touching.
     *
     * <p>Sided machines are the usual cause of a craft that looks stuck for no reason. A
     * vanilla furnace exposes its result slot on its bottom face only, so a crafting pipe
     * on the side can see the fuel slot and nothing else. This distinguishes "not made
     * yet" from "made, but this pipe cannot reach it".
     */
    public static boolean visibleOnlyFromAnotherFace(ServerLevel level,
                                                     BlockPos pipe,
                                                     ItemResource item) {
        if (item.isEmpty()) {
            return false;
        }
        for (Direction toNeighbour : Direction.values()) {
            BlockPos neighbour = pipe.relative(toNeighbour);
            if (!level.hasChunkAt(neighbour)) {
                continue;
            }
            Direction touching = toNeighbour.getOpposite();
            if (countIn(level.getCapability(Capabilities.Item.BLOCK, neighbour, touching), item) > 0) {
                continue;
            }
            for (Direction face : Direction.values()) {
                if (face == touching) {
                    continue;
                }
                if (countIn(level.getCapability(Capabilities.Item.BLOCK, neighbour, face), item) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int countIn(ResourceHandler<ItemResource> handler, ItemResource item) {
        if (handler == null) {
            return 0;
        }
        int total = 0;
        for (int slot = 0; slot < handler.size(); slot++) {
            if (item.equals(handler.getResource(slot))) {
                total += handler.getAmountAsInt(slot);
            }
        }
        return total;
    }

    static ResourceHandler<ItemResource> handlerAt(ServerLevel level, BlockPos pipe,
                                                   Direction direction) {
        BlockPos neighbour = pipe.relative(direction);
        if (!level.hasChunkAt(neighbour)) {
            return null;
        }
        // Plain/basic pipes expose insert-only intakes for hoppers. Treating those as
        // destination inventories makes arrivals re-enter the network and default-route
        // instead of landing in the attached chest.
        if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Item.BLOCK, neighbour, direction.getOpposite());
    }
}

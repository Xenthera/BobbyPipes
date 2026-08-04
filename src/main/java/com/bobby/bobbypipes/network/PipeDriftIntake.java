package com.bobby.bobbypipes.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;

/**
 * Lets anything push items into a plain pipe.
 *
 * <p>Exposed on plain pipes so a hopper, dropper, or another mod's tube can feed the dumb
 * transport. Insert only: extraction is refused, because a hopper underneath a pipe should
 * not be able to drain items out of a network.
 *
 * <p>There is no block entity behind this, and no buffer. Accepting an item spawns a
 * drifting item straight into the level's {@link DriftTracker}, which is only safe because
 * the spawn is deferred to {@link SnapshotJournal#onRootCommit}. Doing it inside
 * {@code insert} would put the item on the network even when the surrounding transaction
 * was later aborted, duplicating it.
 */
public final class PipeDriftIntake implements ResourceHandler<ItemResource> {

    /** How much may be accepted in one transaction, so a hopper feeds in stack sized bites. */
    private static final int CAPACITY = 64;

    private final ServerLevel level;
    private final BlockPos pos;
    private final Direction side;

    private ItemResource pending = ItemResource.EMPTY;
    private int pendingCount;

    private final SnapshotJournal<Integer> journal = new SnapshotJournal<>() {
        @Override
        protected Integer createSnapshot() {
            return pendingCount;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            pendingCount = snapshot;
            if (pendingCount <= 0) {
                pending = ItemResource.EMPTY;
            }
        }

        @Override
        protected void onRootCommit(Integer snapshot) {
            if (!pending.isEmpty() && pendingCount > 0) {
                DriftTracker drift = PipeNetwork.get(level).drift();
                drift.insert(pos, pending, pendingCount, side);
            }
            pending = ItemResource.EMPTY;
            pendingCount = 0;
        }
    };

    public PipeDriftIntake(ServerLevel level, BlockPos pos, Direction side) {
        this.level = level;
        this.pos = pos.immutable();
        this.side = side;
    }

    @Override
    public int size() {
        return 1;
    }

    @Override
    public ItemResource getResource(int index) {
        return pending;
    }

    @Override
    public long getAmountAsLong(int index) {
        return pendingCount;
    }

    @Override
    public long getCapacityAsLong(int index, ItemResource resource) {
        return CAPACITY;
    }

    @Override
    public boolean isValid(int index, ItemResource resource) {
        return !resource.isEmpty();
    }

    @Override
    public int insert(int index, ItemResource resource, int amount, TransactionContext transaction) {
        if (resource.isEmpty() || amount <= 0) {
            return 0;
        }
        if (!pending.isEmpty() && !pending.equals(resource)) {
            return 0;
        }
        int accepted = Math.min(amount, CAPACITY - pendingCount);
        if (accepted <= 0) {
            return 0;
        }
        journal.updateSnapshots(transaction);
        pending = resource;
        pendingCount += accepted;
        return accepted;
    }

    @Override
    public int extract(int index, ItemResource resource, int amount, TransactionContext transaction) {
        // A pipe is not storage. Allowing extraction would make any hopper a siphon.
        return 0;
    }
}

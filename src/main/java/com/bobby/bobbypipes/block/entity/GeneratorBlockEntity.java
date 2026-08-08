package com.bobby.bobbypipes.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * Shared FE buffer for blocks that make energy rather than move it.
 *
 * <p>The exposed handler is extract-only. A generator is a source: letting something insert
 * into it would make it a battery that quietly launders energy from elsewhere, and the
 * Power Junction already exists to be the buffer.
 *
 * <p>Energy is pushed to adjacent acceptors every tick as well as being exposed for pulling.
 * Pushing is what makes a generator useful sitting directly against a Power Junction with no
 * pipes at all, which matters when the network needs power before it can route anything.
 */
public abstract class GeneratorBlockEntity extends BlockEntity {

    private int energy;
    private final EnergyHandler exposed = new OutputHandler();
    private final BufferJournal journal = new BufferJournal();

    protected GeneratorBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /** Total FE this generator can hold. */
    public abstract int capacity();

    /** Most FE this generator will hand to one neighbour per tick. */
    protected abstract int transferPerTick();

    public int energy() {
        return energy;
    }

    public EnergyHandler energyHandler(@Nullable Direction side) {
        return exposed;
    }

    /**
     * Adds freshly generated FE, clamped to the free space.
     *
     * @return how much was actually stored, which is less than {@code amount} when full
     */
    protected int generate(int amount) {
        if (amount <= 0) {
            return 0;
        }
        int stored = Math.min(amount, capacity() - energy);
        if (stored > 0) {
            energy += stored;
            setChanged();
        }
        return stored;
    }

    /** True while the buffer has no room, so a generator can idle instead of burning fuel. */
    protected boolean isFull() {
        return energy >= capacity();
    }

    /**
     * Offers energy to every adjacent block that accepts it.
     *
     * <p>Deliberately not routed through the pipe network: this is a machine handing power to
     * whatever is touching it, the same as any other mod's generator.
     */
    protected void pushToNeighbours(Level level, BlockPos pos) {
        if (energy <= 0) {
            return;
        }
        for (Direction direction : Direction.values()) {
            if (energy <= 0) {
                return;
            }
            BlockPos neighbour = pos.relative(direction);
            if (!level.hasChunkAt(neighbour)) {
                continue;
            }
            EnergyHandler target =
                    level.getCapability(Capabilities.Energy.BLOCK, neighbour, direction.getOpposite());
            if (target == null) {
                continue;
            }
            int offer = Math.min(transferPerTick(), energy);
            if (offer <= 0) {
                continue;
            }
            try (Transaction tx = Transaction.openRoot()) {
                int accepted = target.insert(offer, tx);
                if (accepted > 0) {
                    energy -= accepted;
                    tx.commit();
                    setChanged();
                }
            }
        }
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("energy", energy);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy = Math.max(0, input.getIntOr("energy", 0));
    }

    /** Extract-only view for neighbours and pipes. */
    private final class OutputHandler implements EnergyHandler {
        @Override
        public long getAmountAsLong() {
            return energy;
        }

        @Override
        public long getCapacityAsLong() {
            return capacity();
        }

        @Override
        public int insert(int amount, TransactionContext tx) {
            TransferPreconditions.checkNonNegative(amount);
            // A generator is a source, not a battery.
            return 0;
        }

        @Override
        public int extract(int amount, TransactionContext tx) {
            TransferPreconditions.checkNonNegative(amount);
            int taken = Math.min(amount, energy);
            if (taken <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            energy -= taken;
            return taken;
        }
    }

    private final class BufferJournal extends SnapshotJournal<Integer> {
        @Override
        protected Integer createSnapshot() {
            return energy;
        }

        @Override
        protected void revertToSnapshot(Integer snapshot) {
            energy = snapshot;
        }

        @Override
        protected void onRootCommit(Integer snapshot) {
            setChanged();
        }
    }
}

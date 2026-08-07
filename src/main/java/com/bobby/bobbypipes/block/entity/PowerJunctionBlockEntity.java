package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.PowerJunctionMenu;
import com.bobby.bobbypipes.network.power.LogisticsPowerCosts;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.TransferPreconditions;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.SnapshotJournal;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

/**
 * FE buffer that supplies logistics power to the adjacent pipe component.
 */
public class PowerJunctionBlockEntity extends BlockEntity implements MenuProvider {

    private int energy;
    private int lastTickInserted;
    private int lastTickExtracted;
    private final EnergyHandler handler = new BufferHandler();
    private final BufferJournal journal = new BufferJournal();

    public PowerJunctionBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.POWER_JUNCTION.get(), pos, state);
    }

    public EnergyHandler energyHandler(@Nullable Direction side) {
        return handler;
    }

    public int energy() {
        return energy;
    }

    public int capacity() {
        return LogisticsPowerCosts.CAPACITY;
    }

    public int lastTickInserted() {
        return lastTickInserted;
    }

    public int lastTickExtracted() {
        return lastTickExtracted;
    }

    public void beginTickMeters() {
        // no-op retained for ticker wiring; meters clear after sampler flush
    }

    public void clearTickMeters() {
        lastTickInserted = 0;
        lastTickExtracted = 0;
    }

    /**
     * Extracts {@code amount} FE for a logistics spend. Returns true only if the full
     * amount was taken.
     */
    public boolean tryExtractExact(int amount) {
        if (amount <= 0) {
            return true;
        }
        if (energy < amount) {
            return false;
        }
        try (Transaction tx = Transaction.openRoot()) {
            int taken = handler.extract(amount, tx);
            if (taken < amount) {
                return false;
            }
            tx.commit();
        }
        lastTickExtracted += amount;
        setChanged();
        return true;
    }

    public boolean canExtractExact(int amount) {
        return amount <= 0 || energy >= amount;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.bobbypipes.power_junction");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new PowerJunctionMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("Energy", energy);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        energy = Math.clamp(input.getIntOr("Energy", 0), 0, capacity());
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private final class BufferHandler implements EnergyHandler {
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
            int space = capacity() - energy;
            int accepted = Math.min(amount, space);
            if (accepted <= 0) {
                return 0;
            }
            journal.updateSnapshots(tx);
            energy += accepted;
            lastTickInserted += accepted;
            return accepted;
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

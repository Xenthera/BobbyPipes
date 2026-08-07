package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.FluidSupplierPipeMenu;
import com.bobby.bobbypipes.network.FluidAccess;
import com.bobby.bobbypipes.network.FluidRequestService;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the attached tank topped up to a configured fluid and amount by requesting the
 * shortfall from the network. The fluid equivalent of {@link EnergySupplierPipeBlockEntity},
 * with a fluid identity alongside the target amount since (unlike energy) there is more
 * than one kind of fluid.
 */
public class FluidSupplierPipeBlockEntity extends BlockEntity implements MenuProvider {

    /** How often to scan for shortfall, same cadence as every other Supplier. */
    public static final int TICK_INTERVAL = 20;

    private FluidResource targetFluid = FluidResource.EMPTY;
    private int targetMb;

    public FluidSupplierPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FLUID_SUPPLIER_PIPE.get(), pos, state);
    }

    public FluidResource targetFluid() {
        return targetFluid;
    }

    public int targetMb() {
        return targetMb;
    }

    public void setTarget(FluidResource fluid, int amountMb) {
        this.targetFluid = fluid == null ? FluidResource.EMPTY : fluid;
        this.targetMb = Math.max(0, amountMb);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FluidSupplierPipeBlockEntity pipe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }
        pipe.restock(serverLevel);
    }

    private void restock(ServerLevel level) {
        if (targetFluid.isEmpty() || targetMb <= 0) {
            return;
        }
        PipeNetwork network = PipeNetwork.get(level);
        if (!network.routes().contains(worldPosition)
                && !network.rebuildNow(worldPosition).contains(worldPosition)) {
            return;
        }
        // Shortfall against the target, counting fluid already on its way as if it had
        // arrived. Same reasoning as the energy Supplier: request()'s inbound figure only
        // bounds what the destination tank can hold, and it probes for inbound + ask, so
        // relying on it meant re-ordering the whole target on every scan while parcels were
        // still in transit and overfilling the tank far past the number that was set.
        int need = targetMb
 - FluidAccess.count(level, worldPosition, targetFluid)
 - network.fluidSendQueue().queuedTo(worldPosition, targetFluid)
 - network.fluidLedger().inbound(worldPosition, targetFluid);
        if (need <= 0) {
            return;
        }
        if (!network.power().trySpend(worldPosition,
                com.bobby.bobbypipes.network.power.PowerSpendKind.FLUID_SUPPLIER)) {
            return;
        }
        FluidRequestService.request(level, network, worldPosition, targetFluid, need, false);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.fluid_supplier_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new FluidSupplierPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("target_fluid", FluidResource.CODEC, targetFluid);
        output.store("target_mb", com.mojang.serialization.Codec.INT, targetMb);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        targetFluid = input.read("target_fluid", FluidResource.CODEC).orElse(FluidResource.EMPTY);
        targetMb = input.read("target_mb", com.mojang.serialization.Codec.INT).orElse(0);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

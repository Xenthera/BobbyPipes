package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.EnergySupplierPipeMenu;
import com.bobby.bobbypipes.logistics.EnergyAccess;
import com.bobby.bobbypipes.logistics.EnergyKind;
import com.bobby.bobbypipes.logistics.EnergyRequestService;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the attached energy storage topped up to a configured FE target by requesting the
 * shortfall from the network.
 *
 * <p>The energy equivalent of {@link SupplierPipeBlockEntity}, but with a single target
 * amount instead of a row of ghost item slots, there is only one kind of energy to target.
 */
public class EnergySupplierPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    /** How often to scan for shortfall (1 second at 20 tps), same cadence as item Supplier. */
    public static final int TICK_INTERVAL = 20;

    private int targetFe;

    public EnergySupplierPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.ENERGY_SUPPLIER_PIPE.get(), pos, state);
    }

    public int targetFe() {
        return targetFe;
    }

    public void setTargetFe(int targetFe) {
        this.targetFe = Math.max(0, targetFe);
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  EnergySupplierPipeBlockEntity pipe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }
        pipe.restock(serverLevel);
    }

    private void restock(ServerLevel level) {
        if (targetFe <= 0) {
            return;
        }
        PipeNetwork network = PipeNetwork.get(level);
        // Live snapshot, not a forced rebuild every second - same reasoning as the item
        // Supplier: republishing the graph on a 20-tick cadence hitched every parcel.
        if (!network.routes().contains(worldPosition)
                && !network.rebuildNow(worldPosition).contains(worldPosition)) {
            return;
        }
        // Shortfall against the target, counting energy already on its way as if it had
        // arrived. request() cannot do this for us: its own inbound figure only limits how
        // much the destination buffer can physically hold, and it probes for inbound + ask,
        // so the two cancel and every scan would ask for the full shortfall again. On a big
        // battery that meant re-ordering the target once a second for as long as parcels
        // were in transit, charging the storage far past the number that was set.
        int need = targetFe
 - EnergyAccess.count(level, worldPosition)
 - network.energySendQueue().queuedTo(worldPosition)
 - network.energyLedger().inbound(worldPosition, EnergyKind.ENERGY);
        if (need <= 0) {
            return;
        }
        if (!network.power().trySpend(worldPosition,
                com.bobby.bobbypipes.logistics.power.PowerSpendKind.ENERGY_SUPPLIER)) {
            return;
        }
        EnergyRequestService.request(level, network, worldPosition, need, false);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.energy_supplier_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new EnergySupplierPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("target_fe", Codec.INT, targetFe);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        targetFe = input.read("target_fe", Codec.INT).orElse(0);
    }
}

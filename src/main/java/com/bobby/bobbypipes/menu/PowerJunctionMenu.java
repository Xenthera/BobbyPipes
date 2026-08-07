package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.PowerJunctionBlockEntity;
import com.bobby.bobbypipes.network.payload.PowerJunctionSyncPayload;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;

public class PowerJunctionMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private int energy;
    private int capacity;
    private boolean powered;
    private int windowIn;
    private int windowOut;
    private List<PowerJunctionSyncPayload.Frame> frames = List.of();

    public PowerJunctionMenu(int id, Inventory inventory, PowerJunctionBlockEntity junction) {
        super(ModMenus.POWER_JUNCTION.get(), id);
        this.pos = junction.getBlockPos().immutable();
        this.energy = junction.energy();
        this.capacity = junction.capacity();
        this.powered = true;
    }

    public PowerJunctionMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.POWER_JUNCTION.get(), id);
        this.pos = buf.readBlockPos();
        this.energy = buf.readVarInt();
        this.capacity = buf.readVarInt();
        this.powered = true;
    }

    public BlockPos pos() {
        return pos;
    }

    public int energy() {
        return energy;
    }

    public int capacity() {
        return capacity;
    }

    public boolean powered() {
        return powered;
    }

    public int windowIn() {
        return windowIn;
    }

    public int windowOut() {
        return windowOut;
    }

    public List<PowerJunctionSyncPayload.Frame> frames() {
        return frames;
    }

    public void applySync(PowerJunctionSyncPayload payload) {
        if (!payload.pos().equals(pos)) {
            return;
        }
        this.energy = payload.energy();
        this.capacity = payload.capacity();
        this.powered = payload.powered();
        this.windowIn = payload.windowIn();
        this.windowOut = payload.windowOut();
        this.frames = List.copyOf(payload.frames());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

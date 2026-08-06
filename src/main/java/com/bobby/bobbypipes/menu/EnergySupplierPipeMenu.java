package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.EnergySupplierPipeBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu for the energy supplier pipe screen: pipe position plus the target FE it
 * was opened with, same shape as {@link BasicPipeMenu}.
 */
public class EnergySupplierPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private int targetFe;

    public EnergySupplierPipeMenu(int id, Inventory inventory, EnergySupplierPipeBlockEntity pipe) {
        super(ModMenus.ENERGY_SUPPLIER_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.targetFe = pipe.targetFe();
    }

    public EnergySupplierPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.ENERGY_SUPPLIER_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.targetFe = buf.readVarInt();
    }

    public BlockPos pos() {
        return pos;
    }

    public int targetFe() {
        return targetFe;
    }

    public void setTargetFeLocal(int targetFe) {
        this.targetFe = targetFe;
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

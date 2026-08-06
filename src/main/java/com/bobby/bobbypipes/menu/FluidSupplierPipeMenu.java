package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.FluidSupplierPipeBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Slotless menu for the fluid supplier pipe screen: pipe position, target fluid, and
 * target amount it was opened with, same shape as {@link EnergySupplierPipeMenu}.
 */
public class FluidSupplierPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private FluidResource targetFluid;
    private int targetMb;

    public FluidSupplierPipeMenu(int id, Inventory inventory, FluidSupplierPipeBlockEntity pipe) {
        super(ModMenus.FLUID_SUPPLIER_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.targetFluid = pipe.targetFluid();
        this.targetMb = pipe.targetMb();
    }

    public FluidSupplierPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.FLUID_SUPPLIER_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.targetFluid = FluidResource.STREAM_CODEC.decode(buf);
        this.targetMb = buf.readVarInt();
    }

    public BlockPos pos() {
        return pos;
    }

    public FluidResource targetFluid() {
        return targetFluid;
    }

    public int targetMb() {
        return targetMb;
    }

    public void setTargetLocal(FluidResource fluid, int amountMb) {
        this.targetFluid = fluid;
        this.targetMb = amountMb;
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

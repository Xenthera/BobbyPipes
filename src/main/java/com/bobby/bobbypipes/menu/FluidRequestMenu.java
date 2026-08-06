package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.FluidRequestPipeBlock;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu for the fluid request pipe screen. Only carries the pipe position, same
 * shape as {@link RequestMenu}; which fluid is selected is pure client UI state, same as
 * the item Request screen's own selection.
 */
public class FluidRequestMenu extends AbstractContainerMenu {

    private final BlockPos pos;

    public FluidRequestMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.FLUID_REQUEST.get(), containerId);
        this.pos = pos.immutable();
    }

    public FluidRequestMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
        this(containerId, inventory, buf.readBlockPos());
    }

    public BlockPos pos() {
        return pos;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.level().getBlockState(pos).getBlock() instanceof FluidRequestPipeBlock
                && player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

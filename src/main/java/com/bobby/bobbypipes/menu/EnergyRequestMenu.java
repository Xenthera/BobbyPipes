package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.EnergyRequestPipeBlock;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu for the energy request pipe screen. Only carries the pipe position, same
 * shape as {@link RequestMenu}, there is no item selection to hold since there is only
 * one kind of energy.
 */
public class EnergyRequestMenu extends AbstractContainerMenu {

    private final BlockPos pos;

    public EnergyRequestMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.ENERGY_REQUEST.get(), containerId);
        this.pos = pos.immutable();
    }

    /** Client factory: reads the block pos written by {@code openMenu(..., pos)}. */
    public EnergyRequestMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
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
        return player.level().getBlockState(pos).getBlock() instanceof EnergyRequestPipeBlock
                && player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

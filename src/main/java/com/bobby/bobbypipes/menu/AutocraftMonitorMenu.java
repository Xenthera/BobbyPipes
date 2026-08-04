package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.AutocraftMonitorBlock;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Slotless menu for the autocraft monitor screen. */
public class AutocraftMonitorMenu extends AbstractContainerMenu {

    private final BlockPos pos;

    public AutocraftMonitorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.AUTOCRAFT_MONITOR.get(), containerId);
        this.pos = pos.immutable();
    }

    public AutocraftMonitorMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
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
        return player.level().getBlockState(pos).getBlock() instanceof AutocraftMonitorBlock
                && player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

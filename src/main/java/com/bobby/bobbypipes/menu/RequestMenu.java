package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.RequestPipeBlock;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * Slotless menu for the request pipe screen. Only carries the pipe position so the
 * client and server agree which network node is requesting.
 */
public class RequestMenu extends AbstractContainerMenu {

    private final BlockPos pos;

    public RequestMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(ModMenus.REQUEST.get(), containerId);
        this.pos = pos.immutable();
    }

    /** Client factory: reads the block pos written by {@code openMenu(..., pos)}. */
    public RequestMenu(int containerId, Inventory inventory, RegistryFriendlyByteBuf buf) {
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
        return player.level().getBlockState(pos).getBlock() instanceof RequestPipeBlock
                && player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

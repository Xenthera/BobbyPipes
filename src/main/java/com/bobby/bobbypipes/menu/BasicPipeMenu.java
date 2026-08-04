package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class BasicPipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private boolean defaultRoute;

    public BasicPipeMenu(int id, Inventory inventory, BasicPipeBlockEntity pipe) {
        super(ModMenus.BASIC_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.defaultRoute = pipe.isDefaultRoute();
    }

    public BasicPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.BASIC_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.defaultRoute = buf.readBoolean();
    }

    public BlockPos pos() {
        return pos;
    }

    public boolean isDefaultRoute() {
        return defaultRoute;
    }

    public void setDefaultRouteLocal(boolean defaultRoute) {
        this.defaultRoute = defaultRoute;
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

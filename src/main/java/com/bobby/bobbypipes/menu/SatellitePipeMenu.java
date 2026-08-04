package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class SatellitePipeMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private String satelliteName;

    public SatellitePipeMenu(int id, Inventory inventory, SatellitePipeBlockEntity pipe) {
        super(ModMenus.SATELLITE_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.satelliteName = pipe.satelliteName();
    }

    public SatellitePipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.SATELLITE_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.satelliteName = buf.readUtf();
    }

    public BlockPos pos() {
        return pos;
    }

    public String satelliteName() {
        return satelliteName;
    }

    public void setSatelliteNameLocal(String name) {
        this.satelliteName = name;
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

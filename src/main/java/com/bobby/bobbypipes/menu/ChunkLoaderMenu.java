package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class ChunkLoaderMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private int radius;
    private boolean active;
    private boolean debugOutline;

    public ChunkLoaderMenu(int id, Inventory inventory, ChunkLoaderBlockEntity loader) {
        super(ModMenus.CHUNK_LOADER.get(), id);
        this.pos = loader.getBlockPos().immutable();
        this.radius = loader.radius();
        this.active = loader.isActive();
        this.debugOutline = loader.debugOutline();
    }

    public ChunkLoaderMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.CHUNK_LOADER.get(), id);
        this.pos = buf.readBlockPos();
        this.radius = buf.readVarInt();
        this.active = buf.readBoolean();
        this.debugOutline = buf.readBoolean();
    }

    public BlockPos pos() {
        return pos;
    }

    public int radius() {
        return radius;
    }

    public boolean active() {
        return active;
    }

    public boolean debugOutline() {
        return debugOutline;
    }

    public void setLocal(int radius, boolean active, boolean debugOutline) {
        this.radius = radius;
        this.active = active;
        this.debugOutline = debugOutline;
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

package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.TwerkGeneratorBlockEntity;
import com.bobby.bobbypipes.network.payload.GeneratorSyncPayload;
import com.bobby.bobbypipes.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/** Slotless: there is nothing to put in a twerk generator but effort. */
public class TwerkGeneratorMenu extends AbstractContainerMenu {

    private final BlockPos pos;
    private int energy;
    private int capacity;

    public TwerkGeneratorMenu(int id, Inventory inventory, TwerkGeneratorBlockEntity generator) {
        super(ModMenus.TWERK_GENERATOR.get(), id);
        this.pos = generator.getBlockPos().immutable();
        this.energy = generator.energy();
        this.capacity = generator.capacity();
    }

    public TwerkGeneratorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.TWERK_GENERATOR.get(), id);
        this.pos = buf.readBlockPos();
        this.energy = buf.readVarInt();
        this.capacity = buf.readVarInt();
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

    public void applySync(GeneratorSyncPayload payload) {
        if (!payload.pos().equals(pos)) {
            return;
        }
        this.energy = payload.energy();
        this.capacity = payload.capacity();
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

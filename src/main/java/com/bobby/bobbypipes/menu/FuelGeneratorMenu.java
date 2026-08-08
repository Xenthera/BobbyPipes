package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.FuelGeneratorBlockEntity;
import com.bobby.bobbypipes.network.payload.GeneratorSyncPayload;
import com.bobby.bobbypipes.registry.ModMenus;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.item.ResourceHandlerSlot;

public class FuelGeneratorMenu extends AbstractContainerMenu {

    /**
     * Where the single fuel slot sits, shared by menu and screen so they cannot drift.
     *
     * <p>Right of centre, leaving room for the charge gauge down the left and the thin burn
     * bar immediately to the slot's left - the usual generator arrangement.
     */
    public static final int FUEL_SLOT_X = 98;
    public static final int FUEL_SLOT_Y = 44;
    public static final int INV_SLOT_Y = 94;
    /** Same shape as the other slot-bearing panels: inventory rows + hotbar + bottom pad. */
    public static final int PANEL_HEIGHT =
            INV_SLOT_Y + (3 * 18 + 4 + 18) + GuiLayout.CONTENT_BOTTOM_PAD + 2;

    private final BlockPos pos;
    private final ItemStacksResourceHandler fuel;
    private int energy;
    private int capacity;
    private int burnTicks;
    private int burnTicksTotal;

    public FuelGeneratorMenu(int id, Inventory inventory, FuelGeneratorBlockEntity generator) {
        super(ModMenus.FUEL_GENERATOR.get(), id);
        this.pos = generator.getBlockPos().immutable();
        this.fuel = generator.fuel();
        this.energy = generator.energy();
        this.capacity = generator.capacity();
        this.burnTicks = generator.burnTicks();
        this.burnTicksTotal = generator.burnTicksTotal();
        addGeneratorSlots();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    public FuelGeneratorMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.FUEL_GENERATOR.get(), id);
        this.pos = buf.readBlockPos();
        this.energy = buf.readVarInt();
        this.capacity = buf.readVarInt();
        this.fuel = new ItemStacksResourceHandler(1);
        addGeneratorSlots();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    private void addGeneratorSlots() {
        addSlot(new ResourceHandlerSlot(fuel, fuel::set, 0, FUEL_SLOT_X, FUEL_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                // Anything that burns. Checked here as well as on the block so shift-clicking
                // junk into the slot cannot jam the generator with something it cannot light.
                return !stack.isEmpty();
            }
        });
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

    public int burnTicks() {
        return burnTicks;
    }

    public int burnTicksTotal() {
        return burnTicksTotal;
    }

    public boolean isBurning() {
        return burnTicks > 0;
    }

    public void applySync(GeneratorSyncPayload payload) {
        if (!payload.pos().equals(pos)) {
            return;
        }
        this.energy = payload.energy();
        this.capacity = payload.capacity();
        this.burnTicks = payload.burnTicks();
        this.burnTicksTotal = payload.burnTicksTotal();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, 1, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return player.isWithinBlockInteractionRange(pos, 4.0);
    }
}

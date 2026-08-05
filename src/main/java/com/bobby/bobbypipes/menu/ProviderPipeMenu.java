package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import com.bobby.bobbypipes.registry.ModMenus;
import com.bobby.bobbycore.client.gui.layout.GuiLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

public class ProviderPipeMenu extends AbstractContainerMenu {

    /** Filter ghost row Y (panel-relative), matching supplier/crafting rhythm. */
    public static final int FILTER_SLOT_Y = 20 + GuiLayout.CONTENT_TOP_PAD;
    /** Include toggle + leave-mode button sit under the filter row. */
    public static final int CONTROLS_Y = FILTER_SLOT_Y + 18 + 6;
    /** Filter icon button + leave-mode row height (matches {@code FilterModeButton.SIZE}). */
    public static final int CONTROL_H = 20;
    /** First player-inventory item row. */
    public static final int INV_SLOT_Y = CONTROLS_Y + CONTROL_H + 18;
    public static final int PANEL_HEIGHT = INV_SLOT_Y + (3 * 18 + 4 + 18) + GuiLayout.CONTENT_BOTTOM_PAD + 2;

    private final BlockPos pos;
    private ProviderSettings settings;

    public ProviderPipeMenu(int id, Inventory inventory, ProviderPipeBlockEntity pipe) {
        super(ModMenus.PROVIDER_PIPE.get(), id);
        this.pos = pipe.getBlockPos().immutable();
        this.settings = pipe.settings();
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    public ProviderPipeMenu(int id, Inventory inventory, RegistryFriendlyByteBuf buf) {
        super(ModMenus.PROVIDER_PIPE.get(), id);
        this.pos = buf.readBlockPos();
        this.settings = ProviderSettings.STREAM_CODEC.decode(buf);
        addStandardInventorySlots(inventory, GuiLayout.playerInventoryOriginX(), INV_SLOT_Y);
    }

    public BlockPos pos() {
        return pos;
    }

    public ProviderSettings settings() {
        return settings;
    }

    public void setSettingsLocal(ProviderSettings settings) {
        this.settings = settings;
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

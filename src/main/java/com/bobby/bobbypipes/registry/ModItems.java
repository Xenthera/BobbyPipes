package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BobbyPipes.MOD_ID);

    /**
     * Opens the configuration screen of any routed pipe.
     *
     * <p>Pipes sit against chests and machines, so a bare right click is far more often an
     * attempt to open the container behind them. Gating the screens behind a tool keeps
     * that from being hijacked.
     */
    public static final DeferredItem<Item> WRENCH = ITEMS.registerItem("wrench",
            props -> new Item(props.stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack,
                                            Item.TooltipContext context,
                                            TooltipDisplay display,
                                            Consumer<Component> lines,
                                            TooltipFlag flag) {
                    lines.accept(Component.translatable("item.bobbypipes.wrench.tip")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * Head-slot visor that shows live status of the pipe under the crosshair in world.
     */
    public static final DeferredItem<Item> PIPE_GOGGLES = ITEMS.registerItem("pipe_goggles",
            props -> new Item(props.stacksTo(1).equippable(EquipmentSlot.HEAD)) {
                @Override
                public void appendHoverText(ItemStack stack,
                                            Item.TooltipContext context,
                                            TooltipDisplay display,
                                            Consumer<Component> lines,
                                            TooltipFlag flag) {
                    lines.accept(Component.translatable("item.bobbypipes.pipe_goggles.tip")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * Client-only render stand-in for the routed-parcel cage. Not shown in the creative tab.
     */
    public static final DeferredItem<Item> PARCEL_CAGE = ITEMS.registerItem("parcel_cage",
            props -> new Item(props.stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

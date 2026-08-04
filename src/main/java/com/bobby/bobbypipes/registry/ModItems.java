package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

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
                public void appendHoverText(net.minecraft.world.item.ItemStack stack,
                                            Item.TooltipContext context,
                                            net.minecraft.world.item.component.TooltipDisplay display,
                                            java.util.function.Consumer<net.minecraft.network.chat.Component> lines,
                                            net.minecraft.world.item.TooltipFlag flag) {
                    lines.accept(net.minecraft.network.chat.Component
                            .translatable("item.bobbypipes.wrench.tip")
                            .withStyle(net.minecraft.ChatFormatting.GRAY));
                }
            });

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

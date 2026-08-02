package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BobbyPipes.MOD_ID);

    /** Configuration tool. Rotates and reconfigures pipes once there is something to configure. */
    public static final DeferredItem<Item> WRENCH = ITEMS.registerItem("wrench",
            props -> new Item(props.stacksTo(1)));

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

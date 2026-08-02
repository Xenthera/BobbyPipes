package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.filter.ItemFilter;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * Data components carrying module configuration.
 *
 * <p>Configuration lives on the item rather than in block entity NBT, so it moves with the
 * module, survives being carried around, and syncs to the client for free.
 */
public final class ModDataComponents {

    public static final DeferredRegister.DataComponents DATA_COMPONENTS =
            DeferredRegister.createDataComponents(Registries.DATA_COMPONENT_TYPE, BobbyPipes.MOD_ID);

    /** The item filter on a module that has one, such as the Item Sink. */
    public static final Supplier<DataComponentType<ItemFilter>> ITEM_FILTER =
            DATA_COMPONENTS.registerComponentType("item_filter", builder -> builder
                    .persistent(ItemFilter.CODEC)
                    .networkSynchronized(ItemFilter.STREAM_CODEC));

    private ModDataComponents() {
    }

    public static void register(IEventBus modEventBus) {
        DATA_COMPONENTS.register(modEventBus);
    }
}

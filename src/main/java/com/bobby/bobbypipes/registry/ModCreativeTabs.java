package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BobbyPipes.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup." + BobbyPipes.MOD_ID))
                    // Last of the vanilla tabs, so the mod's own tab lands at the end of
                    // the row rather than shouldering in between two vanilla ones.
                    .withTabsBefore(CreativeModeTabs.SPAWN_EGGS)
                    .icon(() -> ModItems.WRENCH.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        // Item pipes.
                        output.accept(ModBlocks.PIPE.get());
                        output.accept(ModBlocks.LINK_PIPE.get());
                        output.accept(ModBlocks.BASIC_PIPE.get());
                        output.accept(ModBlocks.PROVIDER_PIPE.get());
                        output.accept(ModBlocks.REQUEST_PIPE.get());
                        output.accept(ModBlocks.SUPPLIER_PIPE.get());
                        output.accept(ModBlocks.PASSIVE_SUPPLIER_PIPE.get());
                        output.accept(ModBlocks.CRAFTING_PIPE.get());
                        output.accept(ModBlocks.SATELLITE_PIPE.get());

                        // Energy pipes.
                        output.accept(ModBlocks.ENERGY_PROVIDER_PIPE.get());
                        output.accept(ModBlocks.ENERGY_REQUEST_PIPE.get());
                        output.accept(ModBlocks.ENERGY_SUPPLIER_PIPE.get());

                        // Fluid pipes.
                        output.accept(ModBlocks.FLUID_PROVIDER_PIPE.get());
                        output.accept(ModBlocks.FLUID_REQUEST_PIPE.get());
                        output.accept(ModBlocks.FLUID_SUPPLIER_PIPE.get());

                        // Non-pipe blocks.
                        output.accept(ModBlocks.PATTERN_TABLE.get());
                        output.accept(ModBlocks.AUTOCRAFT_MONITOR.get());
                        output.accept(ModBlocks.CHUNK_LOADER.get());

                        // Items.
                        output.accept(ModItems.WRENCH.get());
                        output.accept(ModItems.PIPE_GOGGLES.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }

    public static void register(IEventBus modEventBus) {
        TABS.register(modEventBus);
    }
}

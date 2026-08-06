package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.menu.LinkPipeMenu;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenu;
import com.bobby.bobbypipes.menu.BasicPipeMenu;
import com.bobby.bobbypipes.menu.ChunkLoaderMenu;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import com.bobby.bobbypipes.menu.EnergyRequestMenu;
import com.bobby.bobbypipes.menu.EnergySupplierPipeMenu;
import com.bobby.bobbypipes.menu.FluidRequestMenu;
import com.bobby.bobbypipes.menu.FluidSupplierPipeMenu;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import com.bobby.bobbypipes.menu.ProviderPipeMenu;
import com.bobby.bobbypipes.menu.RequestMenu;
import com.bobby.bobbypipes.menu.SatellitePipeMenu;
import com.bobby.bobbypipes.menu.SupplierPipeMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {

    public static final DeferredRegister<MenuType<?>> MENUS =
            DeferredRegister.create(Registries.MENU, BobbyPipes.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<RequestMenu>> REQUEST =
            MENUS.register("request", () -> IMenuTypeExtension.create(RequestMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<EnergyRequestMenu>> ENERGY_REQUEST =
            MENUS.register("energy_request", () -> IMenuTypeExtension.create(EnergyRequestMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<BasicPipeMenu>> BASIC_PIPE =
            MENUS.register("basic_pipe", () -> IMenuTypeExtension.create(BasicPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ProviderPipeMenu>> PROVIDER_PIPE =
            MENUS.register("provider_pipe", () -> IMenuTypeExtension.create(ProviderPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<PatternTableMenu>> PATTERN_TABLE =
            MENUS.register("pattern_table", () -> IMenuTypeExtension.create(PatternTableMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<CraftingPipeMenu>> CRAFTING_PIPE =
            MENUS.register("crafting_pipe", () -> IMenuTypeExtension.create(CraftingPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<SatellitePipeMenu>> SATELLITE_PIPE =
            MENUS.register("satellite_pipe", () -> IMenuTypeExtension.create(SatellitePipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<LinkPipeMenu>> LINK_PIPE =
            MENUS.register("link_pipe", () -> IMenuTypeExtension.create(LinkPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<SupplierPipeMenu>> SUPPLIER_PIPE =
            MENUS.register("supplier_pipe", () -> IMenuTypeExtension.create(SupplierPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<EnergySupplierPipeMenu>> ENERGY_SUPPLIER_PIPE =
            MENUS.register("energy_supplier_pipe", () -> IMenuTypeExtension.create(EnergySupplierPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<FluidRequestMenu>> FLUID_REQUEST =
            MENUS.register("fluid_request", () -> IMenuTypeExtension.create(FluidRequestMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<FluidSupplierPipeMenu>> FLUID_SUPPLIER_PIPE =
            MENUS.register("fluid_supplier_pipe", () -> IMenuTypeExtension.create(FluidSupplierPipeMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<AutocraftMonitorMenu>> AUTOCRAFT_MONITOR =
            MENUS.register("autocraft_monitor", () -> IMenuTypeExtension.create(AutocraftMonitorMenu::new));

    public static final DeferredHolder<MenuType<?>, MenuType<ChunkLoaderMenu>> CHUNK_LOADER =
            MENUS.register("chunk_loader", () -> IMenuTypeExtension.create(ChunkLoaderMenu::new));

    private ModMenus() {
    }

    public static void register(IEventBus modEventBus) {
        MENUS.register(modEventBus);
    }
}

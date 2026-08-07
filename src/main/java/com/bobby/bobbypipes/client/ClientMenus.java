package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.screen.LinkPipeScreen;
import com.bobby.bobbypipes.client.screen.AutocraftMonitorScreen;
import com.bobby.bobbypipes.client.screen.BasicPipeScreen;
import com.bobby.bobbypipes.client.screen.ChunkLoaderScreen;
import com.bobby.bobbypipes.client.screen.CraftingPipeScreen;
import com.bobby.bobbypipes.client.screen.EnergyRequestScreen;
import com.bobby.bobbypipes.client.screen.EnergySupplierPipeScreen;
import com.bobby.bobbypipes.client.screen.FluidRequestScreen;
import com.bobby.bobbypipes.client.screen.FluidSupplierPipeScreen;
import com.bobby.bobbypipes.client.screen.PatternTableScreen;
import com.bobby.bobbypipes.client.screen.PowerJunctionScreen;
import com.bobby.bobbypipes.client.screen.ProviderPipeScreen;
import com.bobby.bobbypipes.client.screen.RequestScreen;
import com.bobby.bobbypipes.client.screen.SatellitePipeScreen;
import com.bobby.bobbypipes.client.screen.SupplierPipeScreen;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import com.bobby.bobbypipes.network.payload.CraftingPipeSyncPayload;
import com.bobby.bobbypipes.network.payload.EnergyStockPayload;
import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.PatternTableSyncPayload;
import com.bobby.bobbypipes.network.payload.PowerJunctionSyncPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
import com.bobby.bobbypipes.network.payload.LinkChannelResultPayload;
import com.bobby.bobbypipes.network.payload.SatelliteListPayload;
import com.bobby.bobbypipes.network.payload.SatelliteNameResultPayload;
import com.bobby.bobbypipes.registry.ModMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.network.event.RegisterClientPayloadHandlersEvent;

@EventBusSubscriber(modid = BobbyPipes.MOD_ID, value = Dist.CLIENT)
public final class ClientMenus {

    private ClientMenus() {
    }

    @SubscribeEvent
    public static void registerScreens(RegisterMenuScreensEvent event) {
        event.register(ModMenus.REQUEST.get(), RequestScreen::new);
        event.register(ModMenus.ENERGY_REQUEST.get(), EnergyRequestScreen::new);
        event.register(ModMenus.ENERGY_SUPPLIER_PIPE.get(), EnergySupplierPipeScreen::new);
        event.register(ModMenus.FLUID_REQUEST.get(), FluidRequestScreen::new);
        event.register(ModMenus.FLUID_SUPPLIER_PIPE.get(), FluidSupplierPipeScreen::new);
        event.register(ModMenus.BASIC_PIPE.get(), BasicPipeScreen::new);
        event.register(ModMenus.PROVIDER_PIPE.get(), ProviderPipeScreen::new);
        event.register(ModMenus.PATTERN_TABLE.get(), PatternTableScreen::new);
        event.register(ModMenus.CRAFTING_PIPE.get(), CraftingPipeScreen::new);
        event.register(ModMenus.SATELLITE_PIPE.get(), SatellitePipeScreen::new);
        event.register(ModMenus.LINK_PIPE.get(), LinkPipeScreen::new);
        event.register(ModMenus.SUPPLIER_PIPE.get(), SupplierPipeScreen::new);
        event.register(ModMenus.AUTOCRAFT_MONITOR.get(), AutocraftMonitorScreen::new);
        event.register(ModMenus.CHUNK_LOADER.get(), ChunkLoaderScreen::new);
        event.register(ModMenus.POWER_JUNCTION.get(), PowerJunctionScreen::new);
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(NetworkStockPayload.TYPE, ClientRequestGui::handleStock);
        event.register(EnergyStockPayload.TYPE, ClientEnergyRequestGui::handleStock);
        event.register(FluidStockPayload.TYPE, ClientFluidRequestGui::handleStock);
        event.register(RequestResultPayload.TYPE, ClientRequestGui::handleResult);
        event.register(SatelliteNameResultPayload.TYPE, SatelliteNameResultPayload::handle);
        event.register(LinkChannelResultPayload.TYPE, LinkChannelResultPayload::handle);
        event.register(SatelliteListPayload.TYPE, SatelliteListPayload::handle);
        event.register(PatternTableSyncPayload.TYPE, PatternTableSyncPayload::handle);
        event.register(CraftingPipeSyncPayload.TYPE, CraftingPipeSyncPayload::handle);
        event.register(CraftMonitorPayload.TYPE, ClientCraftMonitor::handle);
        event.register(PowerJunctionSyncPayload.TYPE, PowerJunctionSyncPayload::handle);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRequestGui.clear();
        ClientEnergyRequestGui.clear();
        ClientFluidRequestGui.clear();
        ClientCraftMonitor.clear();
    }
}

package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.screen.AutocraftMonitorScreen;
import com.bobby.bobbypipes.client.screen.BasicPipeScreen;
import com.bobby.bobbypipes.client.screen.CraftingPipeScreen;
import com.bobby.bobbypipes.client.screen.PatternTableScreen;
import com.bobby.bobbypipes.client.screen.RequestScreen;
import com.bobby.bobbypipes.client.screen.SatellitePipeScreen;
import com.bobby.bobbypipes.client.screen.SupplierPipeScreen;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import com.bobby.bobbypipes.network.payload.CraftingPipeSyncPayload;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.PatternTableSyncPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
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
        event.register(ModMenus.BASIC_PIPE.get(), BasicPipeScreen::new);
        event.register(ModMenus.PATTERN_TABLE.get(), PatternTableScreen::new);
        event.register(ModMenus.CRAFTING_PIPE.get(), CraftingPipeScreen::new);
        event.register(ModMenus.SATELLITE_PIPE.get(), SatellitePipeScreen::new);
        event.register(ModMenus.SUPPLIER_PIPE.get(), SupplierPipeScreen::new);
        event.register(ModMenus.AUTOCRAFT_MONITOR.get(), AutocraftMonitorScreen::new);
    }

    @SubscribeEvent
    public static void registerPayloadHandlers(RegisterClientPayloadHandlersEvent event) {
        event.register(NetworkStockPayload.TYPE, ClientRequestGui::handleStock);
        event.register(RequestResultPayload.TYPE, ClientRequestGui::handleResult);
        event.register(SatelliteNameResultPayload.TYPE, SatelliteNameResultPayload::handle);
        event.register(SatelliteListPayload.TYPE, SatelliteListPayload::handle);
        event.register(PatternTableSyncPayload.TYPE, PatternTableSyncPayload::handle);
        event.register(CraftingPipeSyncPayload.TYPE, CraftingPipeSyncPayload::handle);
        event.register(CraftMonitorPayload.TYPE, ClientCraftMonitor::handle);
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientRequestGui.clear();
        ClientCraftMonitor.clear();
    }
}

package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.payload.CraftingPipeSyncPayload;
import com.bobby.bobbypipes.network.payload.EnergyStockPayload;
import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import com.bobby.bobbypipes.network.payload.RequestFluidPayload;
import com.bobby.bobbypipes.network.payload.SetFluidSupplierTargetPayload;
import com.bobby.bobbypipes.network.payload.ImportCraftPatternPayload;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.RequestEnergyPayload;
import com.bobby.bobbypipes.network.payload.SetEnergySupplierTargetPayload;
import com.bobby.bobbypipes.network.payload.CancelCraftJobPayload;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import com.bobby.bobbypipes.network.payload.CraftStatusPayload;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import com.bobby.bobbypipes.network.payload.PatternTableSyncPayload;
import com.bobby.bobbypipes.network.payload.PipeProbePayload;
import com.bobby.bobbypipes.network.payload.ProbePipePayload;
import com.bobby.bobbypipes.network.payload.RequestItemPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
import com.bobby.bobbypipes.network.payload.RequestSatelliteListPayload;
import com.bobby.bobbypipes.network.payload.SatelliteListPayload;
import com.bobby.bobbypipes.network.payload.SatelliteNameResultPayload;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import com.bobby.bobbypipes.network.payload.SetDefaultRoutePayload;
import com.bobby.bobbypipes.network.payload.SetSatelliteNamePayload;
import com.bobby.bobbypipes.network.payload.SetProviderSettingsPayload;
import com.bobby.bobbypipes.network.payload.SetSupplierRequestsPayload;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/**
 * Registers common network payloads. Client handlers live under {@code client/}.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class ModPayloads {

    private ModPayloads() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        event.registrar("1")
                .playToClient(ParcelSyncPayload.TYPE, ParcelSyncPayload.STREAM_CODEC)
                .playToClient(CraftStatusPayload.TYPE, CraftStatusPayload.STREAM_CODEC)
                .playToClient(CraftMonitorPayload.TYPE, CraftMonitorPayload.STREAM_CODEC)
                .playToClient(NetworkStockPayload.TYPE, NetworkStockPayload.STREAM_CODEC)
                .playToClient(EnergyStockPayload.TYPE, EnergyStockPayload.STREAM_CODEC)
                .playToClient(FluidStockPayload.TYPE, FluidStockPayload.STREAM_CODEC)
                .playToClient(RequestResultPayload.TYPE, RequestResultPayload.STREAM_CODEC)
                .playToClient(SatelliteNameResultPayload.TYPE, SatelliteNameResultPayload.STREAM_CODEC)
                .playToClient(SatelliteListPayload.TYPE, SatelliteListPayload.STREAM_CODEC)
                .playToClient(PatternTableSyncPayload.TYPE, PatternTableSyncPayload.STREAM_CODEC)
                .playToClient(CraftingPipeSyncPayload.TYPE, CraftingPipeSyncPayload.STREAM_CODEC)
                .playToClient(PipeProbePayload.TYPE, PipeProbePayload.STREAM_CODEC)
                .playToServer(ProbePipePayload.TYPE, ProbePipePayload.STREAM_CODEC,
                        ProbePipePayload::handle)
                .playToServer(RequestItemPayload.TYPE, RequestItemPayload.STREAM_CODEC,
                        RequestItemPayload::handle)
                .playToServer(RequestEnergyPayload.TYPE, RequestEnergyPayload.STREAM_CODEC,
                        RequestEnergyPayload::handle)
                .playToServer(RequestFluidPayload.TYPE, RequestFluidPayload.STREAM_CODEC,
                        RequestFluidPayload::handle)
                .playToServer(SetFluidSupplierTargetPayload.TYPE, SetFluidSupplierTargetPayload.STREAM_CODEC,
                        SetFluidSupplierTargetPayload::handle)
                .playToServer(SetCraftPatternPayload.TYPE, SetCraftPatternPayload.STREAM_CODEC,
                        SetCraftPatternPayload::handle)
                .playToServer(ImportCraftPatternPayload.TYPE, ImportCraftPatternPayload.STREAM_CODEC,
                        ImportCraftPatternPayload::handle)
                .playToServer(SetSatelliteNamePayload.TYPE, SetSatelliteNamePayload.STREAM_CODEC,
                        SetSatelliteNamePayload::handle)
                .playToServer(RequestSatelliteListPayload.TYPE, RequestSatelliteListPayload.STREAM_CODEC,
                        RequestSatelliteListPayload::handle)
                .playToServer(SetDefaultRoutePayload.TYPE, SetDefaultRoutePayload.STREAM_CODEC,
                        SetDefaultRoutePayload::handle)
                .playToServer(SetSupplierRequestsPayload.TYPE, SetSupplierRequestsPayload.STREAM_CODEC,
                        SetSupplierRequestsPayload::handle)
                .playToServer(SetEnergySupplierTargetPayload.TYPE, SetEnergySupplierTargetPayload.STREAM_CODEC,
                        SetEnergySupplierTargetPayload::handle)
                .playToServer(SetProviderSettingsPayload.TYPE, SetProviderSettingsPayload.STREAM_CODEC,
                        SetProviderSettingsPayload::handle)
                .playToServer(CancelCraftJobPayload.TYPE, CancelCraftJobPayload.STREAM_CODEC,
                        CancelCraftJobPayload::handle);
    }
}

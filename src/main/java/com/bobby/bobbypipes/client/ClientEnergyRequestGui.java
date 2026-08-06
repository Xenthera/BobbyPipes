package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.EnergyStockPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-side state for the energy request pipe screen: the last available-FE estimate.
 */
public final class ClientEnergyRequestGui {

    private static int availableFe;

    private ClientEnergyRequestGui() {
    }

    public static void handleStock(EnergyStockPayload payload, IPayloadContext context) {
        availableFe = payload.availableFe();
    }

    public static int availableFe() {
        return availableFe;
    }

    public static void clear() {
        availableFe = 0;
    }
}

package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.FluidStockPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;

/**
 * Client-side state for the fluid request/supplier screens: the last fluid stock catalog.
 */
public final class ClientFluidRequestGui {

    private static List<FluidStockPayload.Entry> stock = List.of();

    private ClientFluidRequestGui() {
    }

    public static void handleStock(FluidStockPayload payload, IPayloadContext context) {
        stock = List.copyOf(payload.entries());
    }

    public static List<FluidStockPayload.Entry> stock() {
        return stock;
    }

    public static void clear() {
        stock = List.of();
    }

    /** Free amount for {@code fluid} in the last stock snapshot, or 0. */
    public static int amountOf(FluidResource fluid) {
        for (FluidStockPayload.Entry entry : stock) {
            if (entry.fluid().equals(fluid)) {
                return entry.amountMb();
            }
        }
        return 0;
    }
}

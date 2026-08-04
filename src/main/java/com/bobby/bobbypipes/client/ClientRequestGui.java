package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import com.bobby.bobbypipes.network.payload.RequestResultPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;

/**
 * Client-side state for the request pipe screen: stock catalog and last result status.
 */
public final class ClientRequestGui {

    private static List<NetworkStockPayload.Entry> stock = List.of();
    private static RequestResultPayload lastResult;

    private ClientRequestGui() {
    }

    public static void handleStock(NetworkStockPayload payload, IPayloadContext context) {
        stock = List.copyOf(payload.entries());
    }

    public static void handleResult(RequestResultPayload payload, IPayloadContext context) {
        lastResult = payload;
    }

    public static List<NetworkStockPayload.Entry> stock() {
        return stock;
    }

    public static RequestResultPayload lastResult() {
        return lastResult;
    }

    public static void clearResult() {
        lastResult = null;
    }

    public static void clear() {
        stock = List.of();
        lastResult = null;
    }

    /** Finds free amount for {@code item} in the last stock snapshot, or 0. */
    public static int amountOf(ItemResource item) {
        for (NetworkStockPayload.Entry entry : stock) {
            if (entry.item().equals(item)) {
                return entry.amount();
            }
        }
        return 0;
    }
}

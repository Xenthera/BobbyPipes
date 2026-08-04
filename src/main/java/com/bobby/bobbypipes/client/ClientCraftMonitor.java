package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

/** Client cache for the open Autocraft Monitor screen. */
public final class ClientCraftMonitor {

    private static boolean linked;
    private static List<CraftMonitorPayload.Card> cards = List.of();

    private ClientCraftMonitor() {
    }

    public static void handle(CraftMonitorPayload payload, IPayloadContext context) {
        linked = payload.linked();
        cards = List.copyOf(payload.cards());
    }

    public static void clear() {
        linked = false;
        cards = List.of();
    }

    public static boolean linked() {
        return linked;
    }

    public static List<CraftMonitorPayload.Card> cards() {
        return cards;
    }
}

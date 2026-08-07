package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.logistics.NetworkSupply;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.network.payload.NetworkStockPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

/**
 * Opens the request menu and pushes a stock snapshot to the player.
 */
public final class RequestMenus {

    private RequestMenus() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        OptionalInt window = player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new RequestMenu(id, inventory, pos),
                Component.translatable("menu.bobbypipes.request")), pos);
        if (window.isPresent()) {
            syncStock(player, pos);
        }
    }

    /** Sends the current free-stock catalog for {@code pos} to {@code player}. */
    public static void syncStock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        PipeNetwork network = PipeNetwork.get(level);
        network.rebuildNow(pos);
        List<NetworkStockPayload.Entry> entries = new ArrayList<>();
        for (NetworkSupply.CatalogEntry entry : network.supplyFor(pos).catalog()) {
            entries.add(new NetworkStockPayload.Entry(entry.item(), entry.amount(), entry.craftable()));
        }
        PacketDistributor.sendToPlayer(player, new NetworkStockPayload(entries));
    }
}

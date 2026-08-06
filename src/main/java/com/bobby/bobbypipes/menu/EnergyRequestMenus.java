package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.network.EnergyRequestService;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.payload.EnergyStockPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.OptionalInt;

/**
 * Opens the energy request menu and pushes an available-FE estimate to the player.
 */
public final class EnergyRequestMenus {

    private EnergyRequestMenus() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        OptionalInt window = player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new EnergyRequestMenu(id, inventory, pos),
                Component.translatable("menu.bobbypipes.energy_request")), pos);
        if (window.isPresent()) {
            syncStock(player, pos);
        }
    }

    /** Sends the current available-FE estimate for {@code pos} to {@code player}. */
    public static void syncStock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        PipeNetwork network = PipeNetwork.get(level);
        network.rebuildNow(pos);
        int available = EnergyRequestService.availableFe(level, network, pos);
        PacketDistributor.sendToPlayer(player, new EnergyStockPayload(available));
    }
}

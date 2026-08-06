package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.network.FluidRequestService;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.payload.FluidStockPayload;
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
 * Opens the fluid request menu and pushes a fluid stock catalog to the player.
 */
public final class FluidRequestMenus {

    private FluidRequestMenus() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        OptionalInt window = player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new FluidRequestMenu(id, inventory, pos),
                Component.translatable("menu.bobbypipes.fluid_request")), pos);
        if (window.isPresent()) {
            syncStock(player, pos);
        }
    }

    /** Sends the current free-fluid-stock catalog for {@code pos} to {@code player}. */
    public static void syncStock(ServerPlayer player, BlockPos pos) {
        ServerLevel level = player.level();
        PipeNetwork network = PipeNetwork.get(level);
        network.rebuildNow(pos);
        List<FluidStockPayload.Entry> entries = new ArrayList<>();
        for (FluidRequestService.Entry entry : FluidRequestService.catalog(level, network, pos)) {
            entries.add(new FluidStockPayload.Entry(entry.fluid(), entry.amountMb()));
        }
        PacketDistributor.sendToPlayer(player, new FluidStockPayload(entries));
    }
}

package com.bobby.bobbypipes.menu;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.logistics.CrossDimPipeGraph;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.PipeNodeId;
import com.bobby.bobbypipes.network.payload.CraftMonitorPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Opens the autocraft monitor and pushes live queue snapshots while it stays open.
 */
public final class AutocraftMonitorMenus {

    private AutocraftMonitorMenus() {
    }

    public static void open(ServerPlayer player, BlockPos pos) {
        OptionalInt window = player.openMenu(new SimpleMenuProvider(
                (id, inventory, opener) -> new AutocraftMonitorMenu(id, inventory, pos),
                Component.translatable("menu.bobbypipes.autocraft_monitor")), pos);
        if (window.isPresent()) {
            sync(player, pos);
        }
    }

    public static void sync(ServerPlayer player, BlockPos monitorPos) {
        ServerLevel level = player.level();
        Optional<BlockPos> seed = adjacentSmartPipe(level, monitorPos);
        if (seed.isEmpty()) {
            PacketDistributor.sendToPlayer(player,
                    new CraftMonitorPayload(false, java.util.List.of(), java.util.List.of()));
            return;
        }
        PipeNetwork network = PipeNetwork.get(level);
        var routes = network.routes();
        if (!routes.contains(seed.get())) {
            routes = network.rebuildNow(seed.get());
        }
        Set<PipeNodeId> component = new HashSet<>();
        component.add(PipeNodeId.of(level, seed.get()));
        routes.routesFrom(seed.get()).ifPresent(table -> {
            for (BlockPos dest : table.destinations()) {
                component.add(PipeNodeId.of(level, dest));
            }
        });
        // The monitor's own level only knows its own side of a link pair, and a job lives on
        // the requester's level rather than the crafter's. Widen the component across live
        // links, then ask every network - otherwise a craft ordered from the far side reads
        // as "no crafts on this network" from here.
        Set<PipeNodeId> reachable = CrossDimPipeGraph.expandAcrossLinks(component);
        List<CraftMonitorPayload.Card> cards = new ArrayList<>();
        List<CraftMonitorPayload.Order> orders = new ArrayList<>();
        // Positions only, for the provider queue, which is keyed by BlockPos within a level.
        Set<BlockPos> localComponent = new HashSet<>();
        for (PipeNodeId node : reachable) {
            if (node.dimension().equals(level.dimension())) {
                localComponent.add(node.pos());
            }
        }
        for (PipeNetwork candidate : PipeNetwork.instances()) {
            cards.addAll(candidate.craftJobs().monitorCards(
                    candidate.level(), candidate, reachable, level.getGameTime()));
            orders.addAll(candidate.craftJobs().orderRows(candidate.level(), reachable));
            if (candidate.level().dimension().equals(level.dimension())) {
                orders.addAll(candidate.sendQueue().orderRows(candidate.level(), localComponent));
            }
        }
        PacketDistributor.sendToPlayer(player, new CraftMonitorPayload(true, cards, orders));
    }

    /** Resync every player who currently has the monitor open. */
    public static void syncOpenMenus(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof AutocraftMonitorMenu menu) {
                sync(player, menu.pos());
            }
        }
    }

    public static Optional<BlockPos> adjacentSmartPipe(Level level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pos.relative(direction);
            if (PipeBlock.isSmartPipe(level.getBlockState(neighbour).getBlock())) {
                return Optional.of(neighbour.immutable());
            }
        }
        return Optional.empty();
    }
}

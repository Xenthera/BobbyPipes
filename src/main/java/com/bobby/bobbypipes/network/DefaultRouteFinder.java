package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.Optional;

/**
 * Picks the nearest basic pipe marked as a default route that has room for {@code item}.
 */
public final class DefaultRouteFinder {

    private DefaultRouteFinder() {
    }

    public static Optional<BlockPos> nearestWithSpace(ServerLevel level,
                                                      PipeNetwork network,
                                                      BlockPos from,
                                                      ItemResource item) {
        if (item.isEmpty()) {
            return Optional.empty();
        }
        RoutingSnapshot<BlockPos> routes = network.routes();
        return routes.routesFrom(from).map(table -> {
            BlockPos best = null;
            int bestCost = Integer.MAX_VALUE;
            if (isDefaultRoute(level, from) && InventoryAccess.canInsert(level, from, item)) {
                return from;
            }
            for (BlockPos dest : table.destinationsByCost()) {
                if (!isDefaultRoute(level, dest)) {
                    continue;
                }
                if (!InventoryAccess.canInsert(level, dest, item)) {
                    continue;
                }
                int cost = routes.cost(from, dest).orElse(Integer.MAX_VALUE);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = dest;
                }
            }
            return best;
        }).flatMap(pos -> Optional.ofNullable(pos));
    }

    private static boolean isDefaultRoute(ServerLevel level, BlockPos pos) {
        return level.hasChunkAt(pos)
                && level.getBlockEntity(pos) instanceof BasicPipeBlockEntity basic
                && basic.isDefaultRoute();
    }
}

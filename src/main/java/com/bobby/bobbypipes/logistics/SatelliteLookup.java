package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.SatellitePipeBlock;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves named satellite pipes on a crafter's routed component.
 */
public final class SatelliteLookup {

    private SatelliteLookup() {
    }

    public record NamedSatellite(String name, BlockPos pos) {
    }

    public static Map<String, BlockPos> findAll(ServerLevel level, PipeNetwork network, BlockPos from) {
        return findAll(level, network.routes(), from);
    }

    public static Map<String, BlockPos> findAll(ServerLevel level, RoutingSnapshot<BlockPos> routes, BlockPos from) {
        Map<String, BlockPos> found = new HashMap<>();
        for (NamedSatellite sat : listNamed(level, routes, from)) {
            found.putIfAbsent(sat.name(), sat.pos());
        }
        return found;
    }

    public static List<NamedSatellite> listNamed(ServerLevel level, PipeNetwork network, BlockPos from) {
        return listNamed(level, network.routes(), from);
    }

    public static List<NamedSatellite> listNamed(ServerLevel level, RoutingSnapshot<BlockPos> routes, BlockPos from) {
        List<NamedSatellite> list = new ArrayList<>();
        for (BlockPos pos : nodesFrom(routes, from)) {
            if (!(level.getBlockState(pos).getBlock() instanceof SatellitePipeBlock)) {
                continue;
            }
            if (!(level.getBlockEntity(pos) instanceof SatellitePipeBlockEntity satellite)) {
                continue;
            }
            String name = satellite.satelliteName();
            if (name.isBlank()) {
                continue;
            }
            list.add(new NamedSatellite(name, pos.immutable()));
        }
        list.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        return list;
    }

    public static Optional<BlockPos> find(ServerLevel level, PipeNetwork network, BlockPos from, String name) {
        return find(level, network.routes(), from, name);
    }

    public static Optional<BlockPos> find(ServerLevel level,
                                          RoutingSnapshot<BlockPos> routes,
                                          BlockPos from,
                                          String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(findAll(level, routes, from).get(name));
    }

    public static boolean isReachable(ServerLevel level, PipeNetwork network, BlockPos from, String name) {
        return isReachable(level, network.routes(), from, name);
    }

    public static boolean isReachable(ServerLevel level,
                                      RoutingSnapshot<BlockPos> routes,
                                      BlockPos from,
                                      String name) {
        return find(level, routes, from, name).isPresent();
    }

    public static boolean isDuplicateName(ServerLevel level,
                                          PipeNetwork network,
                                          BlockPos self,
                                          String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (NamedSatellite sat : listNamed(level, network, self)) {
            if (!sat.pos().equals(self) && sat.name().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static List<BlockPos> nodesFrom(RoutingSnapshot<BlockPos> routes, BlockPos from) {
        List<BlockPos> nodes = new ArrayList<>();
        nodes.add(from);
        routes.routesFrom(from).ifPresent(table -> nodes.addAll(table.destinations()));
        return nodes;
    }
}

package com.bobby.bobbypipes.logistics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.Optional;

/**
 * Helpers for routing and handing parcels across a cross-dimension link-pipe wormhole.
 */
public final class LinkTransit {

    private LinkTransit() {
    }

    /**
     * Next hop from {@code from} toward {@code to}, consulting the local snapshot first and
     * the cross-dim bridge graph second.
     */
    public static Optional<PipeNodeId> nextHop(ServerLevel level,
                                               RoutingSnapshot<BlockPos> local,
                                               PipeNodeId from,
                                               PipeNodeId to) {
        if (from.equals(to)) {
            return Optional.empty();
        }
        if (from.sameDimension(to) && from.dimension().equals(level.dimension())) {
            Optional<PipeNodeId> localHop =
                    local.nextHop(from.pos(), to.pos()).map(pos -> PipeNodeId.of(level, pos));
            if (localHop.isPresent()) {
                return localHop;
            }
            // Same dimension, different local component: the route may still exist by way of
            // a link out to another world and back.
            return CrossDimPipeGraph.nextHop(from, to);
        }
        if (from.sameDimension(to)) {
            ServerLevel other = LinkPipeRegistry.levelOf(level.getServer(), from);
            if (other == null) {
                return Optional.empty();
            }
            Optional<PipeNodeId> remoteHop =
                    PipeNetwork.get(other).routes().nextHop(from.pos(), to.pos())
                            .map(pos -> PipeNodeId.of(other, pos));
            return remoteHop.isPresent() ? remoteHop : CrossDimPipeGraph.nextHop(from, to);
        }
        return CrossDimPipeGraph.nextHop(from, to);
    }

    public static boolean canReach(ServerLevel level,
                                   RoutingSnapshot<BlockPos> local,
                                   PipeNodeId from,
                                   PipeNodeId to) {
        if (from.equals(to)) {
            return true;
        }
        return nextHop(level, local, from, to).isPresent();
    }

    /**
     * True when {@code at} is a link endpoint whose peer is {@code next} in another
     * dimension (the hop that should hand off rather than land locally).
     */
    public static Optional<PipeNodeId> crossDimPeerHop(MinecraftServer server,
                                                       BlockPos at,
                                                       BlockPos next,
                                                       net.minecraft.resources.ResourceKey<
                                                               net.minecraft.world.level.Level> atDim) {
        PipeNodeId atId = PipeNodeId.of(atDim, at);
        return LinkPipeRegistry.get(server).peerOf(atId).flatMap(peer -> {
            if (peer.sameDimension(atId)) {
                return Optional.empty();
            }
            if (!peer.pos().equals(next)) {
                return Optional.empty();
            }
            if (!LinkPipeRegistry.bothLoaded(atId, peer)) {
                return Optional.empty();
            }
            return Optional.of(peer);
        });
    }

    public static Optional<ServerLevel> levelOf(MinecraftServer server, PipeNodeId node) {
        return Optional.ofNullable(LinkPipeRegistry.levelOf(server, node));
    }
}

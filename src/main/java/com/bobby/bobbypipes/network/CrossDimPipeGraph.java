package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Builds and caches the merged {@link PipeNodeId} corridor graph used when link pipes
 * bridge dimensions (or when a same-dim pair is already folded into the per-level lattice).
 *
 * <p>Per-level {@link PipeNetwork} keeps a {@link BlockPos} lattice for mark painting and
 * same-dimension virtual edges. This class adds the cross-dimension overlay: both peer
 * lattices plus a cost-1 wormhole edge, solved once and consulted for reachability that
 * spans worlds.
 */
public final class CrossDimPipeGraph {

    private static final int MAX_NODES = 20_000;

    /** channel -> solved corridor snapshot spanning both ends. */
    private static final Map<Integer, RoutingSnapshot<PipeNodeId>> BRIDGES =
            new java.util.concurrent.ConcurrentHashMap<>();

    private CrossDimPipeGraph() {
    }

    /**
     * Rebuilds every cross-dimension bridge that touches {@code level} after a local
     * lattice rebuild.
     */
    public static void refreshAfterRebuild(ServerLevel level,
                                           Topology<BlockPos> localLattice,
                                           Set<BlockPos> localSmart) {
        LinkPipeRegistry registry = LinkPipeRegistry.get(level);
        Set<Integer> touched = new HashSet<>();
        for (LinkPipeRegistry.PipePair pair : registry.completePairs()) {
            if (pair.a().sameDimension(pair.b())) {
                BRIDGES.remove(pair.channel());
                continue;
            }
            boolean touches = pair.a().dimension().equals(level.dimension())
                    || pair.b().dimension().equals(level.dimension());
            if (!touches) {
                continue;
            }
            touched.add(pair.channel());
            if (!LinkPipeRegistry.bothLoaded(level.getServer(), pair.a(), pair.b())) {
                BRIDGES.remove(pair.channel());
                continue;
            }
            BRIDGES.put(pair.channel(), buildBridge(level, pair, localLattice, localSmart));
        }
        // Drop stale channels that no longer exist.
        BRIDGES.keySet().removeIf(channel -> registry.completePairs().stream()
                .noneMatch(p -> p.channel() == channel && !p.a().sameDimension(p.b())));
    }

    private static RoutingSnapshot<PipeNodeId> buildBridge(ServerLevel level,
                                                           LinkPipeRegistry.PipePair pair,
                                                           Topology<BlockPos> localLattice,
                                                           Set<BlockPos> localSmart) {
        Topology.Builder<PipeNodeId> builder = Topology.builder();
        PipeNodeId localEnd = pair.a().dimension().equals(level.dimension()) ? pair.a() : pair.b();
        PipeNodeId remoteEnd = localEnd.equals(pair.a()) ? pair.b() : pair.a();

        copyLocal(level.dimension(), localLattice, builder);
        ServerLevel remoteLevel = LinkPipeRegistry.levelOf(level.getServer(), remoteEnd);
        if (remoteLevel != null) {
            scanRemote(remoteLevel, remoteEnd.pos(), builder);
        }
        builder.link(pair.a(), pair.b(), 1);

        Topology<PipeNodeId> lattice = builder.build();
        Set<PipeNodeId> smart = smartNodes(level.getServer(), lattice, localSmart, level.dimension());
        Topology<PipeNodeId> transit = DirectCorridors.transitTopology(lattice, smart);
        return RoutingSnapshot.of(transit, System.nanoTime());
    }

    private static void copyLocal(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim,
                                  Topology<BlockPos> local,
                                  Topology.Builder<PipeNodeId> builder) {
        for (BlockPos pos : local.nodes()) {
            PipeNodeId node = PipeNodeId.of(dim, pos);
            builder.node(node);
            for (Map.Entry<BlockPos, Integer> edge : local.neighbours(pos).entrySet()) {
                builder.link(node, PipeNodeId.of(dim, edge.getKey()), edge.getValue());
            }
        }
    }

    private static void scanRemote(ServerLevel level, BlockPos seed, Topology.Builder<PipeNodeId> builder) {
        // Prefer the remote network's solved lattice (includes same-dim link edges). Fall
        // back to a physical BFS when that side has not rebuilt yet.
        Topology<BlockPos> remoteLattice = PipeNetwork.get(level).physicalLattice();
        if (remoteLattice.size() > 0 && remoteLattice.contains(seed)) {
            copyLocal(level.dimension(), remoteLattice, builder);
            return;
        }
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos start = seed.immutable();
        seen.add(start);
        queue.add(start);
        builder.node(PipeNodeId.of(level, start));
        while (!queue.isEmpty()) {
            if (seen.size() > MAX_NODES) {
                return;
            }
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = current.relative(direction).immutable();
                if (!level.hasChunkAt(neighbour)
                        || !(level.getBlockState(neighbour).getBlock() instanceof PipeBlock)) {
                    continue;
                }
                builder.link(PipeNodeId.of(level, current), PipeNodeId.of(level, neighbour), 1);
                if (seen.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
        injectRemoteSameDimLinks(level, builder);
    }

    /** Adds live same-dimension link-pipe edges into a remote scan that used physical BFS. */
    private static void injectRemoteSameDimLinks(ServerLevel level, Topology.Builder<PipeNodeId> builder) {
        LinkPipeRegistry registry = LinkPipeRegistry.get(level);
        for (LinkPipeRegistry.PipePair pair : registry.completePairs()) {
            if (!pair.a().sameDimension(pair.b())) {
                continue;
            }
            if (!pair.a().dimension().equals(level.dimension())) {
                continue;
            }
            if (!LinkPipeRegistry.bothLoaded(level.getServer(), pair.a(), pair.b())) {
                continue;
            }
            builder.node(pair.a());
            builder.node(pair.b());
            builder.link(pair.a(), pair.b(), 1);
        }
    }

    private static Set<PipeNodeId> smartNodes(
            net.minecraft.server.MinecraftServer server,
            Topology<PipeNodeId> lattice,
            Set<BlockPos> localSmart,
            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> localDim) {
        Set<PipeNodeId> smart = new HashSet<>();
        for (PipeNodeId node : lattice.nodes()) {
            if (node.dimension().equals(localDim) && localSmart.contains(node.pos())) {
                smart.add(node);
                continue;
            }
            ServerLevel nodeLevel = server.getLevel(node.dimension());
            if (nodeLevel == null || !nodeLevel.hasChunkAt(node.pos())) {
                continue;
            }
            Block block = nodeLevel.getBlockState(node.pos()).getBlock();
            if (PipeBlock.isSmartPipe(block) || block instanceof LinkPipeBlock) {
                smart.add(node);
            }
        }
        return smart;
    }

    /** Bridge snapshot for a channel, if a cross-dim pair is live. */
    public static java.util.Optional<RoutingSnapshot<PipeNodeId>> bridge(int channel) {
        return java.util.Optional.ofNullable(BRIDGES.get(channel));
    }

    /** Drops a cached bridge so the next rebuild can recreate it only if both ends load. */
    public static void dropChannel(int channel) {
        BRIDGES.remove(channel);
    }

    /** Every live cross-dim bridge. */
    public static java.util.Collection<RoutingSnapshot<PipeNodeId>> bridges() {
        return BRIDGES.values();
    }

    public static void clear() {
        BRIDGES.clear();
    }

    /**
     * Next hop from {@code from} toward {@code to} across any live cross-dim bridge.
     * Empty when no bridge knows both ends.
     */
    public static java.util.Optional<PipeNodeId> nextHop(PipeNodeId from, PipeNodeId to) {
        if (from.sameDimension(to)) {
            return java.util.Optional.empty();
        }
        for (RoutingSnapshot<PipeNodeId> bridge : BRIDGES.values()) {
            if (bridge.contains(from) && bridge.contains(to)) {
                return bridge.nextHop(from, to);
            }
        }
        return java.util.Optional.empty();
    }

    public static boolean canReach(PipeNodeId from, PipeNodeId to) {
        return nextHop(from, to).isPresent() || from.equals(to);
    }
}

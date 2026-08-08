package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
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

    /**
     * The two link mouths each bridge was built around.
     *
     * <p>A bridge only spans one channel, so a route that crosses two links - overworld to
     * nether and back again - is not inside any single one. These are the joining points a
     * chained route is searched over: reach one channel's mouth, then continue on the bridge
     * that shares it.
     */
    private static final Map<Integer, PipeNodeId[]> BRIDGE_MOUTHS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Memoised {@link #nextHop} answers, thrown away whenever a bridge changes.
     *
     * <p>The chained search walks bridge by bridge, and its callers ask the same questions
     * relentlessly: staging a single parcel resolves a hop per node along the route, and every
     * craft job re-checks whether its endpoints are still connected on every tick. Recomputing
     * that per call made a fifty job order through two wormholes unplayable.
     */
    private static final Map<RouteKey, java.util.Optional<PipeNodeId>> HOP_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Bumped on any bridge mutation; the cache is dropped when it moves. */
    private static final java.util.concurrent.atomic.AtomicLong REVISION =
            new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong CACHE_REVISION =
            new java.util.concurrent.atomic.AtomicLong(-1L);

    /** Bound so a pathological network cannot grow the cache without limit. */
    private static final int MAX_CACHED_ROUTES = 20_000;

    private record RouteKey(PipeNodeId from, PipeNodeId to) {
    }

    /** Called whenever the bridge set changes, so stale routes are never served. */
    private static void invalidateRoutes() {
        REVISION.incrementAndGet();
    }

    private static Map<RouteKey, java.util.Optional<PipeNodeId>> routeCache() {
        long current = REVISION.get();
        if (CACHE_REVISION.getAndSet(current) != current || HOP_CACHE.size() > MAX_CACHED_ROUTES) {
            HOP_CACHE.clear();
        }
        return HOP_CACHE;
    }

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
                BRIDGE_MOUTHS.remove(pair.channel());
                continue;
            }
            boolean touches = pair.a().dimension().equals(level.dimension())
                    || pair.b().dimension().equals(level.dimension());
            if (!touches) {
                continue;
            }
            touched.add(pair.channel());
            if (!LinkPipeRegistry.bothLoaded(pair.a(), pair.b())) {
                BRIDGES.remove(pair.channel());
                BRIDGE_MOUTHS.remove(pair.channel());
                continue;
            }
            BRIDGES.put(pair.channel(), buildBridge(level, pair, localLattice, localSmart));
            BRIDGE_MOUTHS.put(pair.channel(), new PipeNodeId[] {pair.a(), pair.b()});
        }
        // Drop stale channels that no longer exist.
        BRIDGES.keySet().removeIf(channel -> registry.completePairs().stream()
                .noneMatch(p -> p.channel() == channel && !p.a().sameDimension(p.b())));
        BRIDGE_MOUTHS.keySet().retainAll(BRIDGES.keySet());
        invalidateRoutes();
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
            if (!LinkPipeRegistry.bothLoaded(pair.a(), pair.b())) {
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
        BRIDGE_MOUTHS.remove(channel);
        invalidateRoutes();
    }

    /** Every live cross-dim bridge. */
    public static java.util.Collection<RoutingSnapshot<PipeNodeId>> bridges() {
        return BRIDGES.values();
    }

    /**
     * Grows {@code localComponent} to include everything reachable through live cross-dim
     * bridges, in any dimension.
     *
     * <p>Anything asking "what is on my network" has to do this, because a component is only
     * ever solved per level: the Autocraft Monitor two chunks from a link pipe is on the same
     * logical network as the crafter on the far side, and answering from the local routing
     * snapshot alone reports that network as empty.
     *
     * <p>Iterates to a fixpoint rather than taking one pass, since bridges chain: A-B and B-C
     * are separate snapshots, and reaching C from A means noticing B first.
     *
     * @param localComponent nodes already known to be on the network, in any dimension
     * @return {@code localComponent} plus every node any live bridge can reach from it
     */
    public static Set<PipeNodeId> expandAcrossLinks(Set<PipeNodeId> localComponent) {
        return expandAcrossLinks(localComponent, BRIDGES.values());
    }

    /** Testable form: same walk over an explicit bridge set. */
    static Set<PipeNodeId> expandAcrossLinks(Set<PipeNodeId> localComponent,
                                             java.util.Collection<RoutingSnapshot<PipeNodeId>> bridges) {
        if (localComponent.isEmpty() || bridges.isEmpty()) {
            return Set.copyOf(localComponent);
        }
        Set<PipeNodeId> reached = new java.util.LinkedHashSet<>(localComponent);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (RoutingSnapshot<PipeNodeId> bridge : bridges) {
                for (PipeNodeId known : List.copyOf(reached)) {
                    if (!bridge.contains(known)) {
                        continue;
                    }
                    // contains() is not enough: a bridge topology can hold isolated nodes
                    // that share no path with this one.
                    for (PipeNodeId node : bridge.nodes()) {
                        if (!reached.contains(node) && bridge.canReach(known, node)) {
                            reached.add(node);
                            grew = true;
                        }
                    }
                }
            }
        }
        return reached;
    }

    public static void clear() {
        BRIDGES.clear();
        BRIDGE_MOUTHS.clear();
        invalidateRoutes();
    }

    /**
     * Next hop from {@code from} toward {@code to} across any live cross-dim bridge.
     * Empty when no bridge can actually reach both ends.
     *
     * <p>A bridge topology may {@code contain} isolated smart nodes that share no path -
     * catalog uses {@code canReach}, so commit must too. Trying the first bridge that
     * merely contains both endpoints used to return empty and skip later bridges that
     * did have a route.
     */
    public static java.util.Optional<PipeNodeId> nextHop(PipeNodeId from, PipeNodeId to) {
        if (from.equals(to)) {
            return java.util.Optional.empty();
        }
        return routeCache().computeIfAbsent(new RouteKey(from, to),
                key -> solveNextHop(key.from(), key.to()));
    }

    private static java.util.Optional<PipeNodeId> solveNextHop(PipeNodeId from, PipeNodeId to) {
        // One bridge spanning both ends is the common case.
        for (RoutingSnapshot<PipeNodeId> bridge : BRIDGES.values()) {
            if (bridge.canReach(from, to)) {
                return bridge.nextHop(from, to);
            }
        }
        return chainedNextHop(from, to);
    }

    /**
     * First hop of a route that has to cross more than one link.
     *
     * <p>Overworld to the nether and back again is two channels, so no single bridge holds
     * both ends - and because the journey starts and finishes in the same dimension, the old
     * same-dimension short circuit refused it outright. Searches mouth to mouth instead:
     * reach one channel's link, then continue on whichever bridge shares it.
     *
     * <p>Breadth first, so the fewest links wins. Bridges are one per interdimensional
     * channel, so the search space is the number of link pairs, not the number of pipes.
     */
    private static java.util.Optional<PipeNodeId> chainedNextHop(PipeNodeId from, PipeNodeId to) {
        if (BRIDGES.size() < 2) {
            return java.util.Optional.empty();
        }
        // Node reached -> the very first hop taken out of `from` to get there.
        Map<PipeNodeId, PipeNodeId> firstHop = new java.util.HashMap<>();
        Deque<PipeNodeId> queue = new ArrayDeque<>();
        queue.add(from);
        Set<PipeNodeId> seen = new HashSet<>();
        seen.add(from);

        while (!queue.isEmpty()) {
            PipeNodeId at = queue.removeFirst();
            for (Map.Entry<Integer, RoutingSnapshot<PipeNodeId>> entry : BRIDGES.entrySet()) {
                RoutingSnapshot<PipeNodeId> bridge = entry.getValue();
                if (!bridge.contains(at)) {
                    continue;
                }
                if (bridge.canReach(at, to)) {
                    return java.util.Optional.ofNullable(
                            at.equals(from) ? bridge.nextHop(at, to).orElse(null) : firstHop.get(at));
                }
                for (PipeNodeId[] mouths : BRIDGE_MOUTHS.values()) {
                    for (PipeNodeId mouth : mouths) {
                        if (seen.contains(mouth) || !bridge.canReach(at, mouth)) {
                            continue;
                        }
                        PipeNodeId hop = at.equals(from)
                                ? bridge.nextHop(at, mouth).orElse(null)
                                : firstHop.get(at);
                        if (hop == null) {
                            continue;
                        }
                        seen.add(mouth);
                        firstHop.put(mouth, hop);
                        queue.addLast(mouth);
                    }
                }
            }
        }
        return java.util.Optional.empty();
    }

    public static boolean canReach(PipeNodeId from, PipeNodeId to) {
        return nextHop(from, to).isPresent() || from.equals(to);
    }
}

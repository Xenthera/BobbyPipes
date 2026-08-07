package com.bobby.bobbypipes.logistics.power;

import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.block.entity.PowerJunctionBlockEntity;
import com.bobby.bobbypipes.logistics.LinkPipeRegistry;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.PipeNodeId;
import com.bobby.bobbypipes.logistics.RoutingSnapshot;
import com.bobby.bobbypipes.logistics.Topology;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Per-level logistics power: discovers junctions on the pipe lattice, spends FE for
 * routing actions, samples usage for the junction GUI, and paints powered state.
 */
public final class ComponentPower {

    private final ServerLevel level;
    private final PipeNetwork network;

    private Topology<BlockPos> lattice = Topology.empty();
    /** Junction → one adjacent lattice pipe used as the routing attachment. */
    private Map<BlockPos, BlockPos> attachmentPipe = Map.of();
    private Set<BlockPos> junctions = Set.of();
    /** Junction → every junction sharing its lattice component, including itself. */
    private Map<BlockPos, List<BlockPos>> networkPeers = Map.of();
    /** Lattice pipe → component id. Same-dim link edges are already folded into the lattice. */
    private Map<BlockPos, Integer> componentOfPipe = Map.of();
    private Map<Integer, List<BlockPos>> componentJunctions = Map.of();
    /** Component id → its local endpoints of interdimensional link pairs. */
    private Map<Integer, List<PipeNodeId>> componentLinks = Map.of();
    /**
     * One sampler per junction, keyed by position so history survives topology rebuilds.
     * Junctions on the same network all receive the same events, so their graphs agree.
     */
    private final Map<BlockPos, PowerUsageSampler> samplers = new HashMap<>();
    private final Set<Long> linkTollPaid = new HashSet<>();

    public ComponentPower(ServerLevel level, PipeNetwork network) {
        this.level = level;
        this.network = network;
    }

    public PowerUsageSampler samplerFor(BlockPos junction) {
        return samplers.computeIfAbsent(junction.immutable(),
                pos -> new PowerUsageSampler(LogisticsPowerCosts.SAMPLE_HISTORY));
    }

    public Set<BlockPos> junctions() {
        return junctions;
    }

    /** Re-scans the lattice for adjacent power junctions after a topology rebuild. */
    public void rediscover(Topology<BlockPos> newLattice) {
        this.lattice = newLattice;
        Map<BlockPos, BlockPos> attachments = new HashMap<>();
        for (BlockPos pipe : newLattice.nodes()) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = pipe.relative(direction);
                if (level.getBlockEntity(neighbour) instanceof PowerJunctionBlockEntity) {
                    attachments.putIfAbsent(neighbour.immutable(), pipe.immutable());
                }
            }
        }
        attachmentPipe = Map.copyOf(attachments);
        junctions = Set.copyOf(attachments.keySet());
        regroupNetworks();
        refreshPoweredPaint();
    }

    /** Buckets junctions by lattice component so usage is sampled per network, not per level. */
    private void regroupNetworks() {
        Map<BlockPos, Integer> ids = new HashMap<>();
        int nextId = 0;
        for (BlockPos node : lattice.nodes()) {
            if (ids.containsKey(node)) {
                continue;
            }
            int id = nextId++;
            for (BlockPos member : flood(node)) {
                ids.put(member, id);
            }
        }

        Map<Integer, List<BlockPos>> grouped = new HashMap<>();
        for (Map.Entry<BlockPos, BlockPos> entry : attachmentPipe.entrySet()) {
            Integer id = ids.get(entry.getValue());
            if (id != null) {
                grouped.computeIfAbsent(id, key -> new ArrayList<>()).add(entry.getKey());
            }
        }
        Map<BlockPos, List<BlockPos>> peers = new HashMap<>();
        Map<Integer, List<BlockPos>> byComponent = new HashMap<>();
        for (Map.Entry<Integer, List<BlockPos>> entry : grouped.entrySet()) {
            List<BlockPos> shared = List.copyOf(entry.getValue());
            byComponent.put(entry.getKey(), shared);
            for (BlockPos member : shared) {
                peers.put(member, shared);
            }
        }

        componentOfPipe = Map.copyOf(ids);
        componentJunctions = Map.copyOf(byComponent);
        componentLinks = mapInterdimLinks(ids);
        networkPeers = Map.copyOf(peers);
        samplers.keySet().retainAll(junctions);
    }

    /**
     * Interdimensional link ends per component. Same-dimension pairs already contribute a
     * virtual lattice edge, so the flood above covers them; cross-dim ends do not exist on
     * this level's lattice and are hopped explicitly when looking for junctions.
     */
    private Map<Integer, List<PipeNodeId>> mapInterdimLinks(Map<BlockPos, Integer> ids) {
        Map<Integer, List<PipeNodeId>> links = new HashMap<>();
        for (LinkPipeRegistry.PipePair pair : LinkPipeRegistry.get(level).completePairs()) {
            if (pair.a().sameDimension(pair.b())) {
                continue;
            }
            for (PipeNodeId end : List.of(pair.a(), pair.b())) {
                if (!end.dimension().equals(level.dimension())) {
                    continue;
                }
                Integer id = ids.get(end.pos());
                if (id != null) {
                    links.computeIfAbsent(id, key -> new ArrayList<>()).add(end);
                }
            }
        }
        return Map.copyOf(links);
    }

    private List<BlockPos> peersOf(BlockPos junction) {
        return networkPeers.getOrDefault(junction, List.of(junction));
    }

    private void recordSpend(BlockPos payingJunction, PowerSpendKind kind, int fe) {
        for (BlockPos peer : peersOf(payingJunction)) {
            samplerFor(peer).recordSpend(kind, fe);
        }
    }

    /** Component containing this pipe, or the first component beside it. -1 when off-lattice. */
    private int componentIdAt(BlockPos pos) {
        Integer id = componentOfPipe.get(pos);
        if (id != null) {
            return id;
        }
        for (Direction direction : Direction.values()) {
            id = componentOfPipe.get(pos.relative(direction));
            if (id != null) {
                return id;
            }
        }
        return -1;
    }

    /**
     * Junctions in other dimensions reachable through live interdimensional link pairs,
     * paired with the {@link ComponentPower} that owns them so spends land in the right
     * network's usage graph.
     */
    private List<LinkedJunction> linkedJunctions(BlockPos actor) {
        int startId = componentIdAt(actor);
        if (startId < 0) {
            return List.of();
        }
        List<LinkedJunction> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<Hop> queue = new ArrayDeque<>();
        seen.add(domainKey(this, startId));
        queue.add(new Hop(this, startId));
        while (!queue.isEmpty()) {
            Hop hop = queue.removeFirst();
            for (PipeNodeId endpoint : hop.power().componentLinks.getOrDefault(hop.componentId(), List.of())) {
                ServerLevel from = hop.power().level;
                PipeNodeId peer = LinkPipeRegistry.get(from).peerOf(endpoint).orElse(null);
                if (peer == null || !from.hasChunkAt(endpoint.pos())) {
                    continue;
                }
                ServerLevel remoteLevel = LinkPipeRegistry.levelOf(from.getServer(), peer);
                if (remoteLevel == null || !remoteLevel.hasChunkAt(peer.pos())) {
                    continue;
                }
                ComponentPower remote = PipeNetwork.get(remoteLevel).power();
                int remoteId = remote.componentIdAt(peer.pos());
                if (remoteId < 0 || !seen.add(domainKey(remote, remoteId))) {
                    continue;
                }
                for (BlockPos pos : remote.componentJunctions.getOrDefault(remoteId, List.of())) {
                    if (remoteLevel.getBlockEntity(pos) instanceof PowerJunctionBlockEntity junction) {
                        found.add(new LinkedJunction(remote, junction));
                    }
                }
                queue.add(new Hop(remote, remoteId));
            }
        }
        return found;
    }

    private static String domainKey(ComponentPower power, int componentId) {
        return power.level.dimension().identifier() + "#" + componentId;
    }

    private record Hop(ComponentPower power, int componentId) {
    }

    private record LinkedJunction(ComponentPower owner, PowerJunctionBlockEntity junction) {
    }

    public void tick() {
        if (level.getGameTime() % LogisticsPowerCosts.SAMPLE_PERIOD_TICKS == 0) {
            for (BlockPos pos : junctions) {
                if (level.getBlockEntity(pos) instanceof PowerJunctionBlockEntity junction) {
                    if (junction.lastTickInserted() > 0) {
                        for (BlockPos peer : peersOf(pos)) {
                            samplerFor(peer).recordInput(junction.lastTickInserted());
                        }
                    }
                    junction.clearTickMeters();
                }
            }
            for (BlockPos pos : junctions) {
                samplerFor(pos).flushSample();
            }
        }
        if (level.getGameTime() % 10 == 0) {
            refreshPoweredPaint();
        }
        PowerJunctionMenus.syncOpenMenus(level, this);
    }

    public boolean isPowered(BlockPos actor) {
        return canAfford(actor, LogisticsPowerCosts.MIN_CHECK);
    }

    public boolean canAfford(BlockPos actor, int fe) {
        if (fe <= 0) {
            return true;
        }
        for (PowerJunctionBlockEntity junction : reachableJunctions(actor)) {
            if (junction.canExtractExact(fe)) {
                return true;
            }
        }
        for (LinkedJunction linked : linkedJunctions(actor)) {
            if (linked.junction().canExtractExact(fe)) {
                return true;
            }
        }
        return false;
    }

    public boolean trySpend(BlockPos actor, PowerSpendKind kind) {
        return trySpend(actor, kind, LogisticsPowerCosts.costOf(kind));
    }

    public boolean trySpend(BlockPos actor, PowerSpendKind kind, int fe) {
        if (fe <= 0) {
            return true;
        }
        List<ScoredJunction> candidates = scoredJunctions(actor);
        candidates.sort(Comparator.comparingInt(ScoredJunction::cost));
        for (ScoredJunction scored : candidates) {
            if (scored.junction().tryExtractExact(fe)) {
                recordSpend(scored.junction().getBlockPos(), kind, fe);
                return true;
            }
        }
        // Nothing local can pay — reach across interdimensional links.
        for (LinkedJunction linked : linkedJunctions(actor)) {
            if (linked.junction().tryExtractExact(fe)) {
                linked.owner().recordSpend(linked.junction().getBlockPos(), kind, fe);
                return true;
            }
        }
        return false;
    }

    public boolean trySpendLinkSame(BlockPos from, BlockPos to, long parcelId) {
        if (!linkTollPaid.add(parcelId)) {
            return true;
        }
        int cost = LogisticsPowerCosts.linkSameDimCost(from, to);
        if (trySpend(from, PowerSpendKind.LINK_SAME, cost)) {
            return true;
        }
        linkTollPaid.remove(parcelId);
        return false;
    }

    public boolean trySpendLinkInterdim(BlockPos from, PipeNodeId peer, long parcelId) {
        if (!linkTollPaid.add(parcelId)) {
            return true;
        }
        int cost = LogisticsPowerCosts.linkInterdimCost();
        if (trySpend(from, PowerSpendKind.LINK_INTERDIM, cost)) {
            return true;
        }
        linkTollPaid.remove(parcelId);
        return false;
    }

    public void clearLinkToll(long parcelId) {
        linkTollPaid.remove(parcelId);
    }

    private List<PowerJunctionBlockEntity> reachableJunctions(BlockPos actor) {
        List<PowerJunctionBlockEntity> list = new ArrayList<>();
        for (ScoredJunction scored : scoredJunctions(actor)) {
            list.add(scored.junction());
        }
        return list;
    }

    private List<ScoredJunction> scoredJunctions(BlockPos actor) {
        Set<BlockPos> component = latticeComponent(actor);
        if (component.isEmpty() && lattice.contains(actor)) {
            component = Set.of(actor);
        }
        List<ScoredJunction> list = new ArrayList<>();
        RoutingSnapshot<BlockPos> routes = network.routes();
        for (Map.Entry<BlockPos, BlockPos> entry : attachmentPipe.entrySet()) {
            if (!component.contains(entry.getValue())) {
                continue;
            }
            if (!(level.getBlockEntity(entry.getKey()) instanceof PowerJunctionBlockEntity junction)) {
                continue;
            }
            BlockPos attachment = entry.getValue();
            int cost = Integer.MAX_VALUE / 4;
            if (actor.equals(attachment)) {
                cost = 0;
            } else if (routes.contains(actor) && routes.contains(attachment)) {
                cost = routes.cost(actor, attachment).orElse(Integer.MAX_VALUE / 4);
            } else {
                cost = actor.distManhattan(attachment);
            }
            list.add(new ScoredJunction(junction, cost));
        }
        return list;
    }

    private Set<BlockPos> latticeComponent(BlockPos seed) {
        if (!lattice.contains(seed)) {
            // Actor might be a junction neighbour — try adjacent lattice pipes.
            Set<BlockPos> merged = new HashSet<>();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = seed.relative(direction);
                if (lattice.contains(neighbour)) {
                    merged.addAll(flood(neighbour));
                }
            }
            return merged;
        }
        return flood(seed);
    }

    private Set<BlockPos> flood(BlockPos seed) {
        Set<BlockPos> seen = new HashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(seed);
        seen.add(seed);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (BlockPos neighbour : lattice.neighbours(at).keySet()) {
                if (seen.add(neighbour)) {
                    queue.add(neighbour);
                }
            }
        }
        return seen;
    }

    private void refreshPoweredPaint() {
        // One affordability probe per component: it now walks link hops, so per-pipe would be costly.
        Map<Integer, Boolean> poweredByComponent = new HashMap<>();
        for (BlockPos pos : network.smartPipes()) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof RoutedPipeBlock)
                    || !state.hasProperty(RoutedPipeBlock.POWERED)) {
                continue;
            }
            int componentId = componentIdAt(pos);
            boolean powered = componentId < 0
                    ? canAfford(pos, LogisticsPowerCosts.MIN_CHECK)
                    : poweredByComponent.computeIfAbsent(componentId,
                            key -> canAfford(pos, LogisticsPowerCosts.MIN_CHECK));
            if (state.getValue(RoutedPipeBlock.POWERED) != powered) {
                level.setBlock(pos, state.setValue(RoutedPipeBlock.POWERED, powered),
                        Block.UPDATE_CLIENTS);
            }
        }
    }

    private record ScoredJunction(PowerJunctionBlockEntity junction, int cost) {
    }
}

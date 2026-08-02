package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The pipe network for one level: reads pipes out of the world into a {@link Topology}
 * and keeps the solved routes in a {@link RoutingCache}.
 *
 * <p>One instance per {@link ServerLevel}. Networks do not currently cross dimensions, so
 * a block position is enough to identify a node, and block positions are stable across
 * reloads for free.
 */
public final class PipeNetwork {

    /**
     * Per-level instances. Weak keys so an unloaded level can be collected even if
     * {@link #forget} was missed; {@link #forget} is still the intended path.
     */
    private static final Map<ServerLevel, PipeNetwork> INSTANCES = new WeakHashMap<>();

    /** Cap on a single graph read, so a pathological world cannot stall the server. */
    private static final int MAX_NODES = 20_000;

    /** Ticks a parcel spends crossing one pipe. */
    private static final int TICKS_PER_HOP = 5;

    private final ServerLevel level;
    private final RoutingCache<BlockPos> cache = new RoutingCache<>();
    private final DeliveryLedger<BlockPos, ItemResource> ledger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, ItemResource> parcels = new ParcelTracker<>(TICKS_PER_HOP);

    private PipeNetwork(ServerLevel level) {
        this.level = level;
    }

    /** Outstanding promises on this network. */
    public DeliveryLedger<BlockPos, ItemResource> ledger() {
        return ledger;
    }

    /** Items currently moving on this network. */
    public ParcelTracker<BlockPos, ItemResource> parcels() {
        return parcels;
    }

    /** A view of what this network can offer {@code requester}, nearest provider first. */
    public NetworkSupply supplyFor(BlockPos requester) {
        return new NetworkSupply(level, cache.current(), requester, ledger);
    }

    public static synchronized PipeNetwork get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, PipeNetwork::new);
    }

    /** Drops the network for a level that is unloading. */
    public static synchronized void forget(ServerLevel level) {
        PipeNetwork network = INSTANCES.remove(level);
        if (network != null) {
            network.cache.clear();
        }
    }

    public RoutingSnapshot<BlockPos> routes() {
        return cache.current();
    }

    public long revision() {
        return cache.revision();
    }

    public boolean isDirty() {
        return cache.isDirty();
    }

    /**
     * Notes that the pipe layout changed near {@code origin}.
     *
     * <p>Called from block place and break. The world is still mid-update at that point,
     * so the actual read is deferred to the next tick, by which time the change has
     * settled and any neighbouring changes in the same batch have landed too.
     */
    public void invalidate(BlockPos origin) {
        BlockPos seed = origin.immutable();
        cache.invalidate(() -> readWorld(seed));
    }

    /**
     * Advances the network by one tick: rebuild if the layout changed, move parcels, and
     * release promises whose deadline has passed.
     *
     * @return true if the routing graph was rebuilt this tick
     */
    public boolean tick() {
        boolean rebuilt = cache.rebuildIfDirty();

        // Nothing injects parcels yet, so this is a no-op in practice. It is wired now so
        // that when the Request pipe lands the only new work is consuming the report:
        // settling promises for deliveries, and dropping stranded payloads into the world
        // at the node they gave up on.
        parcels.tick(cache.current());

        ledger.expire(level.getGameTime());
        return rebuilt;
    }

    /** Forces an immediate read and solve. Used by the debug command. */
    public RoutingSnapshot<BlockPos> rebuildNow(BlockPos seed) {
        return cache.publish(readWorld(seed));
    }

    /** The next pipe to move toward when travelling from {@code from} to {@code to}. */
    public Optional<BlockPos> nextHop(BlockPos from, BlockPos to) {
        return cache.current().nextHop(from, to);
    }

    /**
     * Walks the world outward from {@code seed} and builds the graph of connected pipes.
     *
     * <p>Only the component containing the seed is read. Unrelated pipe networks
     * elsewhere in the level are left alone, which is what keeps a break in one network
     * from costing anything in another.
     */
    private Topology<BlockPos> readWorld(BlockPos seed) {
        Topology.Builder<BlockPos> builder = Topology.builder();
        if (!isPipe(level, seed)) {
            // The seed pipe is gone, which is the normal case for a break. Start from
            // whatever pipes are still adjacent to the hole it left behind.
            boolean anyNeighbour = false;
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = seed.relative(direction);
                if (isPipe(level, neighbour)) {
                    anyNeighbour = true;
                    scanFrom(neighbour, builder);
                }
            }
            if (!anyNeighbour) {
                return Topology.empty();
            }
            return builder.build();
        }
        scanFrom(seed, builder);
        return builder.build();
    }

    private void scanFrom(BlockPos start, Topology.Builder<BlockPos> builder) {
        Set<BlockPos> seen = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        BlockPos immutableStart = start.immutable();
        seen.add(immutableStart);
        queue.add(immutableStart);
        builder.node(immutableStart);

        while (!queue.isEmpty()) {
            if (seen.size() > MAX_NODES) {
                return;
            }
            BlockPos current = queue.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = current.relative(direction).immutable();
                if (!isPipe(level, neighbour)) {
                    continue;
                }
                // Linking is idempotent and keeps the cheaper cost, so reaching the same
                // pair from both ends during the walk is harmless.
                builder.link(current, neighbour, 1);
                if (seen.add(neighbour)) {
                    queue.addLast(neighbour);
                }
            }
        }
    }

    private static boolean isPipe(LevelReader level, BlockPos pos) {
        // The chunk check matters: reading an unloaded chunk would both load it and
        // report a false absence, silently truncating the network at the chunk border.
        return level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof PipeBlock;
    }
}

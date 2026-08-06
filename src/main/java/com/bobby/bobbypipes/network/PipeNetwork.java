package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.PipeConnection;
import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenus;
import com.bobby.bobbypipes.network.payload.CraftStatusPayload;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import com.bobby.bobbypipes.registry.ModItems;
import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.EnergyShipment;
import com.bobby.bobbypipes.transit.FluidShipment;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.Parcel;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * The pipe network for one level.
 *
 * <p>Routing follows Logistics Pipes corridors: smart pipes see each other only across
 * unbranched runs of plain pipe ({@link DirectCorridors#transitTopology}). A dumb
 * T-junction breaks that run, so the arms are separate networks until a smart pipe sits
 * on the junction. Green/red marks use the same corridor rule.
 */
public final class PipeNetwork {

    /**
     * Per-level instances. Weak keys so an unloaded level can be collected even if
     * {@link #forget} was missed; {@link #forget} is still the intended path.
     */
    private static final Map<ServerLevel, PipeNetwork> INSTANCES = new WeakHashMap<>();

    /** Cap on a single graph read, so a pathological world cannot stall the server. */
    private static final int MAX_NODES = 20_000;

    /**
     * Ticks a parcel spends crossing one pipe.
     *
     * <p>Base tier speed  -  slow enough to read, and to leave headroom for faster pipe
     * tiers later. At 20 tps this is 0.4s per block (1.5x the original 12-tick hop).
     */
    private static final int TICKS_PER_HOP = 8;

    /**
     * Extra distance a hop covers when it runs down a container arm instead of just pipe
     * centre to pipe centre  -  must match {@code ParcelDebugRenderer.ARM_OFFSET}, which is
     * what actually draws that arm on the client.
     */
    private static final float ARM_OFFSET_BLOCKS = 1.0f;

    /**
     * Authoritative per-hop timing: an ordinary hop is the base rate, but the very first
     * hop off a real inventory (entry side set  -  a drift capture has none, so it stays
     * unextended) and the hop landing on the destination each cover one extra arm's worth
     * of distance, so they take proportionally longer. This is what the client renders
     * from directly ({@code ParcelSyncPayload.Entry#ticksForHop}) instead of guessing its
     * own duration  -  there is nothing left for it to fall out of sync with, because the
     * server actually takes this long, not just fixed-tick-always with a slower-looking
     * render layered on top.
     */
    private static final ParcelTracker.HopLength<BlockPos, ItemShipment> HOP_LENGTH =
            (at, next, origin, destination, payload) -> {
                float blocks = 1.0f;
                if (at.equals(origin) && payload.entrySide() != null) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                if (next.equals(destination)) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                return Math.round(TICKS_PER_HOP * blocks);
            };

    /**
     * Same shape as {@link #HOP_LENGTH}, for energy parcels. Fluid will get an identical
     * one when it is built. Kept as a separate constant (not shared/generic) because the
     * two payload types are unrelated records and Java cannot express "any payload with an
     * entrySide" without a shared interface that neither wants to implement for one field.
     */
    private static final ParcelTracker.HopLength<BlockPos, EnergyShipment> ENERGY_HOP_LENGTH =
            (at, next, origin, destination, payload) -> {
                float blocks = 1.0f;
                if (at.equals(origin) && payload.entrySide() != null) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                if (next.equals(destination)) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                return Math.round(TICKS_PER_HOP * blocks);
            };

    /** Same shape as {@link #HOP_LENGTH}, for fluid parcels. */
    private static final ParcelTracker.HopLength<BlockPos, FluidShipment> FLUID_HOP_LENGTH =
            (at, next, origin, destination, payload) -> {
                float blocks = 1.0f;
                if (at.equals(origin) && payload.entrySide() != null) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                if (next.equals(destination)) {
                    blocks += ARM_OFFSET_BLOCKS;
                }
                return Math.round(TICKS_PER_HOP * blocks);
            };

    /**
     * High bit tag so energy parcel ids never collide with item parcel ids in the client's
     * single {@code visuals} map, the same trick drift already uses (negation) for its own
     * id space. Each {@link ParcelTracker} numbers its own parcels from 1, so without this
     * an item parcel and an energy parcel could easily share a raw id.
     */
    private static final long ENERGY_ID_TAG = 1L << 61;

    /** Same reasoning as {@link #ENERGY_ID_TAG}, a different bit so fluid never collides
     * with item, energy, or drift ids either. */
    private static final long FLUID_ID_TAG = 1L << 60;

    private final ServerLevel level;
    private final RoutingCache<BlockPos> cache = new RoutingCache<>();
    private final DeliveryLedger<BlockPos, ItemResource> ledger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, ItemShipment> parcels =
            new ParcelTracker<>(TICKS_PER_HOP, HOP_LENGTH);
    private final DeliveryLedger<BlockPos, EnergyKind> energyLedger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, EnergyShipment> energyParcels =
            new ParcelTracker<>(TICKS_PER_HOP, ENERGY_HOP_LENGTH);
    private final DeliveryLedger<BlockPos, FluidResource> fluidLedger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, FluidShipment> fluidParcels =
            new ParcelTracker<>(TICKS_PER_HOP, FLUID_HOP_LENGTH);
    private final DriftTracker drift;
    /** Shared by providers, crafters, and any other pipe that extracts from inventories. */
    private final ExtractPulseBudget<BlockPos> extractBudget = ExtractPulseBudget.basic();
    private final ProviderSendQueue sendQueue = new ProviderSendQueue(extractBudget);
    /** Separate budget from items: energy amounts are a different scale than item counts. */
    private final ExtractPulseBudget<BlockPos> energyExtractBudget = EnergySendQueue.defaultBudget();
    private final EnergySendQueue energySendQueue = new EnergySendQueue(energyExtractBudget);
    private final ExtractPulseBudget<BlockPos> fluidExtractBudget = FluidSendQueue.defaultBudget();
    private final FluidSendQueue fluidSendQueue = new FluidSendQueue(fluidExtractBudget);
    private final CraftJobManager craftJobs = new CraftJobManager();

    /** Last scanned pipe lattice; used to paint routed-exit marks after a rebuild. */
    private Topology<BlockPos> lastLattice = Topology.empty();
    private Set<BlockPos> lastSmart = Set.of();

    /**
     * Seeds queued by {@link #invalidate} since the last rebuild. Collapsing place/break
     * bursts into one solve still has to re-scan every touched component on world load,
     * where the previous snapshot is empty.
     */
    private final Set<BlockPos> pendingSeeds = new HashSet<>();

    /** Avoid spamming empty parcel snapshots once the network is quiet. */
    private boolean lastParcelSyncWasEmpty = true;

    private PipeNetwork(ServerLevel level) {
        this.level = level;
        this.drift = new DriftTracker(level);
    }

    /** Items wandering through plain pipe, outside the planned network. */
    public DriftTracker drift() {
        return drift;
    }

    /** Outstanding promises on this network. */
    public DeliveryLedger<BlockPos, ItemResource> ledger() {
        return ledger;
    }

    /** Items currently moving on this network. */
    public ParcelTracker<BlockPos, ItemShipment> parcels() {
        return parcels;
    }

    /** Outstanding energy promises on this network. */
    public DeliveryLedger<BlockPos, EnergyKind> energyLedger() {
        return energyLedger;
    }

    /** Energy currently moving on this network, on the same routing graph as items. */
    public ParcelTracker<BlockPos, EnergyShipment> energyParcels() {
        return energyParcels;
    }

    /** Energy withdrawals waiting to leave providers at their send rate. */
    public EnergySendQueue energySendQueue() {
        return energySendQueue;
    }

    /** Outstanding fluid promises on this network. */
    public DeliveryLedger<BlockPos, FluidResource> fluidLedger() {
        return fluidLedger;
    }

    /** Fluid currently moving on this network, on the same routing graph as items. */
    public ParcelTracker<BlockPos, FluidShipment> fluidParcels() {
        return fluidParcels;
    }

    /** Fluid withdrawals waiting to leave providers at their send rate. */
    public FluidSendQueue fluidSendQueue() {
        return fluidSendQueue;
    }

    /** Withdrawals waiting to leave providers at their send rate. */
    public ProviderSendQueue sendQueue() {
        return sendQueue;
    }

    /** Per-pipe extract pulse budget shared by every extracting pipe on this network. */
    public ExtractPulseBudget<BlockPos> extractBudget() {
        return extractBudget;
    }

    /** Autocraft jobs waiting on inputs or mid-chain. */
    public CraftJobManager craftJobs() {
        return craftJobs;
    }

    /** A view of what this network can offer {@code requester}, nearest provider first. */
    public NetworkSupply supplyFor(BlockPos requester) {
        return new NetworkSupply(level, cache.current(), requester, sendQueue);
    }

    /**
     * Like {@link #supplyFor(BlockPos)}, but never offers stock from {@code excludedStores}
     * (used by Supplier pipes so they cannot restock by draining their own chest).
     */
    public NetworkSupply supplyFor(BlockPos requester, Set<Object> excludedStores) {
        return new NetworkSupply(level, cache.current(), requester, sendQueue, excludedStores);
    }

    /**
     * Items already queued or flying toward {@code dest} (not yet inserted).
     *
     * <p>Suppliers subtract this from their shortfall so they do not re-order while a
     * previous restock is still in transit.
     */
    public int inboundTo(BlockPos dest, ItemResource item) {
        return CraftJobManager.inboundTo(this, dest, item);
    }

    public static synchronized PipeNetwork get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, PipeNetwork::new);
    }

    /** Drops the network for a level that is unloading. */
    public static synchronized void forget(ServerLevel level) {
        PipeNetwork network = INSTANCES.remove(level);
        if (network != null) {
            synchronized (network.pendingSeeds) {
                network.pendingSeeds.clear();
            }
            network.cache.clear();
            network.drift.clear();
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
     *
     * <p>Seeds accumulate until the next rebuild so a burst of chunk loads on boot can
     * rediscover every loaded component, not only the last one invalidated.
     */
    public void invalidate(BlockPos origin) {
        BlockPos seed = origin.immutable();
        synchronized (pendingSeeds) {
            pendingSeeds.add(seed);
        }
        cache.invalidate(this::readPendingWorld);
    }

    /**
     * Advances the network by one tick: rebuild if the layout changed, move parcels, and
     * release promises whose deadline has passed.
     *
     * @return true if the routing graph was rebuilt this tick
     */
    public boolean tick() {
        boolean rebuilt = cache.rebuildIfDirty();
        if (rebuilt) {
            applyArmMarks();
        }

        craftJobs.tick(level, this);
        sendQueue.tick(level, ledger, parcels, cache.current());
        energySendQueue.tick(level, energyLedger, energyParcels, cache.current());
        fluidSendQueue.tick(level, fluidLedger, fluidParcels, cache.current());
        RequestService.handle(level, this, parcels.tick(cache.current()));
        EnergyRequestService.handle(level, this, energyParcels.tick(cache.current()));
        FluidRequestService.handle(level, this, fluidParcels.tick(cache.current()));
        drift.tick(this);
        syncParcels();
        if (level.getGameTime() % 10 == 0) {
            AutocraftMonitorMenus.syncOpenMenus(level);
        }
        syncCraftStatus();

        ledger.expire(level.getGameTime());
        energyLedger.expire(level.getGameTime());
        fluidLedger.expire(level.getGameTime());
        return rebuilt;
    }

    /**
     * Pushes the current in-flight parcels to every player in this dimension.
     *
     * <p>Full replace each tick while anything is moving  -  fine for debug volumes.
     * Sends one empty snapshot when the last parcel settles so clients clear leftovers.
     */
    /** How often the craft debug overlay refreshes. Text does not need per-tick updates. */
    private static final int CRAFT_STATUS_INTERVAL = 10;

    private boolean lastCraftStatusWasEmpty;

    private void syncCraftStatus() {
        if (level.players().isEmpty() || level.getGameTime() % CRAFT_STATUS_INTERVAL != 0) {
            return;
        }
        List<CraftStatusPayload.Entry> entries = craftJobs.holograms(level, this);
        if (entries.isEmpty()) {
            // One empty packet clears the overlay; repeating it every tick would not.
            if (!lastCraftStatusWasEmpty) {
                PacketDistributor.sendToPlayersInDimension(level, new CraftStatusPayload(List.of()));
                lastCraftStatusWasEmpty = true;
            }
            return;
        }
        lastCraftStatusWasEmpty = false;
        PacketDistributor.sendToPlayersInDimension(level, new CraftStatusPayload(entries));
    }

    private void syncParcels() {
        if (level.players().isEmpty()) {
            return;
        }
        long gameTime = level.getGameTime();
        if (parcels.inFlight() == 0 && drift.inFlight() == 0 && energyParcels.inFlight() == 0
                && fluidParcels.inFlight() == 0) {
            if (!lastParcelSyncWasEmpty) {
                PacketDistributor.sendToPlayersInDimension(
                        level, new ParcelSyncPayload(TICKS_PER_HOP, gameTime, List.of()));
                lastParcelSyncWasEmpty = true;
            }
            return;
        }
        lastParcelSyncWasEmpty = false;

        List<ParcelSyncPayload.Entry> entries = new ArrayList<>(
                parcels.inFlight() + energyParcels.inFlight() + fluidParcels.inFlight());
        for (Parcel<BlockPos, ItemShipment> parcel : parcels.parcels()) {
            ItemShipment shipment = parcel.payload();
            ItemStack stack = shipment.resource().toStack(shipment.count());
            if (stack.isEmpty()) {
                continue;
            }
            // Only the ends of a journey touch a container, so the arm offsets are sent
            // only for those hops. Everything in between runs centre to centre, which is
            // already a straight line through the arms joining two adjacent pipes.
            //
            // The entry arm is the one the shipment recorded when it was pulled, not
            // whichever container happens to sit next to the origin. Guessing meant a
            // parcel handed over by a drifting item  -  which came in through pipe and
            // touched no container at all  -  started in some unrelated chest's arm and
            // visibly jumped sideways to the pipe centre before setting off.
            Optional<net.minecraft.core.Direction> enterFrom =
                    parcel.atNode().equals(parcel.origin())
                            ? Optional.ofNullable(shipment.entrySide())
                            : Optional.empty();
            Optional<net.minecraft.core.Direction> exitTo =
                    parcel.nextHop() != null && parcel.nextHop().equals(parcel.destination())
                            ? InventoryAccess.sideAccepting(
                                    level, parcel.destination(), shipment.resource())
                            : Optional.empty();

            entries.add(new ParcelSyncPayload.Entry(
                    parcel.id(),
                    parcel.atNode(),
                    Optional.ofNullable(parcel.nextHop()),
                    parcel.ticksIntoHop(),
                    parcel.ticksForHop(),
                    stack,
                    enterFrom,
                    exitTo,
                    true));
        }
        // Energy parcels ride the same sync, on the same shared routing graph as items,
        // just tagged into a disjoint id range so they never collide with an item parcel's
        // id in the client's single visuals map (see ENERGY_ID_TAG).
        for (Parcel<BlockPos, EnergyShipment> parcel : energyParcels.parcels()) {
            EnergyShipment shipment = parcel.payload();
            ItemStack stack = new ItemStack(ModItems.ENERGY_PARCEL.get());
            if (stack.isEmpty()) {
                continue;
            }
            Optional<net.minecraft.core.Direction> enterFrom =
                    parcel.atNode().equals(parcel.origin())
                            ? Optional.ofNullable(shipment.entrySide())
                            : Optional.empty();
            Optional<net.minecraft.core.Direction> exitTo =
                    parcel.nextHop() != null && parcel.nextHop().equals(parcel.destination())
                            ? EnergyAccess.sideAccepting(level, parcel.destination())
                            : Optional.empty();

            entries.add(new ParcelSyncPayload.Entry(
                    parcel.id() | ENERGY_ID_TAG,
                    parcel.atNode(),
                    Optional.ofNullable(parcel.nextHop()),
                    parcel.ticksIntoHop(),
                    parcel.ticksForHop(),
                    stack,
                    enterFrom,
                    exitTo,
                    true));
        }
        // Fluid parcels, same reasoning as energy above but with their own id range
        // (FLUID_ID_TAG) so neither collides with the other or with items/drift.
        for (Parcel<BlockPos, FluidShipment> parcel : fluidParcels.parcels()) {
            FluidShipment shipment = parcel.payload();
            ItemStack stack = new ItemStack(ModItems.FLUID_PARCEL.get());
            if (stack.isEmpty()) {
                continue;
            }
            Optional<net.minecraft.core.Direction> enterFrom =
                    parcel.atNode().equals(parcel.origin())
                            ? Optional.ofNullable(shipment.entrySide())
                            : Optional.empty();
            Optional<net.minecraft.core.Direction> exitTo =
                    parcel.nextHop() != null && parcel.nextHop().equals(parcel.destination())
                            ? FluidAccess.sideAccepting(level, parcel.destination(), shipment.resource())
                            : Optional.empty();

            entries.add(new ParcelSyncPayload.Entry(
                    parcel.id() | FLUID_ID_TAG,
                    parcel.atNode(),
                    Optional.ofNullable(parcel.nextHop()),
                    parcel.ticksIntoHop(),
                    parcel.ticksForHop(),
                    stack,
                    enterFrom,
                    exitTo,
                    true));
        }
        // Drifting items ride the same sync so they draw like anything else in a pipe.
        // ticksIntoHop is the raw drift clock (longer than routed); the client uses
        // {@link DriftTracker#SPEED_FACTOR} to size the hop, so do not rescale into
        // routed ticks here or both speeds collapse to the same look.
        for (DriftTracker.Drifting drifting : drift.items()) {
            ItemStack stack = drifting.item().toStack(drifting.count());
            if (stack.isEmpty()) {
                continue;
            }
            // An item with no next hop is parked: waiting at a routed pipe for the network
            // to take it, or freshly inserted and about to pick a side. Skipping those made
            // it vanish for a second at exactly the dumb-to-smart boundary and then pop back
            // into existence at the pipe centre, so they are sent with no hop instead and
            // the renderer rests them where they stand.
            // An item leaving into a container runs out along one arm instead of on to
            // another pipe. Sending its own position as the next node gives the renderer a
            // path of centre -> arm, so it is animated out the same way a routed parcel is
            // animated into its destination rather than vanishing at the pipe centre.
            // On its very first hop the side it came from is whatever pushed it in, so an
            // item fed by a hopper sets off from that arm. On later hops that side is just
            // the pipe behind it, and prepending its arm would draw the item backing up
            // before moving on, so only the first hop gets one.
            Optional<net.minecraft.core.Direction> pushedIn =
                    drifting.hops() == 0 && drifting.cameFrom() != null
                            && !isPipe(level, drifting.at().relative(drifting.cameFrom()))
                            ? Optional.of(drifting.cameFrom())
                            : Optional.empty();
            entries.add(new ParcelSyncPayload.Entry(
                    -drifting.id(),
                    drifting.at(),
                    drifting.leaving()
                            ? Optional.of(drifting.at())
                            : Optional.ofNullable(drifting.next()),
                    drifting.ticksIntoHop(),
                    drifting.ticksForHop(),
                    stack,
                    pushedIn,
                    Optional.ofNullable(drifting.exitTo()),
                    false));
        }

        PacketDistributor.sendToPlayersInDimension(
                level, new ParcelSyncPayload(TICKS_PER_HOP, gameTime, entries));
    }

    /**
     * Forces an immediate read and solve.
     *
     * <p>No-ops when the corridor graph is unchanged so callers that poll (supplier
     * restock, open menus) cannot republish a new revision every second and stall every
     * in-flight parcel while the solver runs.
     */
    public RoutingSnapshot<BlockPos> rebuildNow(BlockPos seed) {
        Topology<BlockPos> topology = readWorld(seed);
        RoutingSnapshot<BlockPos> current = cache.current();
        if (topology.equals(current.topology())) {
            return current;
        }
        RoutingSnapshot<BlockPos> snapshot = cache.publish(topology);
        applyArmMarks();
        return snapshot;
    }

    /**
     * Paints green/red exit marks on routed pipes only.
     *
     * <p>Plain transport pipes never receive marks. Uses {@link Block#UPDATE_CLIENTS} so
     * clients see the new marks without neighbour updates that would fight connection
     * logic or retrigger a rebuild.
     */
    private void applyArmMarks() {
        Set<DirectCorridors.Edge<BlockPos>> routedExits =
                DirectCorridors.routedExits(lastLattice, lastSmart);

        for (BlockPos pos : lastSmart) {
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof RoutedPipeBlock)) {
                continue;
            }
            BlockState updated = state;
            for (Direction direction : Direction.values()) {
                PipeConnection current = updated.getValue(PipeBlock.propertyFor(direction));
                if (!current.isPipe()) {
                    continue;
                }
                BlockPos neighbour = pos.relative(direction);
                PipeConnection desired =
                        routedExits.contains(new DirectCorridors.Edge<>(pos, neighbour))
                                ? PipeConnection.DIRECT
                                : PipeConnection.INDIRECT;
                if (current != desired) {
                    updated = updated.setValue(PipeBlock.propertyFor(direction), desired);
                }
            }
            if (!updated.equals(state)) {
                level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
            }
        }

        // Strip any leftover DIRECT marks from plain pipes in the lattice (e.g. worlds
        // that had marks before routers were split out).
        for (BlockPos pos : lastLattice.nodes()) {
            if (lastSmart.contains(pos)) {
                continue;
            }
            BlockState state = level.getBlockState(pos);
            if (!(state.getBlock() instanceof PipeBlock) || state.getBlock() instanceof RoutedPipeBlock) {
                continue;
            }
            BlockState updated = state;
            for (Direction direction : Direction.values()) {
                if (updated.getValue(PipeBlock.propertyFor(direction)) == PipeConnection.DIRECT) {
                    updated = updated.setValue(PipeBlock.propertyFor(direction),
                            PipeConnection.INDIRECT);
                }
            }
            if (!updated.equals(state)) {
                level.setBlock(pos, updated, Block.UPDATE_CLIENTS);
            }
        }
    }

    /** The next pipe to move toward when travelling from {@code from} to {@code to}. */
    public Optional<BlockPos> nextHop(BlockPos from, BlockPos to) {
        return cache.current().nextHop(from, to);
    }

    /**
     * Walks the world outward from {@code seed}, merges any other live components, then
     * publishes the corridor transit graph (not the raw lattice).
     *
     * <p>The full lattice is kept for mark painting. Pathfinding uses only edges that lie
     * on unbranched smart-to-smart corridors, so a plain-pipe junction does not join the
     * networks on its arms.
     *
     * <p>Publishing only the seed's component used to wipe every other network in the
     * level on a single place/break, which stranded unrelated parcels  -  hence the merge.
     */
    private Topology<BlockPos> readPendingWorld() {
        Set<BlockPos> seeds;
        synchronized (pendingSeeds) {
            seeds = Set.copyOf(pendingSeeds);
            pendingSeeds.clear();
        }
        return readWorld(seeds);
    }

    private Topology<BlockPos> readWorld(BlockPos seed) {
        return readWorld(Set.of(seed.immutable()));
    }

    private Topology<BlockPos> readWorld(Set<BlockPos> seeds) {
        Topology.Builder<BlockPos> builder = Topology.builder();
        Set<BlockPos> covered = new HashSet<>();
        for (BlockPos seed : seeds) {
            absorbComponent(seed, builder, covered);
        }

        // Previous snapshot nodes may be corridor intermediates; also re-scan from every
        // smart pipe still in the world so disconnected components are not dropped.
        for (BlockPos node : List.copyOf(cache.current().nodes())) {
            if (covered.contains(node) || !isPipe(level, node)) {
                continue;
            }
            absorbComponent(node, builder, covered);
        }

        Topology<BlockPos> lattice = builder.build();
        lastLattice = lattice;
        lastSmart = Set.copyOf(smartNodes(lattice));
        return DirectCorridors.transitTopology(lattice, lastSmart);
    }

    /** Scans the component containing {@code seed} into {@code builder}. */
    private void absorbComponent(BlockPos seed,
                                 Topology.Builder<BlockPos> builder,
                                 Set<BlockPos> covered) {
        Topology<BlockPos> scanned = scanLattice(seed);
        for (BlockPos node : scanned.nodes()) {
            builder.node(node);
            covered.add(node);
            for (Map.Entry<BlockPos, Integer> edge : scanned.neighbours(node).entrySet()) {
                builder.link(node, edge.getKey(), edge.getValue());
            }
        }
    }

    private Topology<BlockPos> scanLattice(BlockPos seed) {
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

    private Set<BlockPos> smartNodes(Topology<BlockPos> lattice) {
        Set<BlockPos> smart = new HashSet<>();
        for (BlockPos pos : lattice.nodes()) {
            if (PipeBlock.isSmartPipe(level.getBlockState(pos).getBlock())) {
                smart.add(pos);
            }
        }
        return smart;
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

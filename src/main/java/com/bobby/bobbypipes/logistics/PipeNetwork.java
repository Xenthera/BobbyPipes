package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.LinkPipeBlock;
import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.PipeConnection;
import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.menu.AutocraftMonitorMenus;
import com.bobby.bobbypipes.network.payload.CraftStatusPayload;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import com.bobby.bobbypipes.logistics.power.ComponentPower;
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
import java.util.HashMap;
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
     * <p>Base tier speed - slow enough to read, and to leave headroom for faster pipe
     * tiers later. At 20 tps this is 0.4s per block (1.5x the original 12-tick hop).
     */
    private static final int TICKS_PER_HOP = 8;

    /**
     * Extra distance when a hop runs an inventory arm (enter or exit). Must match
     * {@code ParcelRenderer.ARM_OFFSET}.
     */
    private static final float ARM_OFFSET_BLOCKS = 1.0f;

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
    private final ParcelTracker<BlockPos, ItemShipment> parcels;
    private final DeliveryLedger<BlockPos, EnergyKind> energyLedger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, EnergyShipment> energyParcels;
    private final DeliveryLedger<BlockPos, FluidResource> fluidLedger = new DeliveryLedger<>();
    private final ParcelTracker<BlockPos, FluidShipment> fluidParcels;
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
    private final ComponentPower power;

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

    /**
     * Cross-dim trips keyed by parcel id while the parcel is still on this level:
     * either staging toward the local link, or mid-wormhole hop toward the peer.
     */
    private final Map<Long, CrossDimTrip> itemCrossTrips = new HashMap<>();
    private final Map<Long, CrossDimTrip> energyCrossTrips = new HashMap<>();
    private final Map<Long, CrossDimTrip> fluidCrossTrips = new HashMap<>();

    private record CrossDimTrip(PipeNodeId finalDest, PipeNodeId peer, boolean wormhole) {
        CrossDimTrip asWormhole() {
            return new CrossDimTrip(finalDest, peer, true);
        }
    }

    private PipeNetwork(ServerLevel level) {
        this.level = level;
        this.power = new ComponentPower(level, this);
        this.drift = new DriftTracker(level);
        this.parcels = new ParcelTracker<>(TICKS_PER_HOP, this::itemHopTicks);
        this.energyParcels = new ParcelTracker<>(TICKS_PER_HOP, this::energyHopTicks);
        this.fluidParcels = new ParcelTracker<>(TICKS_PER_HOP, this::fluidHopTicks);
        this.parcels.setHopBeginGate(this::allowParcelHop);
        this.energyParcels.setHopBeginGate(this::allowParcelHop);
        this.fluidParcels.setHopBeginGate(this::allowParcelHop);
    }

    public ComponentPower power() {
        return power;
    }

    public Set<BlockPos> smartPipes() {
        return lastSmart;
    }

    /**
     * Same-dimension link hops pay distance FE here; ordinary lattice hops are free.
     * Cross-dim handoffs are charged in {@link #promoteStagingDeliveries}.
     */
    private <P> boolean allowParcelHop(Parcel<BlockPos, P> parcel) {
        BlockPos at = parcel.atNode();
        BlockPos next = parcel.nextHop();
        if (next == null) {
            return true;
        }
        PipeNodeId atId = PipeNodeId.of(level, at);
        Optional<PipeNodeId> peer = LinkPipeRegistry.get(level).peerOf(atId);
        if (peer.isEmpty() || !peer.get().pos().equals(next) || !peer.get().sameDimension(atId)) {
            return true;
        }
        return power.trySpendLinkSame(at, next, parcel.id());
    }

    /**
     * Centre-to-centre is 1 block. Enter/exit inventory arms add {@link #ARM_OFFSET_BLOCKS}
     * only when the renderer will actually draw that arm (entrySide set, or an accepting
     * inventory beside the destination). Charging an exit arm for every hop that lands on
     * the destination (link mouths, bare request pipes, etc.) made those hops crawl.
     */
    private static int hopTicks(float blocks) {
        return Math.max(1, Math.round(TICKS_PER_HOP * blocks));
    }

    private int itemHopTicks(BlockPos at, BlockPos next, BlockPos origin, BlockPos destination,
                             ItemShipment payload) {
        float blocks = 1.0f;
        if (at.equals(origin) && payload.entrySide() != null) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        if (next.equals(destination)
                && InventoryAccess.sideAccepting(level, destination, payload.resource()).isPresent()) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        return hopTicks(blocks);
    }

    private int energyHopTicks(BlockPos at, BlockPos next, BlockPos origin, BlockPos destination,
                               EnergyShipment payload) {
        float blocks = 1.0f;
        if (at.equals(origin) && payload.entrySide() != null) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        if (next.equals(destination) && EnergyAccess.sideAccepting(level, destination).isPresent()) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        return hopTicks(blocks);
    }

    private int fluidHopTicks(BlockPos at, BlockPos next, BlockPos origin, BlockPos destination,
                              FluidShipment payload) {
        float blocks = 1.0f;
        if (at.equals(origin) && payload.entrySide() != null) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        if (next.equals(destination)
                && FluidAccess.sideAccepting(level, destination, payload.resource()).isPresent()) {
            blocks += ARM_OFFSET_BLOCKS;
        }
        return hopTicks(blocks);
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
        return CraftJobManager.inboundTo(this, PipeNodeId.of(level, dest), item);
    }

    /**
     * Items of {@code item} currently flying on this network toward {@code dest}, including
     * parcels still staging for a cross-dim handoff whose final destination is {@code dest}.
     */
    int flyingItemsToward(PipeNodeId dest, ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int flying = 0;
        for (Parcel<BlockPos, ItemShipment> parcel : parcels.parcels()) {
            if (!parcel.payload().resource().equals(item)) {
                continue;
            }
            CrossDimTrip trip = itemCrossTrips.get(parcel.id());
            if (trip != null) {
                if (trip.finalDest().equals(dest)) {
                    flying += parcel.payload().count();
                }
                continue;
            }
            if (dest.dimension().equals(level.dimension())
                    && parcel.destination().equals(dest.pos())) {
                flying += parcel.payload().count();
            }
        }
        return flying;
    }

    ServerLevel level() {
        return level;
    }

    /**
     * Cancels flying item parcels headed for {@code dest} (local destination or cross-dim
     * {@code finalDest}). Invokes {@code onCancel} with the node the parcel was at and the
     * cancelled shipment so the caller can reclaim.
     */
    void cancelParcelsToward(PipeNodeId dest,
                             ItemResource item,
                             java.util.function.BiConsumer<BlockPos, ItemShipment> onCancel) {
        List<Parcel<BlockPos, ItemShipment>> flying = new ArrayList<>();
        for (Parcel<BlockPos, ItemShipment> parcel : parcels.parcels()) {
            if (item != null && !item.isEmpty() && !parcel.payload().resource().equals(item)) {
                continue;
            }
            CrossDimTrip trip = itemCrossTrips.get(parcel.id());
            if (trip != null) {
                if (trip.finalDest().equals(dest)) {
                    flying.add(parcel);
                }
                continue;
            }
            if (dest.dimension().equals(level.dimension())
                    && parcel.destination().equals(dest.pos())) {
                flying.add(parcel);
            }
        }
        for (Parcel<BlockPos, ItemShipment> parcel : flying) {
            BlockPos at = parcel.atNode();
            parcels.cancel(parcel.id()).ifPresent(shipment -> {
                itemCrossTrips.remove(parcel.id());
                onCancel.accept(at, shipment);
            });
        }
    }

    /** Cancels every flying item parcel headed for {@code dest}, any item. */
    void cancelParcelsToward(PipeNodeId dest,
                             java.util.function.BiConsumer<BlockPos, ItemShipment> onCancel) {
        cancelParcelsToward(dest, ItemResource.EMPTY, onCancel);
    }

    public static synchronized PipeNetwork get(ServerLevel level) {
        return INSTANCES.computeIfAbsent(level, PipeNetwork::new);
    }

    /** Live per-level instances (does not create empty networks). */
    public static synchronized List<PipeNetwork> instances() {
        return List.copyOf(INSTANCES.values());
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

    /**
     * Last physical lattice from a rebuild (includes same-dim link edges, before corridor
     * collapse). Empty until the first successful rebuild for this level.
     */
    public Topology<BlockPos> physicalLattice() {
        return lastLattice;
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
        power.tick();

        craftJobs.tick(level, this);
        sendQueue.tick(level, this, ledger, parcels, cache.current());
        energySendQueue.tick(level, this, energyLedger, energyParcels, cache.current());
        fluidSendQueue.tick(level, this, fluidLedger, fluidParcels, cache.current());
        handoffCompletingWormholes(parcels, itemCrossTrips, this::acceptItemHandoff);
        handoffCompletingWormholes(energyParcels, energyCrossTrips, this::acceptEnergyHandoff);
        handoffCompletingWormholes(fluidParcels, fluidCrossTrips, this::acceptFluidHandoff);
        RequestService.handle(level, this,
                promoteStagingDeliveries(parcels.tick(cache.current()), parcels, itemCrossTrips));
        EnergyRequestService.handle(level, this,
                promoteStagingDeliveries(energyParcels.tick(cache.current()), energyParcels, energyCrossTrips));
        FluidRequestService.handle(level, this,
                promoteStagingDeliveries(fluidParcels.tick(cache.current()), fluidParcels, fluidCrossTrips));
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
     * <p>Full replace each tick while anything is moving - fine for debug volumes.
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
            // parcel handed over by a drifting item - which came in through pipe and
            // touched no container at all - started in some unrelated chest's arm and
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
                    true,
                    ParcelSyncPayload.Entry.NO_TIER,
                    linkHopFor(parcel)));
        }
        // Energy parcels ride the same sync, on the same shared routing graph as items,
        // just tagged into a disjoint id range so they never collide with an item parcel's
        // id in the client's single visuals map (see ENERGY_ID_TAG).
        for (Parcel<BlockPos, EnergyShipment> parcel : energyParcels.parcels()) {
            EnergyShipment shipment = parcel.payload();
            ItemStack stack = new ItemStack(ModItems.energyParcel(shipment.tier()));
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
                    true,
                    shipment.tier().wireId(),
                    linkHopFor(parcel)));
        }
        // Fluid parcels, same reasoning as energy above but with their own id range
        // (FLUID_ID_TAG) so neither collides with the other or with items/drift.
        for (Parcel<BlockPos, FluidShipment> parcel : fluidParcels.parcels()) {
            FluidShipment shipment = parcel.payload();
            ItemStack stack = new ItemStack(ModItems.fluidParcel(shipment.tier()));
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
                    true,
                    shipment.tier().wireId(),
                    linkHopFor(parcel)));
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
                    false,
                    ParcelSyncPayload.Entry.NO_TIER,
                    false));
        }

        PacketDistributor.sendToPlayersInDimension(
                level, new ParcelSyncPayload(TICKS_PER_HOP, gameTime, entries));
    }

    private static boolean linkHopFor(Parcel<BlockPos, ?> parcel) {
        BlockPos next = parcel.nextHop();
        return next != null && ParcelSyncPayload.isLinkHop(parcel.atNode(), next);
    }

    /**
     * Injects a parcel headed for {@code to}, staging through a link when {@code to} is in
     * another dimension.
     */
    public Optional<Long> injectItemToward(ItemShipment shipment, BlockPos from, PipeNodeId to) {
        return injectToward(parcels, itemCrossTrips, shipment, from, to);
    }

    public Optional<Long> injectEnergyToward(EnergyShipment shipment, BlockPos from, PipeNodeId to) {
        return injectToward(energyParcels, energyCrossTrips, shipment, from, to);
    }

    public Optional<Long> injectFluidToward(FluidShipment shipment, BlockPos from, PipeNodeId to) {
        return injectToward(fluidParcels, fluidCrossTrips, shipment, from, to);
    }

    private <P> Optional<Long> injectToward(ParcelTracker<BlockPos, P> tracker,
                                            Map<Long, CrossDimTrip> trips,
                                            P shipment,
                                            BlockPos from,
                                            PipeNodeId to) {
        PipeNodeId fromId = PipeNodeId.of(level, from);
        if (fromId.sameDimension(to)) {
            return tracker.inject(shipment, from, to.pos(), cache.current());
        }
        Optional<PipeNodeId> staging = stagingLink(fromId, to);
        if (staging.isEmpty()) {
            return Optional.empty();
        }
        PipeNodeId link = staging.get();
        Optional<PipeNodeId> peer = LinkPipeRegistry.get(level).peerOf(link);
        if (peer.isEmpty() || peer.get().sameDimension(link)) {
            return Optional.empty();
        }
        // Cross-dim next hop may not exist on the per-level snapshot alone (wormhole is
        // outside the local lattice). Prefer LinkTransit so staging matches canDeliver.
        if (fromId.equals(link)) {
            Optional<Long> id = tracker.inject(shipment, from, link.pos(), cache.current());
            id.ifPresent(parcelId -> trips.put(parcelId, new CrossDimTrip(to, peer.get(), false)));
            return id;
        }
        Optional<PipeNodeId> hop = LinkTransit.nextHop(level, cache.current(), fromId, link);
        if (hop.isEmpty() || !hop.get().sameDimension(fromId)) {
            return Optional.empty();
        }
        Optional<Long> id = tracker.injectWithFirstHop(
                shipment, from, link.pos(), hop.get().pos(), cache.current().revision());
        id.ifPresent(parcelId -> trips.put(parcelId, new CrossDimTrip(to, peer.get(), false)));
        return id;
    }

    /** Local link pipe on the path from {@code from} to a remote {@code to}. */
    private Optional<PipeNodeId> stagingLink(PipeNodeId from, PipeNodeId to) {
        PipeNodeId cursor = from;
        for (int i = 0; i < MAX_NODES; i++) {
            Optional<PipeNodeId> hop = LinkTransit.nextHop(level, cache.current(), cursor, to);
            if (hop.isEmpty()) {
                return Optional.empty();
            }
            PipeNodeId next = hop.get();
            if (!next.sameDimension(cursor)) {
                return Optional.of(cursor);
            }
            cursor = next;
        }
        return Optional.empty();
    }

    public boolean canDeliver(PipeNodeId from, PipeNodeId to) {
        return LinkTransit.canReach(level, cache.current(), from, to);
    }

    /**
     * Resolves a provider {@code source} that may sit across a live cross-dim link from
     * {@code requester}.
     */
    public Optional<PipeNodeId> resolveSource(BlockPos requester, BlockPos source) {
        if (cache.current().contains(source)) {
            return Optional.of(PipeNodeId.of(level, source));
        }
        PipeNodeId req = PipeNodeId.of(level, requester);
        for (RoutingSnapshot<PipeNodeId> bridge : CrossDimPipeGraph.bridges()) {
            if (!bridge.contains(req)) {
                continue;
            }
            for (PipeNodeId node : bridge.topology().nodes()) {
                if (node.pos().equals(source)
                        && !node.dimension().equals(level.dimension())
                        && bridge.canReach(req, node)) {
                    return Optional.of(node);
                }
            }
        }
        return Optional.empty();
    }

    private void acceptItemHandoff(ItemShipment shipment, PipeNodeId at, PipeNodeId dest) {
        ServerLevel atLevel = LinkPipeRegistry.levelOf(level.getServer(), at);
        if (atLevel == null) {
            InventoryAccess.insertOrDrop(level, at.pos(), shipment.resource(), shipment.count());
            ledger.cancel(shipment.promiseId());
            return;
        }
        if (atLevel != level) {
            PipeNetwork.get(atLevel).acceptItemHandoff(shipment, at, dest);
            return;
        }
        continueAfterHandoff(parcels, itemCrossTrips, shipment, at, dest);
    }

    private void acceptEnergyHandoff(EnergyShipment shipment, PipeNodeId at, PipeNodeId dest) {
        ServerLevel atLevel = LinkPipeRegistry.levelOf(level.getServer(), at);
        if (atLevel == null) {
            energyLedger.cancel(shipment.promiseId());
            return;
        }
        if (atLevel != level) {
            PipeNetwork.get(atLevel).acceptEnergyHandoff(shipment, at, dest);
            return;
        }
        continueAfterHandoff(energyParcels, energyCrossTrips, shipment, at, dest);
    }

    private void acceptFluidHandoff(FluidShipment shipment, PipeNodeId at, PipeNodeId dest) {
        ServerLevel atLevel = LinkPipeRegistry.levelOf(level.getServer(), at);
        if (atLevel == null) {
            fluidLedger.cancel(shipment.promiseId());
            return;
        }
        if (atLevel != level) {
            PipeNetwork.get(atLevel).acceptFluidHandoff(shipment, at, dest);
            return;
        }
        continueAfterHandoff(fluidParcels, fluidCrossTrips, shipment, at, dest);
    }

    private <P> void continueAfterHandoff(ParcelTracker<BlockPos, P> tracker,
                                          Map<Long, CrossDimTrip> trips,
                                          P shipment,
                                          PipeNodeId at,
                                          PipeNodeId dest) {
        // Drop the source-side extract arm: that Direction is meaningless on the peer and
        // makes the client animate out of the wrong face (looks like request↔link bounce).
        P emerged = clearEntrySide(shipment);
        if (at.equals(dest)) {
            tracker.inject(emerged, at.pos(), dest.pos(), cache.current());
            return;
        }
        if (!at.sameDimension(dest) || !at.dimension().equals(level.dimension())) {
            // Still need another wormhole (chained links) - stage again.
            injectToward(tracker, trips, emerged, at.pos(), dest);
            return;
        }
        // Local routes only. LinkTransit / CrossDim must not choose the return wormhole as
        // the first hop or the parcel bounces link ↔ neighbour forever.
        Optional<BlockPos> onward = cache.current().nextHop(at.pos(), dest.pos());
        if (onward.isEmpty() || !isCardinalNeighbour(at.pos(), onward.get()) || !isPipe(level, onward.get())) {
            tracker.inject(emerged, at.pos(), dest.pos(), cache.current());
            return;
        }
        tracker.injectWithFirstHop(
                emerged, at.pos(), dest.pos(), onward.get(), cache.current().revision());
    }

    @SuppressWarnings("unchecked")
    private static <P> P clearEntrySide(P shipment) {
        if (shipment instanceof ItemShipment items) {
            return (P) items.withoutEntrySide();
        }
        if (shipment instanceof EnergyShipment energy) {
            return (P) energy.withoutEntrySide();
        }
        if (shipment instanceof FluidShipment fluid) {
            return (P) fluid.withoutEntrySide();
        }
        return shipment;
    }

    private static boolean isCardinalNeighbour(BlockPos a, BlockPos b) {
        return a.distManhattan(b) == 1;
    }

    @FunctionalInterface
    private interface HandoffAccept<P> {
        void accept(P shipment, PipeNodeId at, PipeNodeId dest);
    }

    private <P> void handoffCompletingWormholes(ParcelTracker<BlockPos, P> tracker,
                                                Map<Long, CrossDimTrip> trips,
                                                HandoffAccept<P> sink) {
        // Legacy in-flight wormhole hops (source-tracker fake hops toward peer BlockPos).
        // New cross-dim traffic hands off immediately in {@link #promoteStagingDeliveries}.
        List<Long> done = new ArrayList<>();
        for (Parcel<BlockPos, P> parcel : List.copyOf(tracker.parcels())) {
            CrossDimTrip trip = trips.get(parcel.id());
            if (trip == null || !trip.wormhole()) {
                continue;
            }
            if (parcel.nextHop() == null || !parcel.nextHop().equals(trip.peer().pos())) {
                continue;
            }
            if (parcel.ticksIntoHop() + 1 < parcel.ticksForHop()) {
                continue;
            }
            Optional<P> payload = tracker.cancel(parcel.id());
            done.add(parcel.id());
            payload.ifPresent(p -> sink.accept(p, trip.peer(), trip.finalDest()));
        }
        done.forEach(trips::remove);
    }

    /**
     * Staging deliveries that reached a cross-dim link mouth hand off into the peer
     * dimension immediately. A fake BlockPos hop toward {@code peer.pos()} on the source
     * tracker is unsafe: coordinates collide across dimensions and cardinally-adjacent
     * peer coords look like a normal pipe hop, which desyncs clients and can bounce the
     * parcel on the far side.
     */
    private <P> ParcelTracker.TickReport<BlockPos, P> promoteStagingDeliveries(
            ParcelTracker.TickReport<BlockPos, P> report,
            ParcelTracker<BlockPos, P> tracker,
            Map<Long, CrossDimTrip> trips) {
        if (report.delivered().isEmpty()) {
            return report;
        }
        List<ParcelTracker.Delivery<BlockPos, P>> kept = new ArrayList<>();
        for (ParcelTracker.Delivery<BlockPos, P> delivery : report.delivered()) {
            CrossDimTrip trip = trips.get(delivery.id());
            if (trip == null) {
                kept.add(delivery);
                continue;
            }
            // Pay interdim link toll before leaving this level; stall (re-queue) if brownout.
            if (!power.trySpendLinkInterdim(delivery.destination(), trip.peer(), delivery.id())) {
                requeueStaging(tracker, trips, delivery, trip);
                continue;
            }
            trips.remove(delivery.id());
            if (trip.wormhole()) {
                // Should have been consumed by handoffCompletingWormholes; never insert
                // into an inventory at the peer's BlockPos in the wrong dimension.
                handoffStaging(tracker, delivery.payload(), trip);
                continue;
            }
            handoffStaging(tracker, delivery.payload(), trip);
        }
        if (kept.size() == report.delivered().size()) {
            return report;
        }
        return new ParcelTracker.TickReport<>(List.copyOf(kept), report.stranded());
    }

    /**
     * Puts a brownout-stalled staging delivery back on the local link mouth so the next
     * tick can retry the interdim toll without voiding the parcel.
     */
    private <P> void requeueStaging(ParcelTracker<BlockPos, P> tracker,
                                    Map<Long, CrossDimTrip> trips,
                                    ParcelTracker.Delivery<BlockPos, P> delivery,
                                    CrossDimTrip trip) {
        power.clearLinkToll(delivery.id());
        trips.remove(delivery.id());
        Optional<Long> id = tracker.injectContinuing(
                delivery.payload(),
                delivery.destination(),
                delivery.destination(),
                null,
                TICKS_PER_HOP,
                cache.current().revision());
        id.ifPresent(parcelId -> trips.put(parcelId, trip));
    }

    private <P> void handoffStaging(ParcelTracker<BlockPos, P> tracker, P payload, CrossDimTrip trip) {
        if (tracker == parcels) {
            acceptItemHandoff((ItemShipment) payload, trip.peer(), trip.finalDest());
        } else if (tracker == energyParcels) {
            acceptEnergyHandoff((EnergyShipment) payload, trip.peer(), trip.finalDest());
        } else {
            acceptFluidHandoff((FluidShipment) payload, trip.peer(), trip.finalDest());
        }
    }

    /**
     * Settles an item promise on whichever level's ledger still holds it (cross-dim handoff
     * delivers on the destination network while the promise was opened on the source).
     */
    public static synchronized void settleItemDelivery(long promiseId, int count) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.ledger.recordDelivery(promiseId, count).isPresent()) {
                return;
            }
        }
    }

    public static synchronized void cancelItemPromise(long promiseId) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.ledger.cancel(promiseId).isPresent()) {
                return;
            }
        }
    }

    public static synchronized void settleEnergyDelivery(long promiseId, int amountFe) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.energyLedger.recordDelivery(promiseId, amountFe).isPresent()) {
                return;
            }
        }
    }

    public static synchronized void cancelEnergyPromise(long promiseId) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.energyLedger.cancel(promiseId).isPresent()) {
                return;
            }
        }
    }

    public static synchronized void settleFluidDelivery(long promiseId, int amountMb) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.fluidLedger.recordDelivery(promiseId, amountMb).isPresent()) {
                return;
            }
        }
    }

    public static synchronized void cancelFluidPromise(long promiseId) {
        for (PipeNetwork network : INSTANCES.values()) {
            if (network.fluidLedger.cancel(promiseId).isPresent()) {
                return;
            }
        }
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
     * level on a single place/break, which stranded unrelated parcels - hence the merge.
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

        injectLinkEdges(builder);
        Topology<BlockPos> lattice = builder.build();
        lastLattice = lattice;
        lastSmart = Set.copyOf(smartNodes(lattice));
        CrossDimPipeGraph.refreshAfterRebuild(level, lattice, lastSmart);
        power.rediscover(lattice);
        return DirectCorridors.transitTopology(lattice, lastSmart);
    }

    /**
     * Cost-1 virtual adjacencies for complete link-pipe pairs in this dimension.
     * Cross-dimension pairs are handled by {@link CrossDimPipeGraph}.
     */
    private void injectLinkEdges(Topology.Builder<BlockPos> builder) {
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
            builder.node(pair.a().pos());
            builder.node(pair.b().pos());
            builder.link(pair.a().pos(), pair.b().pos(), 1);
        }
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
            Block block = level.getBlockState(pos).getBlock();
            // Link mouths are corridor endpoints so a cross-dim wormhole (no local virtual
            // edge) is still reachable for staging parcels and arm marks. Same-dim pairs
            // already get a degree-2 virtual edge; treating the mouth as smart still
            // routes BasicA → Link → peer Link → BasicB correctly.
            if (PipeBlock.isSmartPipe(block) || block instanceof LinkPipeBlock) {
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

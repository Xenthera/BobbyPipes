package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.transit.EnergyShipment;
import com.bobby.bobbypipes.transit.FluidShipment;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.Parcel;
import com.bobby.bobbypipes.transit.ParcelCodecs;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Everything the pipe network was in the middle of doing, saved with the level.
 *
 * <p>{@link PipeNetwork} is otherwise pure in-memory state, so before this a reload discarded
 * every craft job and queued withdrawal, and silently voided every item in a pipe. Block
 * entities keep their own NBT, which is why patterns and inventories used to survive while the
 * work in flight did not.
 *
 * <p>The snapshot is pulled from the live network at write time rather than mirrored as the
 * network changes. That way whatever vanilla writes is current, with no dependency on a save
 * event firing before {@code SavedDataStorage} runs.
 */
public final class PipeNetworkSavedData extends SavedData {

    // ------------------------------------------------------------------ shapes

    /** A cross-dimension trip in progress, keyed by the parcel making it. */
    public record CrossTrip(long parcelId, PipeNodeId finalDest, PipeNodeId peer,
                            boolean wormhole) {

        public static final Codec<CrossTrip> CODEC =
                RecordCodecBuilder.create(instance -> instance.group(
                        Codec.LONG.fieldOf("parcel").forGetter(CrossTrip::parcelId),
                        PipeNodeId.CODEC.fieldOf("final_dest").forGetter(CrossTrip::finalDest),
                        PipeNodeId.CODEC.fieldOf("peer").forGetter(CrossTrip::peer),
                        Codec.BOOL.fieldOf("wormhole").forGetter(CrossTrip::wormhole)
                ).apply(instance, CrossTrip::new));
    }

    /** One medium's in-flight parcels and the promises they settle. */
    public record Medium<P>(List<Parcel<BlockPos, P>> parcels,
                            List<DeliveryLedger.Promise<BlockPos, ?>> promises,
                            List<CrossTrip> crossTrips) {
    }

    private static <I> Codec<DeliveryLedger.Promise<BlockPos, I>> promiseCodec(Codec<I> item) {
        return RecordCodecBuilder.create(instance -> instance.group(
                Codec.LONG.fieldOf("id").forGetter(DeliveryLedger.Promise::id),
                BlockPos.CODEC.fieldOf("source").forGetter(DeliveryLedger.Promise::source),
                BlockPos.CODEC.fieldOf("requester").forGetter(DeliveryLedger.Promise::requester),
                item.fieldOf("item").forGetter(DeliveryLedger.Promise::item),
                Codec.INT.fieldOf("amount").forGetter(DeliveryLedger.Promise::amount),
                Codec.INT.fieldOf("delivered").forGetter(DeliveryLedger.Promise::delivered),
                Codec.LONG.fieldOf("deadline").forGetter(DeliveryLedger.Promise::deadline)
        ).apply(instance, DeliveryLedger.Promise::new));
    }

    /**
     * Energy promises all carry the same stand-in identity, so this writes a constant and
     * reads back the only value there is.
     */
    private static final Codec<EnergyKind> ENERGY_KIND =
            Codec.STRING.xmap(ignored -> EnergyKind.ENERGY, kind -> "energy");

    // ------------------------------------------------------------------ snapshot

    /**
     * The whole of a level's network state.
     *
     * <p>Routing snapshots, extract budgets and power usage history are deliberately absent -
     * all three are rebuilt from the world within a tick of loading, and persisting them would
     * only create a second source of truth to go stale.
     */
    public record Snapshot(
            CraftJobManager.Saved craftJobs,
            List<ProviderSendQueue.SavedJob> itemQueue,
            List<EnergySendQueue.SavedJob> energyQueue,
            List<FluidSendQueue.SavedJob> fluidQueue,
            List<Parcel<BlockPos, ItemShipment>> itemParcels,
            List<Parcel<BlockPos, EnergyShipment>> energyParcels,
            List<Parcel<BlockPos, FluidShipment>> fluidParcels,
            List<DeliveryLedger.Promise<BlockPos, ItemResource>> itemPromises,
            List<DeliveryLedger.Promise<BlockPos, EnergyKind>> energyPromises,
            List<DeliveryLedger.Promise<BlockPos, FluidResource>> fluidPromises,
            List<CrossTrip> itemTrips,
            List<CrossTrip> energyTrips,
            List<CrossTrip> fluidTrips,
            List<DriftTracker.Drifting> drifting,
            List<PipeNetwork.PendingArrival> pendingArrivals) {

        public static final Snapshot EMPTY = new Snapshot(
                new CraftJobManager.Saved(List.of(), 1L),
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    // ------------------------------------------------------------------ codecs

    private static final Codec<CraftJobManager.SavedInput> SAVED_INPUT =
            RecordCodecBuilder.create(instance -> instance.group(
                    ItemResource.CODEC.fieldOf("item").forGetter(CraftJobManager.SavedInput::item),
                    Codec.INT.fieldOf("amount").forGetter(CraftJobManager.SavedInput::amount),
                    Codec.BOOL.fieldOf("from_craft").forGetter(CraftJobManager.SavedInput::fromCraft),
                    PipeNodeId.CODEC.fieldOf("from").forGetter(CraftJobManager.SavedInput::from)
            ).apply(instance, CraftJobManager.SavedInput::new));

    private static final Codec<CraftJobManager.SavedOwed> SAVED_OWED =
            RecordCodecBuilder.create(instance -> instance.group(
                    PipeNodeId.CODEC.fieldOf("consumer").forGetter(CraftJobManager.SavedOwed::consumer),
                    ItemResource.CODEC.fieldOf("item").forGetter(CraftJobManager.SavedOwed::item),
                    Codec.INT.fieldOf("remaining").forGetter(CraftJobManager.SavedOwed::remaining)
            ).apply(instance, CraftJobManager.SavedOwed::new));

    private static final Codec<CraftJobManager.SavedStock> SAVED_STOCK =
            RecordCodecBuilder.create(instance -> instance.group(
                    PipeNodeId.CODEC.fieldOf("provider").forGetter(CraftJobManager.SavedStock::provider),
                    ItemResource.CODEC.fieldOf("item").forGetter(CraftJobManager.SavedStock::item),
                    Codec.INT.fieldOf("remaining").forGetter(CraftJobManager.SavedStock::remaining)
            ).apply(instance, CraftJobManager.SavedStock::new));

    private static final Codec<CraftJobManager.SavedStack> SAVED_STACK =
            RecordCodecBuilder.create(instance -> instance.group(
                    ItemResource.CODEC.fieldOf("item").forGetter(CraftJobManager.SavedStack::item),
                    Codec.INT.fieldOf("count").forGetter(CraftJobManager.SavedStack::count)
            ).apply(instance, CraftJobManager.SavedStack::new));

    private static final Codec<CraftJobManager.SavedProgress> SAVED_PROGRESS =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.BOOL.fieldOf("waiting_for_output")
                            .forGetter(CraftJobManager.SavedProgress::waitingForOutput),
                    Codec.BOOL.fieldOf("armed")
                            .forGetter(CraftJobManager.SavedProgress::armedThisRun),
                    Codec.BOOL.fieldOf("requested")
                            .forGetter(CraftJobManager.SavedProgress::requestedThisRun)
            ).apply(instance, CraftJobManager.SavedProgress::new));

    private static final Codec<CraftJobManager.SavedJob> SAVED_JOB =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.fieldOf("id").forGetter(CraftJobManager.SavedJob::id),
                    Codec.LONG.fieldOf("request").forGetter(CraftJobManager.SavedJob::requestId),
                    PipeNodeId.CODEC.fieldOf("crafter").forGetter(CraftJobManager.SavedJob::crafter),
                    PipeNodeId.CODEC.fieldOf("requester").forGetter(CraftJobManager.SavedJob::requester),
                    ItemResource.CODEC.fieldOf("output").forGetter(CraftJobManager.SavedJob::output),
                    Codec.INT.fieldOf("runs").forGetter(CraftJobManager.SavedJob::runsRemaining),
                    Codec.INT.fieldOf("for_requester")
                            .forGetter(CraftJobManager.SavedJob::remainingForRequester),
                    SAVED_INPUT.listOf().optionalFieldOf("inputs", List.of())
                            .forGetter(CraftJobManager.SavedJob::inputs),
                    PipeNodeId.CODEC.listOf().optionalFieldOf("depends_on", List.of())
                            .forGetter(CraftJobManager.SavedJob::dependsOn),
                    SAVED_STOCK.listOf().optionalFieldOf("stock", List.of())
                            .forGetter(CraftJobManager.SavedJob::stockRemaining),
                    SAVED_OWED.listOf().optionalFieldOf("owes", List.of())
                            .forGetter(CraftJobManager.SavedJob::owes),
                    SAVED_PROGRESS.fieldOf("progress")
                            .forGetter(CraftJobManager.SavedJob::progress),
                    SAVED_STACK.listOf().optionalFieldOf("output_left", List.of())
                            .forGetter(CraftJobManager.SavedJob::outputLeft),
                    Codec.INT.fieldOf("output_left_runs")
                            .forGetter(CraftJobManager.SavedJob::outputLeftRuns),
                    SAVED_STACK.listOf().optionalFieldOf("held", List.of())
                            .forGetter(CraftJobManager.SavedJob::held)
            ).apply(instance, CraftJobManager.SavedJob::new));

    private static final Codec<CraftJobManager.Saved> CRAFT_JOBS =
            RecordCodecBuilder.create(instance -> instance.group(
                    SAVED_JOB.listOf().optionalFieldOf("jobs", List.of())
                            .forGetter(CraftJobManager.Saved::jobs),
                    Codec.LONG.optionalFieldOf("next_request", 1L)
                            .forGetter(CraftJobManager.Saved::nextRequestId)
            ).apply(instance, CraftJobManager.Saved::new));

    private static final Codec<ProviderSendQueue.SavedJob> ITEM_QUEUE_JOB =
            RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("source").forGetter(ProviderSendQueue.SavedJob::source),
                    BlockPos.CODEC.fieldOf("dest").forGetter(ProviderSendQueue.SavedJob::dest),
                    PipeNodeId.CODEC.optionalFieldOf("dest_node").forGetter(
                            job -> Optional.ofNullable(job.destNode())),
                    ItemResource.CODEC.fieldOf("item").forGetter(ProviderSendQueue.SavedJob::item),
                    Codec.INT.fieldOf("remaining").forGetter(ProviderSendQueue.SavedJob::remaining)
            ).apply(instance, (source, dest, node, item, remaining) ->
                    new ProviderSendQueue.SavedJob(source, dest, node.orElse(null), item, remaining)));

    private static final Codec<EnergySendQueue.SavedJob> ENERGY_QUEUE_JOB =
            RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("source").forGetter(EnergySendQueue.SavedJob::source),
                    BlockPos.CODEC.fieldOf("dest").forGetter(EnergySendQueue.SavedJob::dest),
                    Codec.INT.fieldOf("remaining").forGetter(EnergySendQueue.SavedJob::remaining),
                    BlockPos.CODEC.listOf().optionalFieldOf("excluded", List.of())
                            .forGetter(EnergySendQueue.SavedJob::excluded)
            ).apply(instance, EnergySendQueue.SavedJob::new));

    private static final Codec<FluidSendQueue.SavedJob> FLUID_QUEUE_JOB =
            RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("source").forGetter(FluidSendQueue.SavedJob::source),
                    BlockPos.CODEC.fieldOf("dest").forGetter(FluidSendQueue.SavedJob::dest),
                    FluidResource.CODEC.fieldOf("fluid").forGetter(FluidSendQueue.SavedJob::fluid),
                    Codec.INT.fieldOf("remaining").forGetter(FluidSendQueue.SavedJob::remaining),
                    BlockPos.CODEC.listOf().optionalFieldOf("excluded", List.of())
                            .forGetter(FluidSendQueue.SavedJob::excluded)
            ).apply(instance, FluidSendQueue.SavedJob::new));

    private static final Codec<DriftTracker.Drifting> DRIFTING =
            RecordCodecBuilder.create(instance -> instance.group(
                    Codec.LONG.fieldOf("id").forGetter(DriftTracker.Drifting::id),
                    ItemResource.CODEC.fieldOf("item").forGetter(DriftTracker.Drifting::item),
                    Codec.INT.fieldOf("count").forGetter(DriftTracker.Drifting::count),
                    BlockPos.CODEC.fieldOf("at").forGetter(DriftTracker.Drifting::at),
                    BlockPos.CODEC.optionalFieldOf("next").forGetter(
                            d -> Optional.ofNullable(d.next())),
                    Direction.CODEC.optionalFieldOf("came_from").forGetter(
                            d -> Optional.ofNullable(d.cameFrom())),
                    Codec.INT.fieldOf("ticks_into_hop").forGetter(DriftTracker.Drifting::ticksIntoHop),
                    Codec.INT.fieldOf("hops").forGetter(DriftTracker.Drifting::hops),
                    Direction.CODEC.optionalFieldOf("exit_to").forGetter(
                            d -> Optional.ofNullable(d.exitTo())),
                    Codec.INT.fieldOf("ticks_for_hop").forGetter(DriftTracker.Drifting::ticksForHop)
            ).apply(instance, (id, item, count, at, next, cameFrom, into, hops, exit, forHop) ->
                    new DriftTracker.Drifting(id, item, count, at, next.orElse(null),
                            cameFrom.orElse(null), into, hops, exit.orElse(null), forHop)));

    private static final Codec<PipeNetwork.PendingArrival> PENDING_ARRIVAL =
            RecordCodecBuilder.create(instance -> instance.group(
                    BlockPos.CODEC.fieldOf("dest").forGetter(PipeNetwork.PendingArrival::dest),
                    ItemResource.CODEC.fieldOf("item").forGetter(PipeNetwork.PendingArrival::item),
                    Codec.INT.fieldOf("count").forGetter(PipeNetwork.PendingArrival::count),
                    Codec.LONG.fieldOf("promise").forGetter(PipeNetwork.PendingArrival::promiseId)
            ).apply(instance, PipeNetwork.PendingArrival::new));

    private static final Codec<Snapshot> SNAPSHOT =
            RecordCodecBuilder.create(instance -> instance.group(
                    CRAFT_JOBS.optionalFieldOf("craft_jobs", Snapshot.EMPTY.craftJobs())
                            .forGetter(Snapshot::craftJobs),
                    ITEM_QUEUE_JOB.listOf().optionalFieldOf("item_queue", List.of())
                            .forGetter(Snapshot::itemQueue),
                    ENERGY_QUEUE_JOB.listOf().optionalFieldOf("energy_queue", List.of())
                            .forGetter(Snapshot::energyQueue),
                    FLUID_QUEUE_JOB.listOf().optionalFieldOf("fluid_queue", List.of())
                            .forGetter(Snapshot::fluidQueue),
                    ParcelCodecs.parcel(ParcelCodecs.ITEM_SHIPMENT).listOf()
                            .optionalFieldOf("item_parcels", List.of())
                            .forGetter(Snapshot::itemParcels),
                    ParcelCodecs.parcel(ParcelCodecs.ENERGY_SHIPMENT).listOf()
                            .optionalFieldOf("energy_parcels", List.of())
                            .forGetter(Snapshot::energyParcels),
                    ParcelCodecs.parcel(ParcelCodecs.FLUID_SHIPMENT).listOf()
                            .optionalFieldOf("fluid_parcels", List.of())
                            .forGetter(Snapshot::fluidParcels),
                    promiseCodec(ItemResource.CODEC).listOf()
                            .optionalFieldOf("item_promises", List.of())
                            .forGetter(Snapshot::itemPromises),
                    promiseCodec(ENERGY_KIND).listOf()
                            .optionalFieldOf("energy_promises", List.of())
                            .forGetter(Snapshot::energyPromises),
                    promiseCodec(FluidResource.CODEC).listOf()
                            .optionalFieldOf("fluid_promises", List.of())
                            .forGetter(Snapshot::fluidPromises),
                    CrossTrip.CODEC.listOf().optionalFieldOf("item_trips", List.of())
                            .forGetter(Snapshot::itemTrips),
                    CrossTrip.CODEC.listOf().optionalFieldOf("energy_trips", List.of())
                            .forGetter(Snapshot::energyTrips),
                    CrossTrip.CODEC.listOf().optionalFieldOf("fluid_trips", List.of())
                            .forGetter(Snapshot::fluidTrips),
                    DRIFTING.listOf().optionalFieldOf("drifting", List.of())
                            .forGetter(Snapshot::drifting),
                    PENDING_ARRIVAL.listOf().optionalFieldOf("pending_arrivals", List.of())
                            .forGetter(Snapshot::pendingArrivals)
            ).apply(instance, Snapshot::new));

    public static final Codec<PipeNetworkSavedData> CODEC =
            SNAPSHOT.xmap(PipeNetworkSavedData::new, PipeNetworkSavedData::snapshotToWrite);

    public static final SavedDataType<PipeNetworkSavedData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "pipe_network"),
            PipeNetworkSavedData::new,
            CODEC);

    // ------------------------------------------------------------------ instance

    /** What was read from disk, until a network takes it. */
    private Snapshot loaded;
    /** Bound once a network exists, so writes serialize live state rather than a stale copy. */
    private Supplier<Snapshot> live;

    public PipeNetworkSavedData() {
        this(Snapshot.EMPTY);
    }

    private PipeNetworkSavedData(Snapshot loaded) {
        this.loaded = loaded;
    }

    public static PipeNetworkSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(TYPE);
    }

    /** Binds this save data to its network and hands back whatever was loaded from disk. */
    public Snapshot attach(Supplier<Snapshot> liveState) {
        this.live = liveState;
        Snapshot taken = loaded;
        // Dropped so a second attach on the same instance cannot replay old state over a
        // network that has already moved on.
        this.loaded = Snapshot.EMPTY;
        return taken == null ? Snapshot.EMPTY : taken;
    }

    private Snapshot snapshotToWrite() {
        return live != null ? live.get() : (loaded == null ? Snapshot.EMPTY : loaded);
    }
}

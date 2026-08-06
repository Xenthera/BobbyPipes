package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.Demand;
import com.bobby.bobbypipes.request.RequestPlan;
import com.bobby.bobbypipes.request.RequestPlanner;
import com.bobby.bobbypipes.transit.ItemShipment;
import com.bobby.bobbypipes.transit.ParcelTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Turns a planned request into items actually moving.
 *
 * <p>Committing accepts withdrawals onto the provider send queue. Providers then extract
 * and ship at their pulse rate; each pulse is one parcel.
 *
 * <p>A plan that cannot be fully satisfied is not committed at all. Shortfalls can still
 * happen afterwards if a chest empties before the queue drains or a route disappears
 * mid-send, but nothing is started that was known to be impossible up front.
 */
public final class RequestService {

    /** Ticks a promise may go unfulfilled before it is released. */
    static final int PROMISE_TIMEOUT_TICKS = 20 * 60;

    private RequestService() {
    }

    /**
     * What committing accepted  -  not a delivery receipt.
     *
     * <p>{@code shipped} counts items accepted onto the provider send queue plus craft jobs
     * accepted to produce the demand item. Craft amounts are <strong>not</strong> yet
     * delivered; they complete later via {@link CraftJobManager}. Prefer this field as
     * "accepted" when reading GUI/status copy.
     *
     * @param shipped   how many of the demand item were accepted (queue and/or craft)
     * @param requested how many the player asked for
     */
    public record Commitment(int shipped, int requested) {

        public boolean isComplete() {
            return shipped >= requested;
        }

        public int shortfall() {
            return Math.max(0, requested - shipped);
        }
    }

    /**
     * Result of planning (and optionally committing) a request at a pipe.
     *
     * @param plan       empty when there is no pipe at the position
     * @param commitment present only when {@code commit} was requested and a pipe existed
     */
    public record Outcome(
            RequestPlan<BlockPos, ItemResource> plan,
            Commitment commitment) {

        public static Outcome noPipe() {
            return new Outcome(null, null);
        }

        public boolean hasPipe() {
            return plan != null;
        }
    }

    /**
     * Shared entry point for the debug command and the request GUI.
     *
     * <p>Rebuilds the network around {@code at}, plans against live supply, and when
     * {@code commit} is true extracts and ships what the plan found.
     */
    public static Outcome request(ServerLevel level,
                                  BlockPos at,
                                  ItemResource item,
                                  int count,
                                  boolean commit) {
        return request(level, at, item, count, commit, java.util.Set.of());
    }

    /**
     * Like {@link #request}, but stock from {@code excludedStores} is invisible to the
     * planner (Supplier pipes exclude their own attached chest).
     */
    public static Outcome request(ServerLevel level,
                                  BlockPos at,
                                  ItemResource item,
                                  int count,
                                  boolean commit,
                                  java.util.Set<Object> excludedStores) {
        if (item.isEmpty() || count <= 0) {
            return Outcome.noPipe();
        }
        PipeNetwork network = PipeNetwork.get(level);
        if (!ensureRouted(network, at)) {
            return Outcome.noPipe();
        }
        RequestPlan<BlockPos, ItemResource> plan =
                RequestPlanner.plan(new Demand<>(item, count), network.supplyFor(at, excludedStores));
        Commitment commitment = commit
                ? RequestService.commit(level, network, plan, at, item, count, excludedStores)
                : null;
        return new Outcome(plan, commitment);
    }

    /**
     * Commits as much of {@code count} as the network can source right now.
     *
     * <p>Uses the same {@link RequestPlanner} → {@link #commit} chain as the request pipe
     * (including load-balancing a craft across every pipe with that recipe). Unlike a
     * player request that fails closed, an incomplete tree is trimmed to the satisfiable
     * amount and that smaller complete plan is committed instead.
     *
     * @return how many of the demand item were accepted onto the network
     */
    public static int requestAvailable(ServerLevel level,
                                       BlockPos at,
                                       ItemResource item,
                                       int count,
                                       java.util.Set<Object> excludedStores) {
        Commitment commitment = requestWhatYouCan(level, at, item, count, excludedStores).commitment();
        return commitment == null ? 0 : commitment.shipped();
    }

    /**
     * Player-facing request: commit whatever the network can source right now.
     *
     * <p>Each click is independent. Items already queued or in flight from earlier clicks
     * are invisible to the planner (already reserved), so spam-clicking the Request button
     * keeps dispatching until free stock / craft capacity runs out, rather than being
     * blocked by an outstanding request or failing closed when the ask exceeds remaining
     * supply.
     *
     * <p>{@link Outcome#plan()} is always the plan against the original ask, so the GUI
     * can still show what was missing. {@link Outcome#commitment()} reports shipped against
     * that same ask.
     */
    public static Outcome requestWhatYouCan(ServerLevel level,
                                            BlockPos at,
                                            ItemResource item,
                                            int count) {
        return requestWhatYouCan(level, at, item, count, java.util.Set.of());
    }

    public static Outcome requestWhatYouCan(ServerLevel level,
                                            BlockPos at,
                                            ItemResource item,
                                            int count,
                                            java.util.Set<Object> excludedStores) {
        if (item.isEmpty() || count <= 0) {
            return Outcome.noPipe();
        }
        PipeNetwork network = PipeNetwork.get(level);
        if (!ensureRouted(network, at)) {
            return Outcome.noPipe();
        }
        NetworkSupply supply = network.supplyFor(at, excludedStores);
        RequestPlan<BlockPos, ItemResource> plan =
                RequestPlanner.plan(new Demand<>(item, count), supply);
        if (plan.isEmpty()) {
            return new Outcome(plan, new Commitment(0, count));
        }
        if (plan.isComplete()) {
            // Commit this plan directly so a second re-plan cannot collapse a multi-crafter
            // split into a different shape.
            return new Outcome(plan, commit(level, network, plan, at, item, count, excludedStores));
        }
        int want = satisfiableAmount(plan, item);
        if (want <= 0) {
            return new Outcome(plan, new Commitment(0, count));
        }
        RequestPlan<BlockPos, ItemResource> partial =
                RequestPlanner.plan(new Demand<>(item, want), supply);
        if (!partial.isComplete()) {
            return new Outcome(plan, new Commitment(0, count));
        }
        Commitment commitment = commit(level, network, partial, at, item, want, excludedStores);
        return new Outcome(plan, new Commitment(commitment.shipped(), count));
    }

    /**
     * Live routes when the cache is clean; otherwise an immediate rebuild.
     *
     * <p>Supplier restock used to call {@link PipeNetwork#rebuildNow} every second, which
     * re-solved the whole graph on a metronome and hitchd parcel motion.
     */
    private static boolean ensureRouted(PipeNetwork network, BlockPos at) {
        if (!network.isDirty() && network.routes().contains(at)) {
            return true;
        }
        return network.rebuildNow(at).contains(at);
    }

    /** How many of {@code item} this (possibly incomplete) plan would deliver to the requester. */
    static int satisfiableAmount(RequestPlan<BlockPos, ItemResource> plan, ItemResource item) {
        int total = plan.withdrawnTotal(item);
        for (RequestPlan.CraftStep<BlockPos, ItemResource> step : plan.crafts()) {
            if (item.equals(step.output())) {
                total += step.totalOutput();
            }
        }
        return total;
    }

    /**
     * Accepts the plan: direct withdrawals ship to the requester; craft-input withdrawals
     * are left for {@link CraftJobManager} to pull to the right buffer; craft steps are
     * enqueued as autocraft jobs.
     *
     * @return {@code shipped} is accepted amount of the demand item (stock queued + craft
     *         accepted), not confirmed delivery
     */
    public static Commitment commit(ServerLevel level,
                                    PipeNetwork network,
                                    RequestPlan<BlockPos, ItemResource> plan,
                                    BlockPos requester,
                                    ItemResource requestedItem,
                                    int requestedAmount) {
        return commit(level, network, plan, requester, requestedItem, requestedAmount,
                java.util.Set.of());
    }

    /**
     * As {@link #commit}, keeping {@code excludedStores} off limits when providers
     * actually pull.
     *
     * <p>The plan was already built without those stores; passing them on means the queue
     * cannot quietly take from them ticks later either, which is what stopped a Supplier
     * being restocked out of its own chest by a Provider that happens to touch it.
     */
    public static Commitment commit(ServerLevel level,
                                    PipeNetwork network,
                                    RequestPlan<BlockPos, ItemResource> plan,
                                    BlockPos requester,
                                    ItemResource requestedItem,
                                    int requestedAmount,
                                    java.util.Set<Object> excludedStores) {
        // All or nothing, the way Logistics Pipes treats a request tree: it only fulfils
        // once the whole tree resolves. Committing an incomplete plan starts crafts that
        // can never finish, ties up provider stock in reservations, and leaves half made
        // intermediates sitting in pattern tables. The caller still gets the shortfall
        // breakdown so the player is told exactly what to go and get.
        if (!plan.isComplete()) {
            return new Commitment(0, requestedAmount);
        }

        RoutingSnapshot<BlockPos> routes = network.routes();
        ProviderSendQueue sendQueue = network.sendQueue();

        int accepted = 0;
        int stillNeedFromCraft = Math.max(0, requestedAmount);

        // Withdrawals are now only what heads straight for the requester; stock feeding a
        // craft is bound inside its craft step, so the old item-name filter is gone. That
        // filter also skipped legitimate direct pulls whenever the same item appeared
        // anywhere in a recipe.
        for (RequestPlan.Withdrawal<BlockPos, ItemResource> withdrawal : plan.withdrawals()) {
            BlockPos source = withdrawal.source();
            if (!source.equals(requester) && routes.nextHop(source, requester).isEmpty()) {
                continue;
            }
            sendQueue.enqueue(source, requester, withdrawal.item(), withdrawal.amount(),
                    excludedStores);
            if (withdrawal.item().equals(requestedItem)) {
                accepted += withdrawal.amount();
                stillNeedFromCraft = Math.max(0, stillNeedFromCraft - withdrawal.amount());
            }
        }

        accepted += network.craftJobs().enqueue(
                plan, requester, requestedItem, stillNeedFromCraft, level.getGameTime());

        // Kick providers and craft gathering on the committing tick.
        network.craftJobs().tick(level, network);
        sendQueue.tick(level, network.ledger(), network.parcels(), routes);

        return new Commitment(accepted, requestedAmount);
    }

    /**
     * Puts items a pipe is already holding onto the network, headed for {@code dest}.
     *
     * <p>The entry point for items pushed in from outside, by a hopper or another mod's
     * pipe, rather than pulled by a request. Refusing when no route exists is what lets
     * the caller keep the items instead of them disappearing into a network that cannot
     * deliver them.
     *
     * @return how many were accepted onto the network, zero if there is no route
     */
    public static int pushFromPipe(ServerLevel level,
                                   PipeNetwork network,
                                   BlockPos from,
                                   BlockPos dest,
                                   ItemResource item,
                                   int count) {
        if (item.isEmpty() || count <= 0 || from.equals(dest)) {
            return 0;
        }
        long promiseId = network.ledger().promise(from, dest, item, count,
                level.getGameTime() + PROMISE_TIMEOUT_TICKS);
        ItemShipment shipment = new ItemShipment(item, count, promiseId);
        if (network.parcels().inject(shipment, from, dest, network.routes()).isPresent()) {
            return count;
        }
        network.ledger().cancel(promiseId);
        return 0;
    }

    /**
     * Applies one tick's worth of arrivals and failures.
     *
     * <p>Deliveries settle their promise and go into an inventory at the destination.
     * Stranded parcels are put down where they gave up. Either way the items exist
     * somewhere a player can reach them.
     */
    public static void handle(ServerLevel level,
                              PipeNetwork network,
                              ParcelTracker.TickReport<BlockPos, ItemShipment> report) {
        if (report.isQuiet()) {
            return;
        }

        for (ParcelTracker.Delivery<BlockPos, ItemShipment> delivery : report.delivered()) {
            ItemShipment shipment = delivery.payload();
            network.ledger().recordDelivery(shipment.promiseId(), shipment.count());
            InventoryAccess.insertOrDrop(
                    level, delivery.destination(), shipment.resource(), shipment.count());
        }

        for (ParcelTracker.Stranded<BlockPos, ItemShipment> stranded : report.stranded()) {
            ItemShipment shipment = stranded.payload();
            network.ledger().cancel(shipment.promiseId());
            InventoryAccess.insertOrDrop(
                    level, stranded.at(), shipment.resource(), shipment.count());
        }
    }
}

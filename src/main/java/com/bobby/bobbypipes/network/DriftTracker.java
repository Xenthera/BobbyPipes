package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.block.PipeConnection;
import com.bobby.bobbypipes.block.RoutedPipeBlock;
import com.bobby.bobbypipes.transit.drift.DriftExit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Items wandering through plain pipe.
 *
 * <p>The dumb transport. Anything can push an item into a plain pipe and it drifts, taking
 * a random turn at every junction with no plan and no destination. That is what lets a
 * plain pipe serve as a general tube any mod can feed, rather than only as fabric for the
 * routed network.
 *
 * <p>Three things end a drift, and none of them destroy the item:
 * <ul>
 *   <li>reaching a routed pipe that is on a network and has somewhere to send it, where it
 *       joins the logistics network. A routed pipe that is alone, or has nowhere to send
 *       it, is not a stop: the item drifts on through it like any other pipe</li>
 *   <li>turning into a container, where it is inserted</li>
 *   <li>running out of pipe, where it is put into an adjacent container or dropped</li>
 * </ul>
 *
 * <p>Held per level alongside the routed parcels, not per pipe, so a long run of plain pipe
 * costs no block entities and no per-pipe ticking.
 */
public final class DriftTracker {

    /** Ticks an item spends crossing one plain pipe. */
    private static final int TICKS_PER_HOP = 8;

    /**
     * Hard cap on how far one item may wander.
     *
     * <p>A random walk in a looping pipe network has no natural end. Without a limit an
     * item that never happens to find an exit would drift forever, so it is put down
     * instead.
     */
    private static final int MAX_HOPS = 512;

    /**
     * Ticks an item spends running down the stub of arm into a container.
     *
     * <p>Shorter than a full hop because the arm is shorter than the gap between two pipe
     * centres. Matching the tick count to the distance is what keeps the item moving at one
     * steady speed instead of crawling into the container.
     */
    private static final int ARM_TICKS = 4;

    /**
     * One item in transit through plain pipe.
     *
     * @param exitTo when set, the item is running down this arm into a container and its
     *               journey ends when it gets there. {@code next} is null for that hop:
     *               the item is not going to another pipe, it is leaving the network.
     */
    public record Drifting(long id, ItemResource item, int count,
                           BlockPos at, BlockPos next, Direction cameFrom,
                           int ticksIntoHop, int hops, Direction exitTo) {

        /** True while running down an arm into a container. */
        public boolean leaving() {
            return exitTo != null;
        }
    }

    private final ServerLevel level;
    private final RandomSource random = RandomSource.create();
    private final Map<Long, Drifting> items = new LinkedHashMap<>();
    private long nextId = 1L;

    public DriftTracker(ServerLevel level) {
        this.level = level;
    }

    public int inFlight() {
        return items.size();
    }

    public Collection<Drifting> items() {
        return Collections.unmodifiableCollection(items.values());
    }

    public int ticksPerHop() {
        return TICKS_PER_HOP;
    }

    public int armTicks() {
        return ARM_TICKS;
    }

    public void clear() {
        items.clear();
    }

    /**
     * Puts an item into the plain pipe at {@code pos}.
     *
     * <p>Accepted unconditionally: a plain pipe has no notion of whether anywhere will
     * take the item, and refusing here would mean a hopper could never feed an open ended
     * tube. If it finds nowhere to go it is put down at the end of its walk.
     */
    public void insert(BlockPos pos, ItemResource item, int count, Direction from) {
        if (item.isEmpty() || count <= 0) {
            return;
        }
        long id = nextId++;
        items.put(id, new Drifting(id, item, count, pos.immutable(), null, from, 0, 0, null));
    }

    /** Advances every drifting item by one tick. */
    public void tick(PipeNetwork network) {
        if (items.isEmpty()) {
            return;
        }
        List<Drifting> updated = new ArrayList<>(items.size());
        List<Long> finished = new ArrayList<>();

        for (Drifting drifting : List.copyOf(items.values())) {
            Drifting current = drifting;

            // Running down an arm into a container. No routing left to do, just the run
            // out, and the item is handed over at the end of it.
            if (current.leaving()) {
                current = new Drifting(current.id(), current.item(), current.count(),
                        current.at(), null, current.cameFrom(),
                        current.ticksIntoHop() + 1, current.hops(), current.exitTo());
                if (current.ticksIntoHop() >= ARM_TICKS) {
                    deliverThroughArm(current);
                    finished.add(current.id());
                } else {
                    updated.add(current);
                }
                continue;
            }

            if (current.next() == null) {
                current = arriveAt(current, network);
                if (current == null) {
                    finished.add(drifting.id());
                    continue;
                }
            }

            current = new Drifting(current.id(), current.item(), current.count(),
                    current.at(), current.next(), current.cameFrom(),
                    current.ticksIntoHop() + 1, current.hops(), current.exitTo());

            if (current.ticksIntoHop() >= TICKS_PER_HOP) {
                BlockPos arrived = current.next();
                Direction entered = directionBetween(arrived, current.at());
                Drifting landed = new Drifting(current.id(), current.item(), current.count(),
                        arrived, null, entered, 0, current.hops() + 1, null);
                Drifting after = arriveAt(landed, network);
                if (after == null) {
                    finished.add(current.id());
                    continue;
                }
                current = after;
            }
            updated.add(current);
        }

        updated.forEach(d -> items.put(d.id(), d));
        finished.forEach(items::remove);
    }

    /**
     * Handles an item that has just reached {@code drifting.at()}.
     *
     * <p>Every pipe gets the same treatment: a routed pipe is offered the item first, and
     * anything the network will not take carries on drifting from there. Nothing waits.
     *
     * @return the item to keep tracking, or null once it has been dealt with
     */
    private Drifting arriveAt(Drifting drifting, PipeNetwork network) {
        BlockPos at = drifting.at();

        if (level.getBlockState(at).getBlock() instanceof RoutedPipeBlock) {
            Drifting left = offerToNetwork(drifting, network);
            if (left == null) {
                return null;
            }
            drifting = left;
        }
        if (drifting.hops() >= MAX_HOPS) {
            // Travelled long enough. Put it down rather than let it circulate forever.
            eject(drifting);
            return null;
        }
        return chooseNext(drifting, network);
    }

    /**
     * Offers a drifting item to the logistics network at a routed pipe.
     *
     * <p>A routed pipe is the boundary between the dumb and the planned transport, but only
     * for an item it can actually place. A routed pipe with nowhere to send this item is
     * not a wall: the item keeps drifting through it exactly as it would through plain
     * pipe. That is the Logistics Pipes split, where an item carrying a destination it
     * cannot currently reach is buffered and retried, while an item with no destination at
     * all simply takes a random side onward. A drifting item is the second kind, so holding
     * it here was applying the wrong half of that rule: it stopped dead at the pipe and was
     * then put down wherever it happened to be standing.
     *
     * <p>Drifting on is its own retry. The item is offered again at every routed pipe it
     * meets, including this one if it wanders back, so a network that is momentarily full
     * or still rebuilding still gets its chances without the item sitting still for them.
     *
     * @return null once the network has taken it all, otherwise what is left to drift
     */
    private Drifting offerToNetwork(Drifting drifting, PipeNetwork network) {
        BlockPos at = drifting.at();
        if (!onRoutedNetwork(at)) {
            return drifting;
        }
        Optional<SinkFinder.Sink> found =
                SinkFinder.nearest(level, network, at, drifting.item(), drifting.count());
        if (found.isEmpty()) {
            return drifting;
        }
        SinkFinder.Sink sink = found.get();

        // The sink is this very pipe: hand it straight to the inventory behind it rather
        // than routing a parcel from a pipe to itself.
        int sent = sink.pos().equals(at)
                ? InventoryAccess.insert(level, at, drifting.item(), sink.accept())
                : RequestService.pushFromPipe(level, network, at, sink.pos(),
                        drifting.item(), sink.accept());
        if (sent >= drifting.count()) {
            return null;
        }
        if (sent <= 0) {
            return drifting;
        }
        // Partially accepted: the rest carries on drifting.
        return new Drifting(drifting.id(), drifting.item(), drifting.count() - sent,
                at, null, drifting.cameFrom(), 0, drifting.hops(), null);
    }

    /**
     * Picks the next side, acting on it if it is not more pipe.
     *
     * @return the item with a new hop set, or null once it has been put somewhere
     */
    private Drifting chooseNext(Drifting drifting, PipeNetwork network) {
        BlockPos at = drifting.at();
        BlockState state = level.getBlockState(at);
        List<DriftExit.Option<Direction>> options = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            options.add(new DriftExit.Option<>(side, classify(at, side), isRoutedArm(state, side)));
        }

        DriftExit.Option<Direction> chosen =
                DriftExit.choose(options, drifting.cameFrom(), random.nextFloat());
        if (chosen == null) {
            // No onward side: put it in a neighbouring container, else drop it.
            eject(drifting);
            return null;
        }

        BlockPos target = at.relative(chosen.side());
        switch (chosen.kind()) {
            case INVENTORY -> {
                // Set off down the arm rather than appearing inside the container. The
                // insert happens when the item gets there, the same way a routed parcel
                // is animated into its destination.
                return new Drifting(drifting.id(), drifting.item(), drifting.count(),
                        at, null, drifting.cameFrom(), 0, drifting.hops(), chosen.side());
            }
            case DUMB_PIPE, SMART_PIPE -> {
                return new Drifting(drifting.id(), drifting.item(), drifting.count(),
                        at, target, drifting.cameFrom(), 0, drifting.hops(), null);
            }
            default -> {
                eject(drifting);
                return null;
            }
        }
    }

    /**
     * Puts an item down where it stands, never back the way it came.
     *
     * <p>Excluding the arrival side is what stops an item ejected at a dead end landing in
     * the hopper that fed the pipe, only to be pushed straight back in.
     */
    /**
     * Hands an item that has finished its arm run to the container at the end of it.
     *
     * <p>The container may have filled up during the few ticks the item spent travelling,
     * so anything it will not take is put down by the normal eject path rather than lost.
     */
    private void deliverThroughArm(Drifting drifting) {
        int placed = InventoryAccess.insertTo(level, drifting.at(), drifting.exitTo(),
                drifting.item(), drifting.count());
        int left = drifting.count() - placed;
        if (left > 0) {
            InventoryAccess.insertOrDropExcluding(level, drifting.at(), drifting.item(),
                    left, drifting.cameFrom());
        }
    }

    private void eject(Drifting drifting) {
        InventoryAccess.insertOrDropExcluding(level, drifting.at(), drifting.item(),
                drifting.count(), drifting.cameFrom());
    }

    /**
     * Whether the routed pipe at {@code pos} is actually part of a routed network.
     *
     * <p>A routed pipe reaches its peers over a direct connection, which is the green arm
     * mark the player can see. One with no green arm at all has no peers: it is a router
     * on its own, and a lone router is not a network. Such a pipe must not pull a drifting
     * item off its random walk, which is what a default route somewhere across a junction
     * of plain pipe was doing  -  the item was captured at the routed pipe and delivered
     * straight there, so it never reached the junction to branch at it.
     *
     * <p>Reading the block state rather than recomputing keeps this identical to the arm
     * indicator by construction. Red arms and dumb behaviour are then the same statement.
     */
    private boolean onRoutedNetwork(BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof RoutedPipeBlock)) {
            return false;
        }
        for (Direction side : Direction.values()) {
            if (isRoutedArm(state, side)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether this arm is a routed connection, which is the green mark on the pipe.
     *
     * <p>Only routed pipes are ever marked, so a plain pipe reports false on every side and
     * keeps choosing purely at random.
     */
    private static boolean isRoutedArm(BlockState state, Direction side) {
        return state.getBlock() instanceof RoutedPipeBlock
                && state.getValue(PipeBlock.propertyFor(side)) == PipeConnection.DIRECT;
    }

    private DriftExit.Kind classify(BlockPos at, Direction side) {
        BlockPos neighbour = at.relative(side);
        if (!level.hasChunkAt(neighbour)) {
            return DriftExit.Kind.NONE;
        }
        var block = level.getBlockState(neighbour).getBlock();
        if (block instanceof RoutedPipeBlock) {
            return DriftExit.Kind.SMART_PIPE;
        }
        if (block instanceof PipeBlock) {
            return DriftExit.Kind.DUMB_PIPE;
        }
        return InventoryAccess.canInsertFrom(level, at, side, ItemResource.EMPTY)
                ? DriftExit.Kind.INVENTORY
                : DriftExit.Kind.NONE;
    }

    private static Direction directionBetween(BlockPos from, BlockPos to) {
        for (Direction side : Direction.values()) {
            if (from.relative(side).equals(to)) {
                return side;
            }
        }
        return null;
    }
}

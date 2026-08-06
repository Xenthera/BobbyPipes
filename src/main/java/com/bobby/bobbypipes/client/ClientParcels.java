package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.block.PipeBlock;
import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Client-side parcel motion cache.
 *
 * <p>Capture (drift → routed) keeps the approach hop into the smart pipe with no cage, then
 * at the centre shows the cage and leaves at progress 0 toward the first outbound pipe
 * snapshotted at capture, never toward wherever the faster server parcel has already
 * reached. If the server is ahead afterward, the client catches up one adjacent pipe at a
 * time.
 */
public final class ClientParcels {

    private static int ticksPerHop = 8;
    private static final Map<Long, Visual> visuals = new HashMap<>();

    private ClientParcels() {
    }

    public static void handle(ParcelSyncPayload payload, IPayloadContext context) {
        ticksPerHop = Math.max(1, payload.ticksPerHop());
        long now = clientGameTimeOr(payload.gameTime());

        Set<Long> seen = new HashSet<>(payload.parcels().size() * 2);
        for (ParcelSyncPayload.Entry entry : payload.parcels()) {
            seen.add(entry.id());
            Visual visual = visuals.get(entry.id());
            if (visual != null) {
                visual.finishing = false;
                visual.server = entry;
                visual.stack = entry.stack();
                visual.tier = entry.tier();
                rememberLeaveNext(visual, entry);
                if (!visual.awaitingArrival && !visual.catchingUp) {
                    visual.routed = entry.routed();
                    // Only while this entry still describes the hop the visual is actually
                    // rendering. The server ticks parcels before syncing, so a packet can
                    // arrive already reporting the *next* hop's (longer) timing before the
                    // client's own render of the current hop has finished; blindly taking it
                    // mid-flight doubled the denominator under a hop that was still playing,
                    // which read as the item grinding to a near-halt and then lurching to
                    // catch up once it finally reached the boundary.
                    if (entry.at().equals(visual.at) && entry.next().equals(visual.next)) {
                        visual.currentHopTicks = Math.max(1, entry.ticksForHop());
                    }
                }
                continue;
            }
            if (entry.routed()) {
                Visual approaching = takeApproaching(entry);
                if (approaching != null) {
                    bindCapture(approaching, entry, now);
                    visuals.put(entry.id(), approaching);
                    continue;
                }
            }
            visuals.put(entry.id(), Visual.begin(entry, now));
        }
        for (Map.Entry<Long, Visual> entry : visuals.entrySet()) {
            Visual visual = entry.getValue();
            if (!seen.contains(entry.getKey())
                    && !visual.finishing
                    && !visual.awaitingArrival) {
                visual.finishing = true;
            }
        }
    }

    /**
     * Rekeys an approaching drift visual onto the new routed id. Cage shows for the rest
     * of the enter into the capture pipe; outbound hop starts only after arrival.
     */
    private static void bindCapture(Visual visual, ParcelSyncPayload.Entry entry, long now) {
        visual.id = entry.id();
        visual.server = entry;
        visual.stack = entry.stack();
        visual.tier = entry.tier();
        visual.finishing = false;
        visual.captureAt = entry.at().immutable();
        rememberLeaveNext(visual, entry);

        boolean alreadyThere = visual.at.equals(entry.at())
                && (visual.next.isEmpty() || visual.next.get().equals(entry.at()));
        if (alreadyThere) {
            leaveCapturePipe(visual, now);
        } else {
            visual.awaitingArrival = true;
            visual.routed = false;
        }
    }

    private static void rememberLeaveNext(Visual visual, ParcelSyncPayload.Entry entry) {
        if (visual.captureAt == null) {
            return;
        }
        // Only trust outbound while the server still reports the parcel at the capture pipe.
        // Later packets have already moved on and must not replace leaveNext with a skip.
        if (entry.at().equals(visual.captureAt) && entry.next().isPresent()) {
            visual.leaveNext = entry.next().map(BlockPos::immutable);
            // The server's own timing for that first outbound hop, frozen the same way -
            // authoritative, so there is nothing left to guess from path length here.
            visual.leaveNextTicks = Math.max(1, entry.ticksForHop());
        }
    }

    /**
     * Cage on, leave the capture pipe from its centre toward the first outbound neighbour.
     */
    private static void leaveCapturePipe(Visual visual, long now) {
        BlockPos centre = visual.captureAt != null ? visual.captureAt : visual.at;
        Optional<BlockPos> outbound = visual.leaveNext;
        if (outbound.isEmpty() && visual.server != null) {
            outbound = stepToward(centre, visual.server.at());
        }
        visual.awaitingArrival = false;
        visual.at = centre;
        visual.next = outbound;
        visual.enterFrom = Optional.empty();
        visual.exitTo = Optional.empty();
        visual.routed = true;
        visual.hopStart = now;
        visual.currentHopTicks = visual.leaveNext.isPresent() ? visual.leaveNextTicks : ticksPerHop;
        visual.catchingUp = visual.server == null
                || !centre.equals(visual.server.at())
                || !outbound.equals(visual.server.next());
    }

    /**
     * Picks which approaching drift visual just got captured into {@code entry}.
     *
     * <p>A hopper streaming items produces several same-stack drift visuals converging on
     * the same pipe at once, so matching on stack alone is ambiguous. The one actually
     * captured is always whichever is furthest along its hop into that pipe: already
     * there beats still approaching, and among those still approaching, the one with the
     * oldest {@code hopStart} has been travelling longest and is nearest arrival. Binding
     * any other candidate orphans the true leader: it never matches a later server entry
     * and silently vanishes right as it reaches the pipe.
     */
    private static @Nullable Visual takeApproaching(ParcelSyncPayload.Entry entry) {
        Long bestKey = null;
        Visual best = null;
        boolean bestAtPipe = false;
        for (Map.Entry<Long, Visual> mapEntry : visuals.entrySet()) {
            Visual visual = mapEntry.getValue();
            if (visual.routed || visual.awaitingArrival) {
                continue;
            }
            boolean atPipe = visual.at.equals(entry.at());
            boolean intoPipe = visual.next.isPresent() && visual.next.get().equals(entry.at());
            if ((!atPipe && !intoPipe)
                    || !ItemStack.isSameItemSameComponents(visual.stack, entry.stack())) {
                continue;
            }
            // Already-arrived candidates always outrank still-approaching ones; among two
            // still-approaching candidates, the older hopStart has travelled longer.
            boolean better = best == null
                    || (atPipe && !bestAtPipe)
                    || (atPipe == bestAtPipe && visual.hopStart < best.hopStart);
            if (better) {
                best = visual;
                bestKey = mapEntry.getKey();
                bestAtPipe = atPipe;
            }
        }
        if (bestKey != null) {
            visuals.remove(bestKey);
        }
        return best;
    }

    public static int ticksPerHop() {
        return ticksPerHop;
    }

    public static List<Drawn> sample(long clientGameTime, float partialTick) {
        if (visuals.isEmpty()) {
            return List.of();
        }
        List<Drawn> out = new ArrayList<>(visuals.size());
        Iterator<Map.Entry<Long, Visual>> iterator = visuals.entrySet().iterator();
        while (iterator.hasNext()) {
            Visual visual = iterator.next().getValue();
            float progress = visual.next.isEmpty()
                    ? 0.0f
                    : rawProgress(visual, clientGameTime, partialTick);

            if (visual.awaitingArrival) {
                if (progress >= 1.0f || visual.next.isEmpty()) {
                    leaveCapturePipe(visual, clientGameTime);
                    progress = visual.next.isEmpty()
                            ? 0.0f
                            : rawProgress(visual, clientGameTime, partialTick);
                }
            } else if (visual.finishing) {
                if (progress >= 1.0f) {
                    iterator.remove();
                    continue;
                }
            } else {
                reconcile(visual, clientGameTime, partialTick);
                progress = visual.next.isEmpty()
                        ? 0.0f
                        : rawProgress(visual, clientGameTime, partialTick);
            }

            out.add(new Drawn(
                    visual.id(),
                    visual.at,
                    visual.next,
                    visual.enterFrom,
                    visual.exitTo,
                    visual.stack,
                    Mth.clamp(progress, 0.0f, 1.0f),
                    showCage(visual),
                    visual.tier,
                    visual.linkHop));
        }
        return out;
    }

    public static boolean isEmpty() {
        return visuals.isEmpty();
    }

    public static void clear() {
        visuals.clear();
    }

    /** Cage from the centre of the first routed pipe onward, not during the approach. */
    private static boolean showCage(Visual visual) {
        return visual.routed;
    }

    private static void reconcile(Visual visual, long clientGameTime, float partialTick) {
        ParcelSyncPayload.Entry server = visual.server;
        if (server == null) {
            return;
        }

        if (sameHop(visual, server)) {
            visual.catchingUp = false;
            float duration = visual.currentHopTicks;
            float serverProgress = (server.ticksIntoHop() + partialTick) / duration;
            float leadTicks = rawProgress(visual, clientGameTime, partialTick) * duration
 - serverProgress * duration;
            if (leadTicks > 2.0f) {
                visual.hopStart = clientGameTime
 - Math.round(serverProgress * duration);
            }
            return;
        }

        float progress = rawProgress(visual, clientGameTime, partialTick);
        // Never snap mid-hop to a server that ran ahead during the slow unrouted enter.
        if (visual.next.isPresent() && progress < 1.0f) {
            return;
        }

        if (visual.next.isPresent()) {
            BlockPos arrived = visual.next.get();
            // The client's own hop lands exactly on the server's schedule now, so it can
            // finish a tick before that tick's sync packet has arrived - server still
            // reports the hop just completed, not yet the new one. Treating that as "the
            // server is ahead" sent the item stepping toward the stale (one-hop-behind)
            // position, i.e. briefly backward, before correcting once the fresh packet
            // landed. Waiting one more tick for it avoids the stutter entirely.
            boolean staleServerStillOnFinishedHop =
                    !arrived.equals(server.at()) && server.next().map(arrived::equals).orElse(false);
            if (staleServerStillOnFinishedHop) {
                return;
            }
            advanceOnePipe(visual, server, clientGameTime);
            return;
        }

        visual.adopt(server, clientGameTime);
        visual.catchingUp = false;
    }

    /**
     * Completes the current hop and starts the next adjacent step: either the server's
     * hop if we have caught up, or one pipe toward the server along the lattice.
     */
    private static void advanceOnePipe(Visual visual,
                                       ParcelSyncPayload.Entry server,
                                       long now) {
        BlockPos arrived = visual.next.orElseThrow().immutable();
        visual.at = arrived;
        visual.enterFrom = Optional.empty();
        if (arrived.equals(server.at())) {
            visual.next = server.next().map(BlockPos::immutable);
            visual.exitTo = server.exitTo();
            visual.linkHop = server.linkHop();
            visual.catchingUp = false;
            // A real, server-confirmed hop: use its authoritative timing directly.
            visual.currentHopTicks = Math.max(1, server.ticksForHop());
        } else {
            visual.next = stepToward(arrived, server.at());
            visual.exitTo = Optional.empty();
            visual.linkHop = visual.next
                    .map(next -> ParcelSyncPayload.isLinkHop(arrived, next))
                    .orElse(false);
            visual.catchingUp = true;
            // A fabricated catch-up step toward wherever the server has gotten to, not a
            // hop the server itself reported - no arm timing to inherit, just the base rate.
            visual.currentHopTicks = ticksPerHop;
        }
        visual.hopStart = now;
        visual.routed = true;
    }

    /**
     * Next pipe from {@code from} that reduces distance to {@code goal}, so catch-up never
     * skips an intermediate dumb pipe.
     */
    private static Optional<BlockPos> stepToward(BlockPos from, BlockPos goal) {
        if (from.equals(goal)) {
            return Optional.empty();
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return Optional.of(goal.immutable());
        }
        BlockPos best = null;
        int bestDist = from.distManhattan(goal);
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = from.relative(direction);
            if (!(level.getBlockState(neighbour).getBlock() instanceof PipeBlock)) {
                continue;
            }
            int dist = neighbour.distManhattan(goal);
            if (dist < bestDist) {
                bestDist = dist;
                best = neighbour;
            }
        }
        return Optional.ofNullable(best).map(BlockPos::immutable).or(() -> Optional.of(goal.immutable()));
    }

    private static boolean sameHop(Visual visual, ParcelSyncPayload.Entry server) {
        return visual.at.equals(server.at()) && visual.next.equals(server.next());
    }

    private static float rawProgress(Visual visual, long clientGameTime, float partialTick) {
        if (visual.next.isEmpty()) {
            return 0.0f;
        }
        return (clientGameTime - visual.hopStart + partialTick) / (float) visual.currentHopTicks;
    }

    private static long clientGameTimeOr(long fallback) {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : fallback;
    }

    public record Drawn(
            long id,
            BlockPos at,
            Optional<BlockPos> next,
            Optional<Direction> enterFrom,
            Optional<Direction> exitTo,
            ItemStack stack,
            float progress,
            boolean routed,
            int tier,
            boolean linkHop) {
    }

    private static final class Visual {
        private long id;
        private BlockPos at;
        private Optional<BlockPos> next;
        private Optional<Direction> enterFrom;
        private Optional<Direction> exitTo;
        private ItemStack stack;
        private boolean routed;
        /**
         * Parcel density, 0 for items and drift. Fixed for a parcel's whole life (the
         * amount it carries never changes in flight), so unlike hop state there is nothing
         * here that has to be reconciled against the server mid-hop.
         */
        private int tier;
        private boolean linkHop;
        private long hopStart;
        /**
         * How many ticks the current hop takes, straight from the server
         * ({@link ParcelSyncPayload.Entry#ticksForHop}) rather than guessed from path
         * length. Arm hops really do take longer on the server now, so there is nothing
         * left for this to independently compute and fall out of step with.
         */
        private int currentHopTicks = 1;
        private ParcelSyncPayload.Entry server;
        /** True while finishing the unrouted hop into {@link #captureAt}. */
        private boolean awaitingArrival;
        private @Nullable BlockPos captureAt;
        /** First outbound neighbour from the capture pipe, snapshotted while server was still there. */
        private Optional<BlockPos> leaveNext = Optional.empty();
        /** That outbound hop's server-reported timing, frozen alongside {@link #leaveNext}. */
        private int leaveNextTicks = 1;
        /** Server is ahead; advance one adjacent pipe at a time after each hop completes. */
        private boolean catchingUp;
        private boolean finishing;

        private static Visual begin(ParcelSyncPayload.Entry entry, long now) {
            Visual visual = new Visual();
            visual.adopt(entry, now);
            float serverProgress = entry.ticksIntoHop() / (float) visual.currentHopTicks;
            visual.hopStart = now - Math.round(serverProgress * visual.currentHopTicks);
            return visual;
        }

        private long id() {
            return id;
        }

        private void adopt(ParcelSyncPayload.Entry entry, long hopStart) {
            this.server = entry;
            this.id = entry.id();
            this.at = entry.at();
            this.next = entry.next();
            this.enterFrom = entry.enterFrom();
            this.exitTo = entry.exitTo();
            this.stack = entry.stack();
            this.routed = entry.routed();
            this.tier = entry.tier();
            this.linkHop = entry.linkHop();
            this.currentHopTicks = Math.max(1, entry.ticksForHop());
            this.hopStart = hopStart;
            this.finishing = false;
            this.awaitingArrival = false;
            this.catchingUp = false;
        }
    }
}

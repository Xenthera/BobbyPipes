package com.bobby.bobbypipes.client;

import com.bobby.bobbypipes.network.payload.ParcelSyncPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.handling.IPayloadContext;

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
 * <p>Sync packets are treated as authoritative targets, but the drawn hop is allowed to
 * finish travelling to the next pipe centre before switching. That stops the common hitch
 * where the server is already on the next hop while the client is still mid-tube, which
 * used to yank the item to the centre for a frame before it moved on.
 *
 * <p>Deliveries that leave the sync mid-arm are kept until the local hop clock reaches the
 * tip, so items do not pop out halfway into a chest.
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
            if (visual == null) {
                visuals.put(entry.id(), Visual.begin(entry, now));
            } else {
                visual.finishing = false;
                visual.server = entry;
                visual.stack = entry.stack();
            }
        }
        for (Map.Entry<Long, Visual> entry : visuals.entrySet()) {
            if (!seen.contains(entry.getKey()) && !entry.getValue().finishing) {
                // Server already inserted; let the arm run out locally.
                entry.getValue().finishing = true;
            }
        }
    }

    public static int ticksPerHop() {
        return ticksPerHop;
    }

    /**
     * Advances deferred hop hand-offs and returns what the renderer should draw this frame.
     */
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
            if (visual.finishing) {
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
                    Mth.clamp(progress, 0.0f, 1.0f)));
        }
        return out;
    }

    public static boolean isEmpty() {
        return visuals.isEmpty();
    }

    public static void clear() {
        visuals.clear();
    }

    private static void reconcile(Visual visual, long clientGameTime, float partialTick) {
        ParcelSyncPayload.Entry server = visual.server;
        if (server == null) {
            return;
        }
        if (sameHop(visual, server)) {
            // Free-running clocks drift ahead when the server hitchs. More than a couple of
            // ticks ahead means we would sit at the far pipe centre until sync catches up
            // (the old "pause every second" look). Re-anchor to the server in that case.
            float leadTicks = rawProgress(visual, clientGameTime, partialTick) * ticksPerHop
                    - (server.ticksIntoHop() + partialTick);
            if (leadTicks > 2.0f) {
                visual.hopStart = clientGameTime - server.ticksIntoHop();
            }
            return;
        }

        float progress = rawProgress(visual, clientGameTime, partialTick);
        boolean sequential = visual.next.isPresent() && visual.next.get().equals(server.at());

        // Still sliding toward the pipe the server has already entered  -  finish that
        // motion first so we meet it at the centre instead of teleporting there.
        if (sequential && progress < 1.0f) {
            return;
        }

        long start = sequential
                ? clientGameTime
                : clientGameTime - server.ticksIntoHop();
        visual.adopt(server, start);
    }

    private static boolean sameHop(Visual visual, ParcelSyncPayload.Entry server) {
        return visual.at.equals(server.at()) && visual.next.equals(server.next());
    }

    private static float rawProgress(Visual visual, long clientGameTime, float partialTick) {
        if (visual.next.isEmpty()) {
            return 0.0f;
        }
        return (clientGameTime - visual.hopStart + partialTick) / ticksPerHop;
    }

    private static long clientGameTimeOr(long fallback) {
        var level = Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : fallback;
    }

    /** One parcel's drawn hop for this frame. */
    public record Drawn(
            long id,
            BlockPos at,
            Optional<BlockPos> next,
            Optional<Direction> enterFrom,
            Optional<Direction> exitTo,
            ItemStack stack,
            float progress) {
    }

    private static final class Visual {
        private long id;
        private BlockPos at;
        private Optional<BlockPos> next;
        private Optional<Direction> enterFrom;
        private Optional<Direction> exitTo;
        private ItemStack stack;
        private long hopStart;
        private ParcelSyncPayload.Entry server;
        /** True after the server dropped this parcel; keep drawing until progress hits 1. */
        private boolean finishing;

        private static Visual begin(ParcelSyncPayload.Entry entry, long now) {
            Visual visual = new Visual();
            visual.adopt(entry, now - entry.ticksIntoHop());
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
            // Latch arm ends for the whole hop so a late exitTo/enterFrom cannot morph
            // the path mid-travel and hitch the item.
            this.enterFrom = entry.enterFrom();
            this.exitTo = entry.exitTo();
            this.stack = entry.stack();
            this.hopStart = hopStart;
            this.finishing = false;
        }
    }
}

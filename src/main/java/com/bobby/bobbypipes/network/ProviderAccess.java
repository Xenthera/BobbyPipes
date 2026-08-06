package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.pipes.ProviderLeaveMode;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Provider-aware stock reads and extracts.
 *
 * <p>A provider offers every distinct adjacent inventory (LP pipe semantics). Filter and
 * leave mode are applied per inventory: leave-first/last skip that inventory's first/last
 * occupied stack in slot order. Reported amounts use extractable quantities (dry-run
 * extract) so storage-side reservations are not over-reported.
 */
public final class ProviderAccess {

    private ProviderAccess() {
    }

    public static Map<ItemResource, Integer> summarize(ServerLevel level, BlockPos pipe) {
        return summarizeUnclaimed(level, pipe, new HashSet<>());
    }

    public static Map<ItemResource, Integer> summarizeUnclaimed(ServerLevel level,
                                                                BlockPos pipe,
                                                                Set<Object> claimed) {
        ProviderSettings settings = settingsAt(level, pipe);
        Map<ItemResource, Integer> totals = new LinkedHashMap<>();
        InventoryAccess.forEachUnclaimed(level, pipe, claimed, (handler, ignored) -> {
            OccupiedBounds bounds = OccupiedBounds.of(handler);
            for (int slot = 0; slot < handler.size(); slot++) {
                if (!settings.leaveMode().allowsOccupiedSlot(slot, bounds.first(), bounds.last())) {
                    continue;
                }
                ItemResource resource = handler.getResource(slot);
                if (resource.isEmpty() || !settings.accepts(resource)) {
                    continue;
                }
                int amount = providableInSlot(handler, slot, resource, settings.leaveMode(), null);
                if (amount > 0) {
                    totals.merge(resource, amount, Integer::sum);
                }
            }
        });
        return totals;
    }

    public static int countUnclaimed(ServerLevel level,
                                     BlockPos pipe,
                                     ItemResource item,
                                     Set<Object> claimed) {
        if (item.isEmpty()) {
            return 0;
        }
        ProviderSettings settings = settingsAt(level, pipe);
        if (!settings.accepts(item)) {
            // Still claim attached stores so siblings do not double-count them as empty.
            InventoryAccess.forEachUnclaimed(level, pipe, claimed, (handler, ignored) -> {
            });
            return 0;
        }
        int[] total = {0};
        InventoryAccess.forEachUnclaimed(level, pipe, claimed, (handler, ignored) -> {
            OccupiedBounds bounds = OccupiedBounds.of(handler);
            for (int slot = 0; slot < handler.size(); slot++) {
                if (!settings.leaveMode().allowsOccupiedSlot(slot, bounds.first(), bounds.last())) {
                    continue;
                }
                if (!item.equals(handler.getResource(slot))) {
                    continue;
                }
                total[0] += providableInSlot(handler, slot, item, settings.leaveMode(), null);
            }
        });
        return total[0];
    }

    /**
     * Extracts up to {@code wanted} of {@code item}, respecting filter and leave mode
     * across every distinct adjacent inventory.
     *
     * @return how many were actually removed
     */
    public static int extract(ServerLevel level, BlockPos pipe, ItemResource item, int wanted) {
        return extract(level, pipe, item, wanted, Set.of());
    }

    /**
     * As {@link #extract(ServerLevel, BlockPos, ItemResource, int)}, but stores whose
     * identity is in {@code excluded} are left alone.
     *
     * <p>A Supplier plans with its own chest excluded, but a Provider pipe touching both
     * that chest and another one would still have drained the shared chest here, shipping
     * the Supplier its own items. Seeding the claimed set with the exclusions makes the
     * pull skip them for the same reason the plan did.
     */
    public static int extract(ServerLevel level, BlockPos pipe, ItemResource item, int wanted,
                              Set<Object> excluded) {
        if (item.isEmpty() || wanted <= 0) {
            return 0;
        }
        ProviderSettings settings = settingsAt(level, pipe);
        if (!settings.accepts(item)) {
            return 0;
        }
        int[] taken = {0};
        try (Transaction transaction = Transaction.openRoot()) {
            InventoryAccess.forEachUnclaimed(level, pipe, new HashSet<>(excluded), (handler, ignored) -> {
                if (taken[0] >= wanted) {
                    return;
                }
                OccupiedBounds bounds = OccupiedBounds.of(handler);
                for (int slot = 0; slot < handler.size() && taken[0] < wanted; slot++) {
                    if (!settings.leaveMode().allowsOccupiedSlot(slot, bounds.first(), bounds.last())
                            || !item.equals(handler.getResource(slot))) {
                        continue;
                    }
                    int allow = providableInSlot(
                            handler, slot, item, settings.leaveMode(), transaction);
                    if (allow <= 0) {
                        continue;
                    }
                    taken[0] += handler.extract(
                            slot, item, Math.min(wanted - taken[0], allow), transaction);
                }
            });
            transaction.commit();
        }
        return taken[0];
    }

    /** Face holding providable stock of {@code item}, if any. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe, ItemResource item) {
        return sideHolding(level, pipe, item, Set.of());
    }

    /** As {@link #sideHolding(ServerLevel, BlockPos, ItemResource)}, skipping {@code excluded} stores. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe, ItemResource item,
                                                  Set<Object> excluded) {
        if (item.isEmpty()) {
            return Optional.empty();
        }
        ProviderSettings settings = settingsAt(level, pipe);
        if (!settings.accepts(item)) {
            return Optional.empty();
        }
        Set<Object> seen = new HashSet<>(excluded);
        for (Direction direction : Direction.values()) {
            ResourceHandler<ItemResource> handler = InventoryAccess.handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            Object identity = StorageIdentities.of(level, pipe.relative(direction));
            if (!seen.add(identity)) {
                continue;
            }
            OccupiedBounds bounds = OccupiedBounds.of(handler);
            for (int slot = 0; slot < handler.size(); slot++) {
                if (!settings.leaveMode().allowsOccupiedSlot(slot, bounds.first(), bounds.last())
                        || !item.equals(handler.getResource(slot))) {
                    continue;
                }
                if (providableInSlot(handler, slot, item, settings.leaveMode(), null) > 0) {
                    return Optional.of(direction);
                }
            }
        }
        return Optional.empty();
    }

    private static ProviderSettings settingsAt(ServerLevel level, BlockPos pipe) {
        if (level.getBlockEntity(pipe) instanceof ProviderPipeBlockEntity be) {
            return be.settings();
        }
        return ProviderSettings.EMPTY;
    }

    /**
     * Extractable count from one slot, then optionally one more reserved by the provider's
     * leave-one-per-stack mode. The dry-run already honors storage-side leave-last rules.
     *
     * <p>When {@code parent} is non-null, the probe opens as a nested transaction so callers
     * already inside a root (e.g. {@link #extract}) do not crash.
     */
    private static int providableInSlot(ResourceHandler<ItemResource> handler,
                                        int slot,
                                        ItemResource item,
                                        ProviderLeaveMode leaveMode,
                                        @Nullable TransactionContext parent) {
        int extractable;
        try (Transaction probe = parent == null ? Transaction.openRoot() : Transaction.open(parent)) {
            extractable = handler.extract(slot, item, Integer.MAX_VALUE, probe);
        }
        if (leaveMode.leavesOnePerStack()) {
            extractable = Math.max(0, extractable - 1);
        }
        return extractable;
    }

    /** First and last occupied slot indices in one inventory (slot scan order). */
    private record OccupiedBounds(int first, int last) {

        static OccupiedBounds of(ResourceHandler<ItemResource> handler) {
            int first = -1;
            int last = -1;
            for (int slot = 0; slot < handler.size(); slot++) {
                if (handler.getResource(slot).isEmpty() || handler.getAmountAsInt(slot) <= 0) {
                    continue;
                }
                if (first < 0) {
                    first = slot;
                }
                last = slot;
            }
            return new OccupiedBounds(first, last);
        }
    }
}

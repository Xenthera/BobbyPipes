package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.energy.EnergyHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and moves FE in the energy storages touching a pipe.
 *
 * <p>Mirrors {@link InventoryAccess} exactly, one capability lookup per side, transactional
 * probes for capacity checks, so the rest of the network layer deals in plain FE amounts
 * the same way it deals in plain item counts. Energy has no resource identity to match on,
 * every method here just moves or counts a raw amount.
 */
public final class EnergyAccess {

    /**
     * How many times one side is asked to move energy before we accept that it is done.
     *
     * <p>Energy storages routinely cap a single {@code insert}/{@code extract} call at
     * their per-operation transfer rate (Energized Power's battery box hands over about
     * 2k FE per call regardless of how much it is holding). Asking once and believing the
     * answer meant a 10k parcel measured a battery box with a full buffer as "2k
     * available", and, worse, a delivery into a rate-limited machine placed 2k of a 10k
     * parcel and voided the other 8k. Repeating the call until it stops making progress
     * measures the real free space and the real free stock; the cap is only there so a
     * pathological handler that always returns a positive amount cannot spin forever.
     */
    private static final int PUMP_ATTEMPTS = 64;

    private EnergyAccess() {
    }

    /** Total FE currently held in energy storages touching {@code pipe}. */
    public static int count(ServerLevel level, BlockPos pipe) {
        int total = 0;
        for (Direction direction : Direction.values()) {
            EnergyHandler handler = handlerAt(level, pipe, direction);
            if (handler != null) {
                total += handler.getAmountAsInt();
            }
        }
        return total;
    }

    /**
     * Takes up to {@code wanted} FE out of storages touching {@code pipe}.
     *
     * <p>Committed immediately. Extracting less than asked for is normal, the caller must
     * use the returned amount rather than assuming it got everything.
     *
     * @return how much was actually removed
     */
    public static int extract(ServerLevel level, BlockPos pipe, int wanted) {
        return extract(level, pipe, wanted, Set.of());
    }

    /**
     * Like {@link #extract(ServerLevel, BlockPos, int)}, but storages at any position in
     * {@code excluded} are left alone.
     *
     * <p>This is what stops a battery box being drained to fill itself: when a Provider
     * pipe and the pipe asking both touch the same storage, pulling from it would ship
     * energy out of that box and straight back into it, forever, with the target never
     * getting any closer.
     */
    public static int extract(ServerLevel level, BlockPos pipe, int wanted, Set<BlockPos> excluded) {
        if (wanted <= 0) {
            return 0;
        }
        int taken = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (taken >= wanted) {
                    break;
                }
                EnergyHandler handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                taken += pumpExtract(handler, wanted - taken, transaction);
            }
            transaction.commit();
        }
        return taken;
    }

    /**
     * Puts up to {@code amount} FE into storages touching {@code pipe}.
     *
     * @return how much was accepted, which may be zero if everything nearby is full
     */
    public static int insert(ServerLevel level, BlockPos pipe, int amount) {
        return insert(level, pipe, amount, Set.of());
    }

    /** As {@link #insert(ServerLevel, BlockPos, int)}, skipping {@code excluded} storages. */
    public static int insert(ServerLevel level, BlockPos pipe, int amount, Set<BlockPos> excluded) {
        if (amount <= 0) {
            return 0;
        }
        int placed = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (placed >= amount) {
                    break;
                }
                EnergyHandler handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                placed += pumpInsert(handler, amount - placed, transaction);
            }
            transaction.commit();
        }
        return placed;
    }

    /**
     * How much FE storages touching {@code pipe} could accept right now.
     *
     * <p>Uses a rollback transaction so nothing is actually moved. Used before extracting
     * from a source so a full destination throttles how much is packeted up in the first
     * place, rather than shipping a full packet and voiding whatever does not fit.
     */
    public static int insertable(ServerLevel level, BlockPos pipe, int wanted) {
        if (wanted <= 0) {
            return 0;
        }
        int accepted = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (accepted >= wanted) {
                    break;
                }
                EnergyHandler handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                accepted += pumpInsert(handler, wanted - accepted, transaction);
            }
            // Roll back - this is a capacity probe only.
        }
        return accepted;
    }

    /**
     * How much FE could actually be pulled out of storages touching {@code pipe} right now.
     *
     * <p>Rollback probe, nothing moves. This is deliberately not the same as {@link #count}:
     * plenty of generators expose their buffer as readable but refuse extraction (they only
     * push). Counting such a buffer as available FE would queue withdrawals that can never
     * be filled, so anything deciding what the network can supply should ask this.
     */
    public static int extractable(ServerLevel level, BlockPos pipe, int wanted) {
        return extractable(level, pipe, wanted, Set.of());
    }

    /**
     * Like {@link #extractable(ServerLevel, BlockPos, int)}, ignoring storages at any
     * position in {@code excluded}. Same self-feeding reasoning as
     * {@link #extract(ServerLevel, BlockPos, int, Set)}: stock that would only be shipped
     * back to where it came from is not available stock.
     */
    public static int extractable(ServerLevel level, BlockPos pipe, int wanted, Set<BlockPos> excluded) {
        if (wanted <= 0) {
            return 0;
        }
        int available = 0;
        try (Transaction probe = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (available >= wanted) {
                    break;
                }
                EnergyHandler handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                available += pumpExtract(handler, wanted - available, probe);
            }
            // Roll back - this is an availability probe only.
        }
        return available;
    }

    /** Repeats {@code insert} inside {@code transaction} until it stops taking anything. */
    private static int pumpInsert(EnergyHandler handler, int wanted, Transaction transaction) {
        int placed = 0;
        for (int attempt = 0; attempt < PUMP_ATTEMPTS && placed < wanted; attempt++) {
            int step = handler.insert(wanted - placed, transaction);
            if (step <= 0) {
                break;
            }
            placed += step;
        }
        return placed;
    }

    /** Repeats {@code extract} inside {@code transaction} until it stops giving anything. */
    private static int pumpExtract(EnergyHandler handler, int wanted, Transaction transaction) {
        int taken = 0;
        for (int attempt = 0; attempt < PUMP_ATTEMPTS && taken < wanted; attempt++) {
            int step = handler.extract(wanted - taken, transaction);
            if (step <= 0) {
                break;
            }
            taken += step;
        }
        return taken;
    }

    /** The side of {@code pipe} an energy storage sits on, if any. */
    public static Optional<Direction> energySide(ServerLevel level, BlockPos pipe) {
        for (Direction direction : Direction.values()) {
            if (handlerAt(level, pipe, direction) != null) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /** The side of {@code pipe} holding at least some FE, if any. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe) {
        return sideHolding(level, pipe, Set.of());
    }

    /** As {@link #sideHolding(ServerLevel, BlockPos)}, skipping {@code excluded} storages. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe,
                                                  Set<BlockPos> excluded) {
        for (Direction direction : Direction.values()) {
            EnergyHandler handler = handlerAt(level, pipe, direction, excluded);
            if (handler != null && handler.getAmountAsInt() > 0) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /**
     * Positions of every energy storage touching {@code pipe}.
     *
     * <p>The exclusion set a requester hands to the provider side, so nothing on the
     * network offers to sell it energy out of its own storage.
     */
    public static Set<BlockPos> attachedStorages(ServerLevel level, BlockPos pipe) {
        Set<BlockPos> storages = new HashSet<>();
        for (Direction direction : Direction.values()) {
            if (handlerAt(level, pipe, direction) != null) {
                storages.add(pipe.relative(direction).immutable());
            }
        }
        return storages;
    }

    /** The side of {@code pipe} that would take at least one FE right now, if any. */
    public static Optional<Direction> sideAccepting(ServerLevel level, BlockPos pipe) {
        for (Direction direction : Direction.values()) {
            EnergyHandler handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            try (Transaction probe = Transaction.openRoot()) {
                if (handler.insert(1, probe) > 0) {
                    return Optional.of(direction);
                }
            }
        }
        return energySide(level, pipe);
    }

    private static EnergyHandler handlerAt(ServerLevel level, BlockPos pipe, Direction direction) {
        return handlerAt(level, pipe, direction, Set.of());
    }

    private static EnergyHandler handlerAt(ServerLevel level, BlockPos pipe, Direction direction,
                                           Set<BlockPos> excluded) {
        BlockPos neighbour = pipe.relative(direction);
        if (!level.hasChunkAt(neighbour) || excluded.contains(neighbour)) {
            return null;
        }
        // Same reasoning as InventoryAccess: a pipe is transit fabric, not a destination,
        // so a neighbouring pipe is never treated as an energy storage to drain or fill.
        if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Energy.BLOCK, neighbour, direction.getOpposite());
    }
}

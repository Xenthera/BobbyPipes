package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.PipeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.fluid.FluidResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and moves fluid in the tanks touching a pipe.
 *
 * <p>Mirrors {@link InventoryAccess} exactly, same {@code ResourceHandler} capability
 * shape, just parameterized on {@link FluidResource} instead of {@code ItemResource} and
 * looked up through {@link Capabilities.Fluid#BLOCK} instead of the item capability.
 */
public final class FluidAccess {

    /**
     * How many times one tank is asked to move fluid before we accept that it is done.
     * Same per-operation transfer caps as energy storages; the bound is only there so a
     * handler that always returns a positive amount cannot spin forever.
     */
    private static final int PUMP_ATTEMPTS = 64;

    private FluidAccess() {
    }

    /** Merged contents of tanks touching {@code pipe}, keyed by exact fluid identity. */
    public static Map<FluidResource, Integer> summarize(ServerLevel level, BlockPos pipe) {
        Map<FluidResource, Integer> totals = new LinkedHashMap<>();
        for (Direction direction : Direction.values()) {
            ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.size(); slot++) {
                FluidResource resource = handler.getResource(slot);
                if (resource.isEmpty()) {
                    continue;
                }
                int amount = handler.getAmountAsInt(slot);
                if (amount > 0) {
                    totals.merge(resource, amount, Integer::sum);
                }
            }
        }
        return totals;
    }

    /** Total mB of {@code fluid} in tanks touching {@code pipe}. */
    public static int count(ServerLevel level, BlockPos pipe, FluidResource fluid) {
        if (fluid.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Direction direction : Direction.values()) {
            ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.size(); slot++) {
                if (fluid.equals(handler.getResource(slot))) {
                    total += handler.getAmountAsInt(slot);
                }
            }
        }
        return total;
    }

    /**
     * Takes up to {@code wanted} mB of {@code fluid} out of tanks touching {@code pipe}.
     *
     * @return how many mB were actually removed
     */
    public static int extract(ServerLevel level, BlockPos pipe, FluidResource fluid, int wanted) {
        return extract(level, pipe, fluid, wanted, Set.of());
    }

    /**
     * Like {@link #extract(ServerLevel, BlockPos, FluidResource, int)}, but tanks at any
     * position in {@code excluded} are left alone.
     *
     * <p>Same self-feeding reasoning as the energy side: a Provider pipe on the far side of
     * the tank a Supplier is trying to fill is a different node touching the same tank, so
     * without this the fluid leaves and comes straight back, forever.
     */
    public static int extract(ServerLevel level, BlockPos pipe, FluidResource fluid, int wanted,
                              Set<BlockPos> excluded) {
        if (fluid.isEmpty() || wanted <= 0) {
            return 0;
        }
        int taken = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (taken >= wanted) {
                    break;
                }
                ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                taken += pumpExtract(handler, fluid, wanted - taken, transaction);
            }
            transaction.commit();
        }
        return taken;
    }

    /**
     * Puts up to {@code amount} mB of {@code fluid} into tanks touching {@code pipe}.
     *
     * @return how many mB were accepted, which may be zero if everything nearby is full
     */
    public static int insert(ServerLevel level, BlockPos pipe, FluidResource fluid, int amount) {
        return insert(level, pipe, fluid, amount, Set.of());
    }

    /** As {@link #insert(ServerLevel, BlockPos, FluidResource, int)}, skipping {@code excluded} tanks. */
    public static int insert(ServerLevel level, BlockPos pipe, FluidResource fluid, int amount,
                             Set<BlockPos> excluded) {
        if (fluid.isEmpty() || amount <= 0) {
            return 0;
        }
        int placed = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (placed >= amount) {
                    break;
                }
                ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                placed += pumpInsert(handler, fluid, amount - placed, transaction);
            }
            transaction.commit();
        }
        return placed;
    }

    /**
     * How many mB of {@code fluid} tanks touching {@code pipe} could accept right now.
     *
     * <p>Uses a rollback transaction so nothing is actually moved, so a full destination
     * throttles how much gets drawn from a source rather than shipping a full packet and
     * voiding whatever does not fit.
     */
    public static int insertable(ServerLevel level, BlockPos pipe, FluidResource fluid, int wanted) {
        if (fluid.isEmpty() || wanted <= 0) {
            return 0;
        }
        int accepted = 0;
        try (Transaction transaction = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (accepted >= wanted) {
                    break;
                }
                ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction);
                if (handler == null) {
                    continue;
                }
                accepted += pumpInsert(handler, fluid, wanted - accepted, transaction);
            }
            // Roll back - this is a capacity probe only.
        }
        return accepted;
    }

    /**
     * How many mB of {@code fluid} could actually be pulled out of tanks touching
     * {@code pipe} right now, ignoring {@code excluded} tanks.
     *
     * <p>Rollback probe. Deliberately not {@link #count}: a tank that reports a full buffer
     * may still refuse extraction, and stock that would only be shipped back where it came
     * from is not stock the network can offer.
     */
    public static int extractable(ServerLevel level, BlockPos pipe, FluidResource fluid, int wanted,
                                  Set<BlockPos> excluded) {
        if (fluid.isEmpty() || wanted <= 0) {
            return 0;
        }
        int available = 0;
        try (Transaction probe = Transaction.openRoot()) {
            for (Direction direction : Direction.values()) {
                if (available >= wanted) {
                    break;
                }
                ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction, excluded);
                if (handler == null) {
                    continue;
                }
                available += pumpExtract(handler, fluid, wanted - available, probe);
            }
            // Roll back - this is an availability probe only.
        }
        return available;
    }

    /**
     * Repeats {@code insert} inside {@code transaction} until it stops taking anything.
     *
     * <p>Tanks and machine buffers routinely cap a single transfer call at their per-
     * operation rate, so believing one call under-reports both free space and free stock,
     * and under-delivers a parcel whose remainder then gets voided.
     */
    private static int pumpInsert(ResourceHandler<FluidResource> handler, FluidResource fluid,
                                  int wanted, Transaction transaction) {
        int placed = 0;
        for (int attempt = 0; attempt < PUMP_ATTEMPTS && placed < wanted; attempt++) {
            int step = handler.insert(fluid, wanted - placed, transaction);
            if (step <= 0) {
                break;
            }
            placed += step;
        }
        return placed;
    }

    /** Repeats {@code extract} inside {@code transaction} until it stops giving anything. */
    private static int pumpExtract(ResourceHandler<FluidResource> handler, FluidResource fluid,
                                   int wanted, Transaction transaction) {
        int taken = 0;
        for (int attempt = 0; attempt < PUMP_ATTEMPTS && taken < wanted; attempt++) {
            int step = handler.extract(fluid, wanted - taken, transaction);
            if (step <= 0) {
                break;
            }
            taken += step;
        }
        return taken;
    }

    /** The side of {@code pipe} holding {@code fluid}, if any. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe, FluidResource fluid) {
        return sideHolding(level, pipe, fluid, Set.of());
    }

    /** As {@link #sideHolding(ServerLevel, BlockPos, FluidResource)}, skipping {@code excluded} tanks. */
    public static Optional<Direction> sideHolding(ServerLevel level, BlockPos pipe, FluidResource fluid,
                                                  Set<BlockPos> excluded) {
        if (fluid.isEmpty()) {
            return Optional.empty();
        }
        for (Direction direction : Direction.values()) {
            ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction, excluded);
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.size(); slot++) {
                if (fluid.equals(handler.getResource(slot)) && handler.getAmountAsInt(slot) > 0) {
                    return Optional.of(direction);
                }
            }
        }
        return Optional.empty();
    }

    /** The side of {@code pipe} that would take {@code fluid} right now, if any. */
    public static Optional<Direction> sideAccepting(ServerLevel level, BlockPos pipe, FluidResource fluid) {
        for (Direction direction : Direction.values()) {
            ResourceHandler<FluidResource> handler = handlerAt(level, pipe, direction);
            if (handler == null) {
                continue;
            }
            try (Transaction probe = Transaction.openRoot()) {
                if (handler.insert(fluid, 1, probe) > 0) {
                    return Optional.of(direction);
                }
            }
        }
        for (Direction direction : Direction.values()) {
            if (handlerAt(level, pipe, direction) != null) {
                return Optional.of(direction);
            }
        }
        return Optional.empty();
    }

    /** Storage identities of every tank touching {@code pipe}. */
    public static Set<Object> attachedIdentities(ServerLevel level, BlockPos pipe) {
        Set<Object> identities = new HashSet<>();
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pipe.relative(direction);
            if (handlerAt(level, pipe, direction) == null) {
                continue;
            }
            identities.add(StorageIdentities.of(level, neighbour));
        }
        return identities;
    }

    /** Positions of every tank touching {@code pipe}, for use as an exclusion set. */
    public static Set<BlockPos> attachedTanks(ServerLevel level, BlockPos pipe) {
        Set<BlockPos> tanks = new HashSet<>();
        for (Direction direction : Direction.values()) {
            if (handlerAt(level, pipe, direction) != null) {
                tanks.add(pipe.relative(direction).immutable());
            }
        }
        return tanks;
    }

    private static ResourceHandler<FluidResource> handlerAt(ServerLevel level, BlockPos pipe,
                                                             Direction direction) {
        return handlerAt(level, pipe, direction, Set.of());
    }

    private static ResourceHandler<FluidResource> handlerAt(ServerLevel level, BlockPos pipe,
                                                            Direction direction,
                                                            Set<BlockPos> excluded) {
        BlockPos neighbour = pipe.relative(direction);
        if (!level.hasChunkAt(neighbour) || excluded.contains(neighbour)) {
            return null;
        }
        if (level.getBlockState(neighbour).getBlock() instanceof PipeBlock) {
            return null;
        }
        return level.getCapability(Capabilities.Fluid.BLOCK, neighbour, direction.getOpposite());
    }
}

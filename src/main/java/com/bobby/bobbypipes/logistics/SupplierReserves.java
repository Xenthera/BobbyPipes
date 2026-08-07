package com.bobby.bobbypipes.logistics;

import com.bobby.bobbypipes.block.entity.EnergySupplierPipeBlockEntity;
import com.bobby.bobbypipes.block.entity.FluidSupplierPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * How much of a Provider's stock is spoken for by a Supplier maintaining the same storage.
 *
 * <p>A Supplier target is a floor the network promises to keep, so a Provider touching that
 * same storage may only offer what sits <em>above</em> the floor. Without this, two storages
 * that each carry a Provider and a Supplier take turns draining each other: A's Supplier
 * pulls from B's Provider, which drops B below its target, so B's Supplier pulls from A's
 * Provider, and so on. Energy sloshes back and forth forever, neither target is ever
 * satisfied, unrelated requests starve behind the churn, and any generator feeding the loop
 * keeps topping both storages up until they are full far past their targets.
 *
 * <p>Reserving the target makes the fixed point stable instead: once a storage holds its
 * target, its Provider offers nothing, so nothing drains it, so its Supplier stops asking.
 *
 * <p>The floor only applies when that Supplier and this Provider sit on the <em>same</em>
 * routed component. A Supplier on one network and a Provider on another that happen to
 * share a battery are not in a cycle with each other: the Provider may export freely and
 * the Supplier may restock from its own network without those rules interfering.
 */
public final class SupplierReserves {

    private SupplierReserves() {
    }

    /**
     * FE that Suppliers are holding on the storages touching {@code providerPipe}.
     *
     * <p>Per storage the largest target wins rather than the sum: two Suppliers on one
     * battery box both want the box at their own target, so the binding promise is the
     * larger of the two, not both added together.
     */
    public static int reservedFe(ServerLevel level, BlockPos providerPipe) {
        RoutingSnapshot<BlockPos> routes = PipeNetwork.get(level).routes();
        int reserved = 0;
        for (BlockPos storage : EnergyAccess.attachedStorages(level, providerPipe)) {
            int highest = 0;
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = storage.relative(direction);
                if (blockEntityAt(level, neighbour) instanceof EnergySupplierPipeBlockEntity supplier
                        && sameNetwork(routes, providerPipe, neighbour)) {
                    highest = Math.max(highest, supplier.targetFe());
                }
            }
            reserved += highest;
        }
        return reserved;
    }

    /**
     * mB of {@code fluid} that Suppliers are holding on the tanks touching
     * {@code providerPipe}. Same reasoning as {@link #reservedFe}; a Supplier targeting a
     * different fluid reserves nothing of this one.
     */
    public static int reservedMb(ServerLevel level, BlockPos providerPipe, FluidResource fluid) {
        if (fluid.isEmpty()) {
            return 0;
        }
        RoutingSnapshot<BlockPos> routes = PipeNetwork.get(level).routes();
        int reserved = 0;
        for (BlockPos tank : FluidAccess.attachedTanks(level, providerPipe)) {
            int highest = 0;
            for (Direction direction : Direction.values()) {
                BlockPos neighbour = tank.relative(direction);
                if (blockEntityAt(level, neighbour) instanceof FluidSupplierPipeBlockEntity supplier
                        && fluid.equals(supplier.targetFluid())
                        && sameNetwork(routes, providerPipe, neighbour)) {
                    highest = Math.max(highest, supplier.targetMb());
                }
            }
            reserved += highest;
        }
        return reserved;
    }

    /**
     * True when both pipes are on the same connected component of the current routing
     * snapshot. Missing either end means they cannot cycle through this network.
     */
    private static boolean sameNetwork(RoutingSnapshot<BlockPos> routes,
                                       BlockPos providerPipe,
                                       BlockPos supplierPipe) {
        return routes.topology().componentOf(providerPipe).contains(supplierPipe);
    }

    private static BlockEntity blockEntityAt(ServerLevel level, BlockPos pos) {
        // Never load a chunk to answer this: an unloaded neighbour reserves nothing, which
        // is the same answer the routing layer gives for anything it cannot see.
        return level.hasChunkAt(pos) ? level.getBlockEntity(pos) : null;
    }
}

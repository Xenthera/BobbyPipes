package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Publishes its ghost targets as standing demand and waits.
 *
 * <p>The counterpart to {@link SupplierPipeBlockEntity}: same configuration, opposite
 * direction of pull. This pipe never asks the network for anything. It is a sink, and the
 * only items it ever receives are ones already loose on the network with nowhere better to
 * go - pushed in by a hopper, drifting out of plain pipe, or left over from a craft.
 * {@code SinkFinder} offers those to a passive supplier that is still short before it falls
 * back to a default route, so stocking a machine's input chest costs no requests at all.
 *
 * <p>It therefore has no ticker. There is nothing for it to do on its own: all the
 * behaviour lives in whether {@code SinkFinder} finds a shortfall here at the moment an
 * item needs a home.
 */
public class PassiveSupplierPipeBlockEntity extends StockTargetPipeBlockEntity {

    public PassiveSupplierPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PASSIVE_SUPPLIER_PIPE.get(), pos, state,
                "menu.bobbypipes.passive_supplier_pipe");
    }
}

package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * Burns ordinary furnace fuel into FE.
 *
 * <p>Deliberately the dull option: any fuel works, the rate is flat, and the only control is
 * what you feed it. It exists so a fresh network can be powered at all.
 */
public class FuelGeneratorBlockEntity extends GeneratorBlockEntity
        implements net.minecraft.world.MenuProvider {

    public static final int CAPACITY = 100_000;
    /**
     * FE produced per tick while burning: 100/t, so 2,000 FE a second.
     *
     * <p>One coal is 1,600 ticks of burn, so roughly 160,000 FE per coal - more than a buffer
     * full. Set against a 25 FE craft and a 5 FE provider pull, one of these comfortably runs
     * a working network, which is the point of it being the starter generator.
     */
    public static final int FE_PER_TICK = 100;
    /** Max FE handed to one neighbour per tick. Above the burn rate so it never backs up. */
    private static final int TRANSFER_PER_TICK = 1_000;

    /** Ticks of burn left from the item currently being consumed. */
    private int burnTicks;
    /** How long that item burns in total, for the lit-state and any future progress bar. */
    private int burnTicksTotal;

    private final ItemStacksResourceHandler fuel = new ItemStacksResourceHandler(1) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };

    public FuelGeneratorBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.FUEL_GENERATOR.get(), pos, state);
    }

    @Override
    public int capacity() {
        return CAPACITY;
    }

    @Override
    protected int transferPerTick() {
        return TRANSFER_PER_TICK;
    }

    public ResourceHandler<ItemResource> itemHandler() {
        return fuel;
    }

    public ItemStacksResourceHandler fuel() {
        return fuel;
    }

    public boolean isBurning() {
        return burnTicks > 0;
    }

    public int burnTicks() {
        return burnTicks;
    }

    public int burnTicksTotal() {
        return burnTicksTotal;
    }

    /**
     * Burns, generates, and pushes. Returns true when the lit state changed, so the block can
     * repaint only on a real transition rather than every tick.
     */
    public boolean serverTick(Level level, BlockPos pos) {
        boolean wasBurning = isBurning();

        if (burnTicks > 0) {
            burnTicks--;
            generate(FE_PER_TICK);
        }
        // Only light a fresh item when there is somewhere to put the energy. Burning into a
        // full buffer would silently eat fuel for nothing.
        if (burnTicks <= 0 && !isFull()) {
            consumeOneFuel(level);
        }

        pushToNeighbours(level, pos);

        if (wasBurning != isBurning()) {
            setChanged();
            return true;
        }
        return false;
    }

    /** Takes one item from the fuel slot and lights it, if it burns. */
    private void consumeOneFuel(Level level) {
        ItemResource resource = fuel.getResource(0);
        if (resource.isEmpty() || fuel.getAmountAsInt(0) <= 0) {
            return;
        }
        ItemStack probe = resource.toStack(1);
        int duration = level.fuelValues().burnDuration(probe);
        if (duration <= 0) {
            return;
        }
        try (var tx = net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
            if (fuel.extract(0, resource, 1, tx) != 1) {
                return;
            }
            tx.commit();
        }
        burnTicks = duration;
        burnTicksTotal = duration;
        setChanged();
    }

    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        return net.minecraft.network.chat.Component.translatable("block.bobbypipes.fuel_generator");
    }

    @Override
    public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
            int id, net.minecraft.world.entity.player.Inventory inventory,
            net.minecraft.world.entity.player.Player player) {
        return new com.bobby.bobbypipes.menu.FuelGeneratorMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putInt("burn_ticks", burnTicks);
        output.putInt("burn_ticks_total", burnTicksTotal);
        fuel.serialize(output.child("fuel"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        burnTicks = Math.max(0, input.getIntOr("burn_ticks", 0));
        burnTicksTotal = Math.max(0, input.getIntOr("burn_ticks_total", 0));
        input.child("fuel").ifPresent(fuel::deserialize);
    }
}

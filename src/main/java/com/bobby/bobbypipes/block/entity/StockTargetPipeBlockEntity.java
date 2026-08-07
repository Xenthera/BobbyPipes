package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.SupplierPipeMenu;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.item.ItemResource;
import org.jspecify.annotations.Nullable;

/**
 * A pipe configured with a row of ghost stock targets.
 *
 * <p>Two pipes are configured this way and differ only in how the shortfall is closed. The
 * {@link SupplierPipeBlockEntity} goes and gets it, asking the network for what is missing.
 * The {@link PassiveSupplierPipeBlockEntity} waits for it, publishing the shortfall as
 * demand so items already loose on the network land here instead of a default route. The
 * configuration, its storage, its sync and its screen are the same for both, so they live
 * here and each subclass only supplies its half of the behaviour.
 */
public abstract class StockTargetPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    private final String titleKey;
    private SupplierRequests requests = SupplierRequests.EMPTY;

    protected StockTargetPipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                         String titleKey) {
        super(type, pos, state);
        this.titleKey = titleKey;
    }

    public SupplierRequests requests() {
        return requests;
    }

    public void setRequests(SupplierRequests requests) {
        this.requests = requests == null ? SupplierRequests.EMPTY : requests;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    /**
     * How much of {@code item} this pipe is configured to keep, across every slot.
     *
     * <p>Summed rather than first-match so two slots holding the same item read as one
     * larger target, which is what a player who filled two slots plainly meant.
     */
    public int targetFor(ItemResource item) {
        if (item.isEmpty()) {
            return 0;
        }
        int target = 0;
        for (int i = 0; i < SupplierRequests.SLOT_COUNT; i++) {
            ItemStack ghost = requests.slot(i);
            if (!ghost.isEmpty() && ItemResource.of(ghost).equals(item)) {
                target += ghost.getCount();
            }
        }
        return target;
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable(titleKey);
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new SupplierPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("requests", SupplierRequests.CODEC, requests);
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        requests = input.read("requests", SupplierRequests.CODEC).orElse(SupplierRequests.EMPTY);
    }
}

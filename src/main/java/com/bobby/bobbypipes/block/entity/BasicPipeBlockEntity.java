package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.menu.BasicPipeMenu;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.TransactionContext;
import org.jspecify.annotations.Nullable;

import com.bobby.bobbypipes.logistics.SinkFinder;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.RequestService;
import com.bobby.bobbypipes.transit.ItemShipment;

import java.util.Optional;

/**
 * Settings for a basic routed pipe. Currently only the default-route flag.
 */
public class BasicPipeBlockEntity extends PipeBlockEntity implements MenuProvider {

    /** Slots a hopper or another mod's pipe can push into. */
    public static final int INTAKE_SLOTS = 1;

    private boolean defaultRoute;

    /**
     * Landing area for items pushed in from outside.
     *
     * <p>A real buffer rather than injecting a parcel straight from the insert call. The
     * transfer API's {@code TransactionContext} exposes only a depth, with no way to hook
     * a rollback, so an insert that was later aborted would have already put an item on
     * the network and duplicated it. A normal handler already gets transactions right, so
     * items land here and the tick drains them.
     */
    private final ItemStacksResourceHandler intake = new ItemStacksResourceHandler(INTAKE_SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };

    /**
     * What neighbours see: insert only.
     *
     * <p>Extraction is refused so a hopper under a pipe cannot drain the network, which
     * would otherwise be an easy way to siphon a whole system.
     */
    private final ResourceHandler<ItemResource> exposed = new ResourceHandler<>() {
        @Override
        public int size() {
            return intake.size();
        }

        @Override
        public ItemResource getResource(int index) {
            return intake.getResource(index);
        }

        @Override
        public long getAmountAsLong(int index) {
            return intake.getAmountAsLong(index);
        }

        @Override
        public long getCapacityAsLong(int index, ItemResource resource) {
            return intake.getCapacityAsLong(index, resource);
        }

        @Override
        public boolean isValid(int index, ItemResource resource) {
            return intake.isValid(index, resource);
        }

        @Override
        public int insert(int index, ItemResource resource, int amount, TransactionContext tx) {
            return intake.insert(index, resource, amount, tx);
        }

        @Override
        public int extract(int index, ItemResource resource, int amount, TransactionContext tx) {
            return 0;
        }
    };

    public BasicPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.BASIC_PIPE.get(), pos, state);
    }

    public ResourceHandler<ItemResource> itemHandler() {
        return exposed;
    }

    /**
     * Sends anything pushed into this pipe on toward a default route.
     *
     * <p>Nowhere to send it means it stays in the buffer, so the hopper backs up rather
     * than the network eating items it cannot deliver.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  BasicPipeBlockEntity pipe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        for (int slot = 0; slot < pipe.intake.size(); slot++) {
            ItemResource item = pipe.intake.getResource(slot);
            int held = pipe.intake.getAmountAsInt(slot);
            if (item.isEmpty() || held <= 0) {
                continue;
            }
            PipeNetwork network = PipeNetwork.get(serverLevel);
            if (!network.routes().contains(pos)) {
                // Not on a solved network yet; try again next tick.
                continue;
            }
            Optional<SinkFinder.Sink> target =
                    SinkFinder.nearest(serverLevel, network, pos, item, held);
            if (target.isEmpty() || target.get().pos().equals(pos)) {
                continue;
            }
            int sent = RequestService.pushFromPipe(
                    serverLevel, network, pos, target.get().pos(), item, target.get().accept());
            if (sent > 0) {
                try (var transaction =
                             net.neoforged.neoforge.transfer.transaction.Transaction.openRoot()) {
                    pipe.intake.extract(slot, item, sent, transaction);
                    transaction.commit();
                }
            }
        }
    }

    public boolean isDefaultRoute() {
        return defaultRoute;
    }

    public void setDefaultRoute(boolean defaultRoute) {
        this.defaultRoute = defaultRoute;
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.basic_pipe");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new BasicPipeMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putBoolean("defaultRoute", defaultRoute);
        intake.serialize(output.child("intake"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        defaultRoute = input.getBooleanOr("defaultRoute", false);
        input.child("intake").ifPresent(intake::deserialize);
    }

    /** Anything still waiting to enter the network drops rather than being deleted. */
    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            for (int slot = 0; slot < intake.size(); slot++) {
                ItemResource resource = intake.getResource(slot);
                int amount = intake.getAmountAsInt(slot);
                if (!resource.isEmpty() && amount > 0) {
                    net.minecraft.world.Containers.dropItemStack(
                            level, pos.getX(), pos.getY(), pos.getZ(), resource.toStack(amount));
                }
            }
        }
        super.preRemoveSideEffects(pos, state);
    }
}

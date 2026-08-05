package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.network.InventoryAccess;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.RequestService;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Keeps the attached inventory stocked to the configured ghost targets by requesting
 * shortfalls from the network.
 */
public class SupplierPipeBlockEntity extends StockTargetPipeBlockEntity {

    /** How often to scan for shortfalls (1 second at 20 tps). */
    public static final int TICK_INTERVAL = 20;

    public SupplierPipeBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.SUPPLIER_PIPE.get(), pos, state, "menu.bobbypipes.supplier_pipe");
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SupplierPipeBlockEntity pipe) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }
        if (serverLevel.getGameTime() % TICK_INTERVAL != 0) {
            return;
        }
        pipe.restock(serverLevel);
    }

    private void restock(ServerLevel level) {
        if (requests().isEmpty()) {
            return;
        }
        PipeNetwork network = PipeNetwork.get(level);
        // Use the live snapshot. Forcing rebuildNow every second used to republish the
        // graph on a 20-tick cadence and hitch every parcel in the level.
        if (!network.routes().contains(worldPosition)
                && !network.rebuildNow(worldPosition).contains(worldPosition)) {
            return;
        }
        Set<Object> selfStores = InventoryAccess.attachedIdentities(level, worldPosition);
        // Sum ghost counts per item (same as {@link #targetFor}) so two 64-log slots
        // mean "keep 128", not two independent targets of 64 that collapse after the first.
        Map<ItemResource, Integer> targets = new LinkedHashMap<>();
        for (int i = 0; i < SupplierRequests.SLOT_COUNT; i++) {
            ItemStack ghost = requests().slot(i);
            if (!ghost.isEmpty()) {
                targets.merge(ItemResource.of(ghost), ghost.getCount(), Integer::sum);
            }
        }
        for (Map.Entry<ItemResource, Integer> entry : targets.entrySet()) {
            ItemResource item = entry.getKey();
            int target = entry.getValue();
            // Queued craft still covering this item, but providers now have stock:
            // drop the unstarted craft tree and re-request so stock wins. Crafts that
            // have already pulled ingredients or reached the machine are left alone.
            if (providerFree(network, item, selfStores) > 0) {
                network.craftJobs().cancelUnstartedOwedTo(worldPosition, item);
            }
            int have = InventoryAccess.count(level, worldPosition, item);
            int inbound = network.inboundTo(worldPosition, item);
            int need = target - have - inbound;
            if (need <= 0) {
                continue;
            }
            int room = InventoryAccess.insertable(level, worldPosition, item, need);
            need = Math.min(need, room);
            if (need <= 0) {
                continue;
            }
            // Same RequestPlanner to commit chain as the request pipe (with our chest
            // excluded), so work is split across every crafting pipe of that recipe.
            RequestService.requestAvailable(level, worldPosition, item, need, selfStores);
        }
    }

    /** Free provider stock of {@code item}, ignoring this supplier's own attached stores. */
    private int providerFree(PipeNetwork network, ItemResource item, Set<Object> selfStores) {
        int free = 0;
        for (var stock : network.supplyFor(worldPosition, selfStores).available(item)) {
            free += stock.amount();
        }
        return free;
    }
}

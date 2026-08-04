package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.block.CraftingPipeBlock;
import com.bobby.bobbypipes.block.ProviderPipeBlock;
import com.bobby.bobbypipes.block.entity.CraftingPipeBlockEntity;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.request.Demand;
import com.bobby.bobbypipes.request.Supply;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads real inventories out of the world and presents them to the request planner.
 *
 * <p>{@link ItemResource} is the item identity: it carries the item and its data
 * components, so a damaged pickaxe and a fresh one are correctly different things, which a
 * raw {@code Item} key would have conflated.
 *
 * <p>Providers are offered in routing-cost order from the requester, which is what makes a
 * request pull from the nearest chest. The planner drains that list in order and does not
 * re-sort it.
 *
 * <p>Free stock subtracts items still queued at the provider (accepted but not yet
 * extracted). In-flight promises are already gone from the chest, so they must not be
 * subtracted again.
 *
 * <p>Only {@link ProviderPipeBlock} nodes offer stock. Being a provider is opt-in so a
 * plain transport pipe past a chest does not quietly hand out its contents.
 *
 * <p>Shared stores (BobbyChests networked channel, or two pipes on one chest) are offered
 * once via the nearest provider. Without that, the catalog and planner would sum the same
 * backing inventory for every pipe that touches it.
 */
public final class NetworkSupply implements Supply<BlockPos, ItemResource> {

    private final ServerLevel level;
    private final RoutingSnapshot<BlockPos> routes;
    private final BlockPos requester;
    private final ProviderSendQueue sendQueue;
    /** Storage identities the requester must not pull from (e.g. a Supplier's own chest). */
    private final Set<Object> excludedStores;

    public NetworkSupply(ServerLevel level,
                         RoutingSnapshot<BlockPos> routes,
                         BlockPos requester,
                         ProviderSendQueue sendQueue) {
        this(level, routes, requester, sendQueue, Set.of());
    }

    public NetworkSupply(ServerLevel level,
                         RoutingSnapshot<BlockPos> routes,
                         BlockPos requester,
                         ProviderSendQueue sendQueue,
                         Set<Object> excludedStores) {
        this.level = level;
        this.routes = routes;
        this.requester = requester;
        this.sendQueue = sendQueue;
        this.excludedStores = Set.copyOf(excludedStores);
    }

    @Override
    public List<Stock<BlockPos, ItemResource>> available(ItemResource item) {
        if (item.isEmpty()) {
            return List.of();
        }
        List<Stock<BlockPos, ItemResource>> found = new ArrayList<>();
        Set<Object> claimedStores = new HashSet<>(excludedStores);
        for (BlockPos pipe : providerNodesByDistance()) {
            if (!isProvider(pipe)) {
                continue;
            }
            int held = InventoryAccess.countUnclaimed(level, pipe, item, claimedStores);
            if (held <= 0) {
                continue;
            }
            int free = held - sendQueue.queued(pipe, item);
            if (free > 0) {
                found.add(new Stock<>(pipe, item, free));
            }
        }
        return found;
    }

    @Override
    public List<Craft<BlockPos, ItemResource>> recipesFor(ItemResource item) {
        if (item.isEmpty()) {
            return List.of();
        }
        List<Craft<BlockPos, ItemResource>> crafts = new ArrayList<>();
        for (BlockPos pipe : providerNodesByDistance()) {
            if (!(level.getBlockState(pipe).getBlock() instanceof CraftingPipeBlock)) {
                continue;
            }
            if (!(level.getBlockEntity(pipe) instanceof CraftingPipeBlockEntity be)) {
                continue;
            }
            CraftPattern pattern = be.pattern();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.hasSatellite()
                    && !SatelliteLookup.isReachable(level, routes, pipe, pattern.satellite())) {
                continue;
            }
            ItemStack output = pattern.primaryOutput();
            if (output.isEmpty() || !item.equals(ItemResource.of(output))) {
                continue;
            }
            // Merge by item. ingredients() reports one entry per grid slot, so a chest
            // arrives as eight separate one plank entries. Passing those through
            // unmerged made the planner resolve a single plank at a time: one plank
            // needs ceil(1/4) = one run of the plank recipe, so it ordered one log,
            // produced four planks, credited one, and stalled. The gathering side has
            // always merged, which is why the debug readout said eight while the plan
            // said one.
            java.util.LinkedHashMap<ItemResource, Integer> totals = new java.util.LinkedHashMap<>();
            for (CraftPattern.CountedIngredient ingredient : pattern.ingredients()) {
                totals.merge(ingredient.item(), ingredient.count(), Integer::sum);
            }
            List<Demand<ItemResource>> inputs = new ArrayList<>();
            totals.forEach((ingredient, count) -> inputs.add(new Demand<>(ingredient, count)));
            if (inputs.isEmpty()) {
                continue;
            }
            crafts.add(new Craft<>(
                    pipe,
                    new Demand<>(item, Math.max(1, output.getCount())),
                    inputs));
        }
        return crafts;
    }

    /**
     * Free stock plus craftable outputs for the request GUI grid.
     *
     * <p>Sorted by item id so the grid is stable between opens; planning still prefers
     * nearest providers via {@link #available}.
     */
    public List<CatalogEntry> catalog() {
        Map<ItemResource, Integer> totals = new LinkedHashMap<>();
        Map<ItemResource, Boolean> craftable = new LinkedHashMap<>();
        Set<Object> claimedStores = new HashSet<>(excludedStores);
        for (BlockPos pipe : providerNodesByDistance()) {
            if (isProvider(pipe)) {
                for (Map.Entry<ItemResource, Integer> held
                        : InventoryAccess.summarizeUnclaimed(level, pipe, claimedStores).entrySet()) {
                    int free = held.getValue() - sendQueue.queued(pipe, held.getKey());
                    if (free > 0) {
                        totals.merge(held.getKey(), free, Integer::sum);
                    }
                }
            }
            if (!(level.getBlockState(pipe).getBlock() instanceof CraftingPipeBlock)) {
                continue;
            }
            if (!(level.getBlockEntity(pipe) instanceof CraftingPipeBlockEntity be)) {
                continue;
            }
            CraftPattern pattern = be.pattern();
            if (pattern.isEmpty()) {
                continue;
            }
            if (pattern.hasSatellite()
                    && !SatelliteLookup.isReachable(level, routes, pipe, pattern.satellite())) {
                continue;
            }
            ItemStack output = pattern.primaryOutput();
            if (output.isEmpty()) {
                continue;
            }
            craftable.put(ItemResource.of(output), true);
        }
        List<ItemResource> items = new ArrayList<>();
        for (ItemResource item : totals.keySet()) {
            items.add(item);
        }
        for (ItemResource item : craftable.keySet()) {
            if (!totals.containsKey(item)) {
                items.add(item);
            }
        }
        items.sort(Comparator.comparing(item -> BuiltInRegistries.ITEM.getKey(item.getItem()).toString()));
        List<CatalogEntry> entries = new ArrayList<>(items.size());
        for (ItemResource item : items) {
            entries.add(new CatalogEntry(item, totals.getOrDefault(item, 0), craftable.containsKey(item)));
        }
        return entries;
    }

    /**
     * One grid cell: free network stock and whether a reachable crafting pipe can make it.
     * Craftable-only entries may have {@code amount == 0}.
     */
    public record CatalogEntry(ItemResource item, int amount, boolean craftable) {
        public CatalogEntry {
            if (amount < 0) {
                throw new IllegalArgumentException("catalog amount must be non-negative, got " + amount);
            }
            if (amount == 0 && !craftable) {
                throw new IllegalArgumentException("catalog entry must be stocked or craftable");
            }
        }
    }

    /**
     * Every node that could hold stock, nearest first.
     *
     * <p>The requester itself is included: an inventory attached to the requesting pipe is
     * a legitimate and maximally cheap source.
     */
    private List<BlockPos> providerNodesByDistance() {
        return routes.routesFrom(requester)
                .map(table -> {
                    List<BlockPos> ordered = new ArrayList<>();
                    ordered.add(requester);
                    ordered.addAll(table.destinationsByCost());
                    return ordered;
                })
                .orElseGet(() -> routes.contains(requester) ? List.of(requester) : List.of());
    }

    private boolean isProvider(BlockPos pipe) {
        return level.hasChunkAt(pipe)
                && level.getBlockState(pipe).getBlock() instanceof ProviderPipeBlock;
    }
}

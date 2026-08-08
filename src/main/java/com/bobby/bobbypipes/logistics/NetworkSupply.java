package com.bobby.bobbypipes.logistics;

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
 * <p>Nodes are {@link PipeNodeId} so a provider or crafter across a link pipe is distinct
 * from a same-coordinate pipe in another dimension, and recipes/stock resolve against the
 * correct level.
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
public final class NetworkSupply implements Supply<PipeNodeId, ItemResource> {

    private final ServerLevel level;
    private final RoutingSnapshot<BlockPos> routes;
    private final PipeNodeId requester;
    private final ProviderSendQueue sendQueue;
    /** Storage identities the requester must not pull from (e.g. a Supplier's own chest). */
    private final Set<Object> excludedStores;
    /**
     * Resolved once per instance. A supply object lives for one request, but the planner asks
     * it for stock and recipes many times over while it walks the craft tree, and every one of
     * those was re-walking the routing table and re-expanding the link graph.
     */
    private List<PipeNodeId> providerNodes;

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
        this.requester = PipeNodeId.of(level, requester);
        this.sendQueue = sendQueue;
        this.excludedStores = Set.copyOf(excludedStores);
    }

    @Override
    public List<Stock<PipeNodeId, ItemResource>> available(ItemResource item) {
        if (item.isEmpty()) {
            return List.of();
        }
        List<Stock<PipeNodeId, ItemResource>> found = new ArrayList<>();
        Set<Object> claimedStores = new HashSet<>(excludedStores);
        for (PipeNodeId pipe : providerNodesByDistance()) {
            if (!isProvider(pipe)) {
                continue;
            }
            ServerLevel pipeLevel = levelOf(pipe);
            if (pipeLevel == null) {
                continue;
            }
            int held = ProviderAccess.countUnclaimed(pipeLevel, pipe.pos(), item, claimedStores);
            if (held <= 0) {
                continue;
            }
            int free = held - queuedAt(pipeLevel, pipe, item);
            if (free > 0) {
                found.add(new Stock<>(pipe, item, free));
            }
        }
        return found;
    }

    @Override
    public List<Craft<PipeNodeId, ItemResource>> recipesFor(ItemResource item) {
        if (item.isEmpty()) {
            return List.of();
        }
        List<Craft<PipeNodeId, ItemResource>> crafts = new ArrayList<>();
        for (PipeNodeId pipe : providerNodesByDistance()) {
            ServerLevel pipeLevel = levelOf(pipe);
            if (pipeLevel == null
                    || !(pipeLevel.getBlockState(pipe.pos()).getBlock() instanceof CraftingPipeBlock)) {
                continue;
            }
            if (!(pipeLevel.getBlockEntity(pipe.pos()) instanceof CraftingPipeBlockEntity be)) {
                continue;
            }
            CraftPattern pattern = be.pattern();
            if (pattern.isEmpty()) {
                continue;
            }
            PipeNetwork pipeNetwork = PipeNetwork.get(pipeLevel);
            if (pattern.hasSatellite()
                    && !SatelliteLookup.isReachable(
                            pipeLevel, pipeNetwork, pipe.pos(), pattern.satellite())) {
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
            LinkedHashMap<ItemResource, Integer> totals = new LinkedHashMap<>();
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
                    inputs,
                    // What this crafter is already committed to, so planning can favour the
                    // idle ones instead of splitting evenly across busy and idle alike.
                    pipeNetwork.craftJobs().outstandingRunsAt(pipe)));
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
        for (PipeNodeId pipe : providerNodesByDistance()) {
            ServerLevel pipeLevel = levelOf(pipe);
            if (pipeLevel == null) {
                continue;
            }
            if (isProvider(pipe)) {
                for (Map.Entry<ItemResource, Integer> held
                        : ProviderAccess.summarizeUnclaimed(
                                pipeLevel, pipe.pos(), claimedStores).entrySet()) {
                    int free = held.getValue() - queuedAt(pipeLevel, pipe, held.getKey());
                    if (free > 0) {
                        totals.merge(held.getKey(), free, Integer::sum);
                    }
                }
            }
            if (!(pipeLevel.getBlockState(pipe.pos()).getBlock() instanceof CraftingPipeBlock)) {
                continue;
            }
            if (!(pipeLevel.getBlockEntity(pipe.pos()) instanceof CraftingPipeBlockEntity be)) {
                continue;
            }
            CraftPattern pattern = be.pattern();
            if (pattern.isEmpty()) {
                continue;
            }
            PipeNetwork pipeNetwork = PipeNetwork.get(pipeLevel);
            if (pattern.hasSatellite()
                    && !SatelliteLookup.isReachable(
                            pipeLevel, pipeNetwork, pipe.pos(), pattern.satellite())) {
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
     * Every node that could hold stock or craft, nearest first.
     *
     * <p>The requester itself is included: an inventory attached to the requesting pipe is
     * a legitimate and maximally cheap source. Remote nodes reached through a live link
     * follow, keyed by full {@link PipeNodeId} so same-coordinate pipes in another
     * dimension are not dropped.
     */
    private List<PipeNodeId> providerNodesByDistance() {
        if (providerNodes != null) {
            return providerNodes;
        }
        List<PipeNodeId> ordered = routes.routesFrom(requester.pos())
                .map(table -> {
                    List<PipeNodeId> list = new ArrayList<>();
                    list.add(requester);
                    for (BlockPos dest : table.destinationsByCost()) {
                        list.add(PipeNodeId.of(level, dest));
                    }
                    return list;
                })
                .orElseGet(() -> routes.contains(requester.pos())
                        ? new ArrayList<>(List.of(requester))
                        : new ArrayList<>());
        Set<PipeNodeId> seen = new HashSet<>(ordered);
        // Everything the links can reach, following chains rather than a single bridge, and
        // without filtering by dimension. A run that leaves the overworld, crosses the nether
        // and comes back lands on nodes that share the requester's dimension but are not in
        // its local routing table - the old same-dimension skip dropped exactly those, and
        // only consulting bridges containing the requester missed the second hop entirely.
        for (PipeNodeId node : CrossDimPipeGraph.expandAcrossLinks(seen)) {
            if (seen.add(node)) {
                ordered.add(node);
            }
        }
        providerNodes = ordered;
        return ordered;
    }

    private int queuedAt(ServerLevel pipeLevel, PipeNodeId pipe, ItemResource item) {
        if (pipeLevel == level) {
            return sendQueue.queued(pipe.pos(), item);
        }
        return PipeNetwork.get(pipeLevel).sendQueue().queued(pipe.pos(), item);
    }

    private boolean isProvider(PipeNodeId pipe) {
        // levelOf already refuses unloaded chunks, so this read cannot force a load.
        ServerLevel pipeLevel = levelOf(pipe);
        return pipeLevel != null
                && pipeLevel.getBlockState(pipe.pos()).getBlock() instanceof ProviderPipeBlock;
    }

    /**
     * The level a provider/crafter sits in, or null when its chunk is not loaded.
     *
     * <p>The chunk check is the point. Reading a block state or block entity in an unloaded
     * chunk does not report absence - it loads the chunk synchronously. Supplier restock
     * scans every second, so an unguarded read here force-loaded a link pipe's peer chunk on
     * a one-second cycle: the chunk churned in and out, the peer's LIVE/SEVERED status
     * churned with it, and a request that landed inside one of those windows really could
     * pull from a chunk that was otherwise unloaded.
     *
     * <p>Applies to same-dimension nodes too - a far-away pipe in this level is just as
     * capable of being unloaded as one across a link.
     */
    private ServerLevel levelOf(PipeNodeId pipe) {
        ServerLevel pipeLevel = pipe.sameDimension(requester)
                ? level
                : LinkPipeRegistry.levelOf(level.getServer(), pipe);
        if (pipeLevel == null || !pipeLevel.hasChunkAt(pipe.pos())) {
            return null;
        }
        return pipeLevel;
    }
}

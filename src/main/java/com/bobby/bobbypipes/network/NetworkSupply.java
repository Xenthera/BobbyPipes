package com.bobby.bobbypipes.network;

import com.bobby.bobbypipes.request.DeliveryLedger;
import com.bobby.bobbypipes.request.Supply;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;

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
 * <p>Stock already promised to an earlier request is subtracted before it is offered, so
 * two requests a tick apart cannot both plan against the same items.
 *
 * <p>Temporary until the Provider pipe exists: every pipe with an adjacent inventory acts
 * as a provider. Once providers are an explicit pipe type this filters to those.
 */
public final class NetworkSupply implements Supply<BlockPos, ItemResource> {

    private final ServerLevel level;
    private final RoutingSnapshot<BlockPos> routes;
    private final BlockPos requester;
    private final DeliveryLedger<BlockPos, ItemResource> ledger;

    public NetworkSupply(ServerLevel level,
                         RoutingSnapshot<BlockPos> routes,
                         BlockPos requester,
                         DeliveryLedger<BlockPos, ItemResource> ledger) {
        this.level = level;
        this.routes = routes;
        this.requester = requester;
        this.ledger = ledger;
    }

    @Override
    public List<Stock<BlockPos, ItemResource>> available(ItemResource item) {
        if (item.isEmpty()) {
            return List.of();
        }
        List<Stock<BlockPos, ItemResource>> found = new ArrayList<>();
        for (BlockPos pipe : providerNodesByDistance()) {
            int held = countAdjacentTo(pipe, item);
            if (held <= 0) {
                continue;
            }
            int free = held - ledger.reserved(pipe, item);
            if (free > 0) {
                found.add(new Stock<>(pipe, item, free));
            }
        }
        return found;
    }

    @Override
    public List<Craft<BlockPos, ItemResource>> recipesFor(ItemResource item) {
        // No crafter pipe exists yet, so nothing on the network claims it can make
        // anything. The planner handles an empty list by reporting a shortfall.
        return List.of();
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

    /** Total count of {@code item} in inventories touching {@code pipe}. */
    private int countAdjacentTo(BlockPos pipe, ItemResource item) {
        int total = 0;
        for (Direction direction : Direction.values()) {
            BlockPos neighbour = pipe.relative(direction);
            if (!level.hasChunkAt(neighbour)) {
                continue;
            }
            ResourceHandler<ItemResource> handler = level.getCapability(
                    Capabilities.Item.BLOCK, neighbour, direction.getOpposite());
            if (handler == null) {
                continue;
            }
            for (int slot = 0; slot < handler.size(); slot++) {
                if (item.equals(handler.getResource(slot))) {
                    total += handler.getAmountAsInt(slot);
                }
            }
        }
        return total;
    }
}

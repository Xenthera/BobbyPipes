package com.bobby.bobbypipes.command;

import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.RouteTable;
import com.bobby.bobbypipes.network.RequestService;
import com.bobby.bobbypipes.network.RoutingSnapshot;
import com.bobby.bobbypipes.request.RequestPlan;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;
import java.util.Optional;

/**
 * Debug view of the routing layer.
 *
 * <pre>
 *   /bobbypipes network                report the network containing the nearest pipe
 *   /bobbypipes route &lt;from&gt; &lt;to&gt;      hop count and first step between two pipes
 *   /bobbypipes plan &lt;at&gt; &lt;item&gt; &lt;count&gt;    plan a request without moving anything
 *   /bobbypipes request &lt;at&gt; &lt;item&gt; &lt;count&gt; plan it and actually ship the items
 *   /bobbypipes parcels                      what is currently in flight
 * </pre>
 */
public final class NetworkCommand {

    /** How far to look for a pipe to seed the scan from, in blocks. */
    private static final int SEARCH_RADIUS = 8;

    private NetworkCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("bobbypipes")
                .requires(source -> Commands.LEVEL_GAMEMASTERS.check(source.permissions()))
                .then(Commands.literal("network").executes(NetworkCommand::reportNetwork))
                .then(Commands.literal("route")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(NetworkCommand::reportRoute))))
                .then(Commands.literal("plan")
                        .then(Commands.argument("at", BlockPosArgument.blockPos())
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> request(context, false))))))
                .then(Commands.literal("request")
                        .then(Commands.argument("at", BlockPosArgument.blockPos())
                                .then(Commands.argument("item", IdentifierArgument.id())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> request(context, true))))))
                .then(Commands.literal("parcels").executes(NetworkCommand::reportParcels))
                .then(Commands.literal("jobs").executes(NetworkCommand::reportJobs))
                .then(Commands.literal("drift").executes(NetworkCommand::reportDrift)));
    }

    private static int reportNetwork(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        PipeNetwork network = PipeNetwork.get(level);
        BlockPos around = BlockPos.containing(context.getSource().getPosition());

        Optional<BlockPos> seed = findNearbyPipe(level, around);
        if (seed.isEmpty()) {
            reply(context, "No pipe within " + SEARCH_RADIUS + " blocks.");
            return 0;
        }

        RoutingSnapshot<BlockPos> snapshot = network.rebuildNow(seed.get());
        RouteTable<BlockPos> table = snapshot.routesFrom(seed.get()).orElse(null);
        int reachable = table == null ? 0 : table.destinations().size();

        reply(context, "Network at " + format(seed.get())
                + ": " + snapshot.nodes().size() + " pipes, "
                + reachable + " reachable from there, revision " + snapshot.revision() + ".");
        return 1;
    }

    private static int reportRoute(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
        BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");

        PipeNetwork network = PipeNetwork.get(level);
        RoutingSnapshot<BlockPos> snapshot = network.rebuildNow(from);

        if (!snapshot.contains(from)) {
            reply(context, "No pipe at " + format(from) + ".");
            return 0;
        }
        Optional<Integer> cost = snapshot.cost(from, to);
        if (cost.isEmpty()) {
            reply(context, format(to) + " is not reachable from " + format(from)
                    + " (separate network, or not a pipe).");
            return 0;
        }

        BlockPos next = snapshot.nextHop(from, to).orElseThrow();
        reply(context, format(from) + " to " + format(to)
                + ": " + cost.get() + " hops, first step " + format(next) + ".");
        return 1;
    }

    /**
     * Plans a request against the real inventories on the network and reports the plan
     * without committing it.
     *
     * <p>Exercises the whole chain: routing graph, then provider discovery in cost order,
     * then the planner.
     */
    private static int request(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                               boolean commit)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel level = context.getSource().getLevel();
        BlockPos at = BlockPosArgument.getLoadedBlockPos(context, "at");
        Identifier itemId = IdentifierArgument.getId(context, "item");
        int count = IntegerArgumentType.getInteger(context, "count");

        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null) {
            reply(context, "Unknown item: " + itemId);
            return 0;
        }

        RequestService.Outcome outcome =
                RequestService.request(level, at, ItemResource.of(item), count, commit);
        if (!outcome.hasPipe()) {
            reply(context, "No pipe at " + format(at) + " to request from.");
            return 0;
        }

        RequestPlan<BlockPos, ItemResource> plan = outcome.plan();
        reply(context, "Request " + count + " " + itemId + " at " + format(at) + ":");
        if (plan.withdrawals().isEmpty()) {
            reply(context, "  nothing found on the network");
        }
        for (RequestPlan.Withdrawal<BlockPos, ItemResource> withdrawal : plan.withdrawals()) {
            reply(context, "  take " + withdrawal.amount() + " via pipe " + format(withdrawal.source()));
        }
        if (plan.isComplete()) {
            reply(context, "  plan is complete");
        } else {
            plan.missing().forEach(shortfall -> reply(context, "  short by " + shortfall.amount()));
        }

        if (!commit) {
            return plan.isComplete() ? 1 : 0;
        }

        RequestService.Commitment commitment = outcome.commitment();
        reply(context, "  shipped " + commitment.shipped() + " of " + commitment.requested()
                + (commitment.isComplete() ? "" : ", short by " + commitment.shortfall()));
        return commitment.shipped();
    }

    /**
     * Prints every item drifting through plain pipe.
     *
     * <p>Shows where each one is, where it is headed, and which side it arrived through,
     * which is what distinguishes a genuine reversal from an item being ejected and
     * re-inserted by whatever fed the pipe.
     */
    private static int reportDrift(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        var drift = PipeNetwork.get(level).drift();
        if (drift.inFlight() == 0) {
            reply(context, "Nothing drifting in plain pipe.");
            return 0;
        }
        reply(context, drift.inFlight() + " item(s) drifting:");
        for (var item : drift.items()) {
            reply(context, "  " + item.count() + "x " + item.item().toStack(1).getHoverName().getString()
                    + " at " + format(item.at())
                    + (item.next() == null ? " (choosing)" : " -> " + format(item.next()))
                    + " entered from " + (item.cameFrom() == null ? "insert" : item.cameFrom().getName())
                    + ", hop " + item.hops());
        }
        return drift.inFlight();
    }

    /**
     * Prints what every craft job is waiting on.
     *
     * <p>Same information the debug overlay shows, in a form that can be copied out of
     * chat and pasted into a bug report.
     */
    private static int reportJobs(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        PipeNetwork network = PipeNetwork.get(level);
        List<com.bobby.bobbypipes.network.CraftJobManager.JobReport> entries =
                network.craftJobs().describe(level, network);
        if (entries.isEmpty()) {
            reply(context, "No active craft jobs.");
            return 0;
        }
        reply(context, entries.size() + " craft job(s):");
        for (var entry : entries) {
            reply(context, "  " + format(entry.crafter()) + "  " + entry.headline());
            entry.detail().forEach(detail -> reply(context, "      " + detail));
        }
        return entries.size();
    }

    /** Reports what is currently moving, which is otherwise invisible until rendering lands. */
    private static int reportParcels(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        PipeNetwork network = PipeNetwork.get(level);
        int inFlight = network.parcels().inFlight();

        reply(context, inFlight + " parcel(s) in flight, "
                + network.ledger().openCount() + " open promise(s).");
        network.parcels().parcels().forEach(parcel -> reply(context,
                "  " + parcel.payload() + " at " + format(parcel.atNode())
                        + " heading to " + format(parcel.destination())));
        return inFlight;
    }

    /** Nearest pipe to {@code around}, searched outward so the closest one wins. */
    private static Optional<BlockPos> findNearbyPipe(ServerLevel level, BlockPos around) {
        List<BlockPos> candidates = BlockPos.betweenClosedStream(
                        around.offset(-SEARCH_RADIUS, -SEARCH_RADIUS, -SEARCH_RADIUS),
                        around.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))
                .map(BlockPos::immutable)
                .filter(pos -> level.getBlockState(pos).getBlock()
                        instanceof com.bobby.bobbypipes.block.PipeBlock)
                .toList();
        return candidates.stream()
                .min((a, b) -> Double.compare(a.distSqr(around), b.distSqr(around)));
    }

    private static String format(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static void reply(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context,
                              String message) {
        context.getSource().sendSuccess(() -> Component.literal(message), false);
    }
}

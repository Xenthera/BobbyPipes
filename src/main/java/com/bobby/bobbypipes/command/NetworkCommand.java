package com.bobby.bobbypipes.command;

import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.RouteTable;
import com.bobby.bobbypipes.network.RoutingSnapshot;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;

/**
 * Debug view of the routing layer.
 *
 * <p>The network has no player-facing surface yet, so this is how its behaviour gets
 * checked in a real world rather than only in unit tests.
 *
 * <pre>
 *   /bobbypipes network            report the network containing the nearest pipe
 *   /bobbypipes route &lt;from&gt; &lt;to&gt;  show hop count and the first step between two pipes
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
                                        .executes(NetworkCommand::reportRoute)))));
    }

    private static int reportNetwork(com.mojang.brigadier.context.CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        ServerLevel level = player.level();
        PipeNetwork network = PipeNetwork.get(level);

        Optional<BlockPos> seed = findNearbyPipe(level, player.blockPosition());
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

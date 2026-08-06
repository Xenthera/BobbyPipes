package com.bobby.bobbypipes.chunk;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.LoadingValidationCallback;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.common.world.chunk.TicketHelper;

/**
 * NeoForge ticket controller for creative chunk loaders.
 */
@EventBusSubscriber(modid = BobbyPipes.MOD_ID)
public final class ChunkLoaderTickets {

    public static final Identifier ID =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "chunk_loader");

    public static final TicketController CONTROLLER =
            new TicketController(ID, ChunkLoaderTickets::validateTickets);

    private ChunkLoaderTickets() {
    }

    @SubscribeEvent
    public static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    private static void validateTickets(ServerLevel level, TicketHelper helper) {
        for (BlockPos owner : helper.getBlockTickets().keySet()) {
            if (!(level.getBlockEntity(owner) instanceof ChunkLoaderBlockEntity loader)
                    || !loader.isActive()) {
                helper.removeAllTickets(owner);
            }
        }
    }

    /** Forces or releases one chunk owned by {@code owner}. */
    public static void setForced(ServerLevel level, BlockPos owner, ChunkPos chunk, boolean force) {
        CONTROLLER.forceChunk(level, owner, chunk.x(), chunk.z(), force, false);
    }
}

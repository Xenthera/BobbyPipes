package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import com.bobby.bobbypipes.logistics.PipeNetwork;

/**
 * Asks the server to drop one running craft job.
 *
 * <p>Craft jobs have no timeout: a job waiting on an ingredient that will never arrive
 * waits forever rather than being killed on a guess about how long is too long. This is
 * how the player clears one, from the Autocraft Monitor.
 *
 * @param jobId the id the monitor was shown for that job
 */
public record CancelCraftJobPayload(long jobId) implements CustomPacketPayload {

    public static final Type<CancelCraftJobPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "cancel_craft_job"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CancelCraftJobPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_LONG, CancelCraftJobPayload::jobId,
                    CancelCraftJobPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(CancelCraftJobPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            // The monitor now lists jobs from across live links, so the job behind this
            // button need not live on the clicking player's level. Job ids are unique across
            // managers, so the first network that owns it is the right one.
            for (PipeNetwork network : PipeNetwork.instances()) {
                if (network.craftJobs().cancel(network.level(), network, payload.jobId())) {
                    return;
                }
            }
        });
    }
}

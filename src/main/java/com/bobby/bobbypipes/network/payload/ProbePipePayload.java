package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.network.PipeProbe;
import com.bobby.bobbypipes.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client → server: ask for live status of the pipe under the crosshair (Pipe Goggles).
 */
public record ProbePipePayload(BlockPos pos) implements CustomPacketPayload {

    public static final Type<ProbePipePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "probe_pipe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProbePipePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, ProbePipePayload::pos,
                    ProbePipePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ProbePipePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)
                    || !(player.level() instanceof ServerLevel level)) {
                return;
            }
            if (!player.getItemBySlot(EquipmentSlot.HEAD).is(ModItems.PIPE_GOGGLES.get())) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 1.0)) {
                return;
            }
            PacketDistributor.sendToPlayer(
                    player, new PipeProbePayload(payload.pos(), PipeProbe.describe(level, payload.pos())));
        });
    }
}

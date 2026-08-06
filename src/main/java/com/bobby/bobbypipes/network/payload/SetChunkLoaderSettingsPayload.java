package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.ChunkLoaderBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetChunkLoaderSettingsPayload(
        BlockPos pos, int radius, boolean active, boolean debugOutline)
        implements CustomPacketPayload {

    public static final Type<SetChunkLoaderSettingsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_chunk_loader"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetChunkLoaderSettingsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetChunkLoaderSettingsPayload::pos,
                    ByteBufCodecs.VAR_INT, SetChunkLoaderSettingsPayload::radius,
                    ByteBufCodecs.BOOL, SetChunkLoaderSettingsPayload::active,
                    ByteBufCodecs.BOOL, SetChunkLoaderSettingsPayload::debugOutline,
                    SetChunkLoaderSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetChunkLoaderSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 4.0)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            if (!(level.getBlockEntity(payload.pos()) instanceof ChunkLoaderBlockEntity loader)) {
                return;
            }
            loader.applySettings(payload.radius(), payload.active(), payload.debugOutline());
        });
    }
}

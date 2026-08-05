package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.ProviderPipeBlockEntity;
import com.bobby.bobbypipes.pipes.ProviderSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetProviderSettingsPayload(BlockPos pos, ProviderSettings settings)
        implements CustomPacketPayload {

    public static final Type<SetProviderSettingsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_provider_settings"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetProviderSettingsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetProviderSettingsPayload::pos,
                    ProviderSettings.STREAM_CODEC, SetProviderSettingsPayload::settings,
                    SetProviderSettingsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetProviderSettingsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            if (!level.hasChunkAt(payload.pos())) {
                return;
            }
            if (!(level.getBlockEntity(payload.pos()) instanceof ProviderPipeBlockEntity pipe)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 8.0)) {
                return;
            }
            pipe.setSettings(payload.settings());
        });
    }
}

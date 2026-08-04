package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.BasicPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetDefaultRoutePayload(BlockPos pos, boolean defaultRoute)
        implements CustomPacketPayload {

    public static final Type<SetDefaultRoutePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_default_route"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetDefaultRoutePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetDefaultRoutePayload::pos,
                    ByteBufCodecs.BOOL, SetDefaultRoutePayload::defaultRoute,
                    SetDefaultRoutePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetDefaultRoutePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 4.0)) {
                return;
            }
            if (player.level().getBlockEntity(payload.pos()) instanceof BasicPipeBlockEntity pipe) {
                pipe.setDefaultRoute(payload.defaultRoute());
            }
        });
    }
}

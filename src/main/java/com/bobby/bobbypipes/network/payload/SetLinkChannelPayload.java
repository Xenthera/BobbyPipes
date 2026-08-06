package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.LinkPipeBlockEntity;
import com.bobby.bobbypipes.network.LinkClaimResult;
import com.bobby.bobbypipes.pipes.LinkChannelResult;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetLinkChannelPayload(BlockPos pos, int channel) implements CustomPacketPayload {

    public static final Type<SetLinkChannelPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_link_channel"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetLinkChannelPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetLinkChannelPayload::pos,
                    ByteBufCodecs.VAR_INT, SetLinkChannelPayload::channel,
                    SetLinkChannelPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetLinkChannelPayload payload, IPayloadContext context) {
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
            if (!(level.getBlockEntity(payload.pos()) instanceof LinkPipeBlockEntity link)) {
                return;
            }
            LinkClaimResult claim = link.setChannel(payload.channel());
            LinkChannelResult result = switch (claim) {
                case OK -> LinkChannelResult.SUCCESS;
                case CHANNEL_FULL -> LinkChannelResult.CHANNEL_FULL;
                case INVALID_CHANNEL -> LinkChannelResult.INVALID_CHANNEL;
            };
            PacketDistributor.sendToPlayer(player,
                    new LinkChannelResultPayload(
                            result, link.channel(), link.isPaired(), link.isLive()));
        });
    }
}

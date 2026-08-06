package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.ClientLinkGui;
import com.bobby.bobbypipes.pipes.LinkChannelResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record LinkChannelResultPayload(
        LinkChannelResult result, int channel, boolean paired, boolean live)
        implements CustomPacketPayload {

    public static final Type<LinkChannelResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "link_channel_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LinkChannelResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, p -> p.result().name(),
                    ByteBufCodecs.VAR_INT, LinkChannelResultPayload::channel,
                    ByteBufCodecs.BOOL, LinkChannelResultPayload::paired,
                    ByteBufCodecs.BOOL, LinkChannelResultPayload::live,
                    (resultName, channel, paired, live) -> new LinkChannelResultPayload(
                            LinkChannelResult.valueOf(resultName), channel, paired, live));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(LinkChannelResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientLinkGui.onChannelResult(
                payload.result(), payload.channel(), payload.paired(), payload.live()));
    }
}

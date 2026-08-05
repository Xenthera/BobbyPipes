package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * Server → client: status lines for the pipe currently under the goggles crosshair.
 */
public record PipeProbePayload(BlockPos pos, List<String> lines) implements CustomPacketPayload {

    public static final Type<PipeProbePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "pipe_probe"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PipeProbePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, PipeProbePayload::pos,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), PipeProbePayload::lines,
                    PipeProbePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

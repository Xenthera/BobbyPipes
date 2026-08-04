package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.ClientSatelliteGui;
import com.bobby.bobbypipes.pipes.SatelliteNamingResult;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SatelliteNameResultPayload(SatelliteNamingResult result, String name)
        implements CustomPacketPayload {

    public static final Type<SatelliteNameResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "satellite_name_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SatelliteNameResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.STRING_UTF8, p -> p.result().name(),
                    ByteBufCodecs.STRING_UTF8, SatelliteNameResultPayload::name,
                    (resultName, name) -> new SatelliteNameResultPayload(
                            SatelliteNamingResult.valueOf(resultName), name));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SatelliteNameResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientSatelliteGui.onNameResult(payload.result(), payload.name()));
    }
}

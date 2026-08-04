package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.client.ClientCraftingGui;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.List;

public record SatelliteListPayload(BlockPos crafterPos, List<String> names) implements CustomPacketPayload {

    public static final Type<SatelliteListPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "satellite_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SatelliteListPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SatelliteListPayload::crafterPos,
                    ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), SatelliteListPayload::names,
                    SatelliteListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SatelliteListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientCraftingGui.onSatelliteList(payload.crafterPos(), payload.names()));
    }
}

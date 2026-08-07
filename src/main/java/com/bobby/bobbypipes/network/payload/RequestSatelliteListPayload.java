package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.SatelliteLookup;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;

public record RequestSatelliteListPayload(BlockPos crafterPos) implements CustomPacketPayload {

    public static final Type<RequestSatelliteListPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "request_satellite_list"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestSatelliteListPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestSatelliteListPayload::crafterPos,
                    RequestSatelliteListPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestSatelliteListPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.crafterPos(), 4.0)) {
                return;
            }
            if (!(player.level() instanceof ServerLevel level)) {
                return;
            }
            PipeNetwork network = PipeNetwork.get(level);
            List<String> names = new ArrayList<>();
            for (SatelliteLookup.NamedSatellite sat : SatelliteLookup.listNamed(level, network, payload.crafterPos())) {
                names.add(sat.name());
            }
            PacketDistributor.sendToPlayer(player, new SatelliteListPayload(payload.crafterPos(), names));
        });
    }
}

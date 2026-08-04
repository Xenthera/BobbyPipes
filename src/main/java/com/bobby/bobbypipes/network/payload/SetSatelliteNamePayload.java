package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.SatellitePipeBlockEntity;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.SatelliteLookup;
import com.bobby.bobbypipes.pipes.SatelliteNamingResult;
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

public record SetSatelliteNamePayload(BlockPos pos, String name) implements CustomPacketPayload {

    public static final Type<SetSatelliteNamePayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_satellite_name"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetSatelliteNamePayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetSatelliteNamePayload::pos,
                    ByteBufCodecs.STRING_UTF8, SetSatelliteNamePayload::name,
                    SetSatelliteNamePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetSatelliteNamePayload payload, IPayloadContext context) {
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
            if (!(level.getBlockEntity(payload.pos()) instanceof SatellitePipeBlockEntity satellite)) {
                return;
            }
            String requested = payload.name() == null ? "" : payload.name().trim();
            SatelliteNamingResult result;
            if (requested.isBlank()) {
                result = SatelliteNamingResult.BLANK_NAME;
            } else if (SatelliteLookup.isDuplicateName(
                    level, PipeNetwork.get(level), payload.pos(), requested)) {
                result = SatelliteNamingResult.DUPLICATE_NAME;
            } else {
                satellite.setSatelliteName(requested);
                result = SatelliteNamingResult.SUCCESS;
            }
            PacketDistributor.sendToPlayer(
                    player, new SatelliteNameResultPayload(result, satellite.satelliteName()));
        });
    }
}

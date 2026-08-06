package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.EnergySupplierPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetEnergySupplierTargetPayload(BlockPos pos, int targetFe)
        implements CustomPacketPayload {

    public static final Type<SetEnergySupplierTargetPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_energy_supplier_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetEnergySupplierTargetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetEnergySupplierTargetPayload::pos,
                    ByteBufCodecs.VAR_INT, SetEnergySupplierTargetPayload::targetFe,
                    SetEnergySupplierTargetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetEnergySupplierTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 4.0)) {
                return;
            }
            if (player.level().getBlockEntity(payload.pos()) instanceof EnergySupplierPipeBlockEntity pipe) {
                pipe.setTargetFe(payload.targetFe());
            }
        });
    }
}

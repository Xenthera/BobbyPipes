package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.StockTargetPipeBlockEntity;
import com.bobby.bobbypipes.pipes.SupplierRequests;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record SetSupplierRequestsPayload(BlockPos pos, SupplierRequests requests)
        implements CustomPacketPayload {

    public static final Type<SetSupplierRequestsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_supplier_requests"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetSupplierRequestsPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetSupplierRequestsPayload::pos,
                    SupplierRequests.STREAM_CODEC, SetSupplierRequestsPayload::requests,
                    SetSupplierRequestsPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetSupplierRequestsPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            Level level = player.level();
            if (!level.hasChunkAt(payload.pos())) {
                return;
            }
            if (!(level.getBlockEntity(payload.pos()) instanceof StockTargetPipeBlockEntity pipe)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 8.0)) {
                return;
            }
            pipe.setRequests(payload.requests());
        });
    }
}

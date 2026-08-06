package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.block.entity.FluidSupplierPipeBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

public record SetFluidSupplierTargetPayload(BlockPos pos, FluidResource fluid, int amountMb)
        implements CustomPacketPayload {

    public static final Type<SetFluidSupplierTargetPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "set_fluid_supplier_target"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SetFluidSupplierTargetPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, SetFluidSupplierTargetPayload::pos,
                    FluidResource.STREAM_CODEC, SetFluidSupplierTargetPayload::fluid,
                    ByteBufCodecs.VAR_INT, SetFluidSupplierTargetPayload::amountMb,
                    SetFluidSupplierTargetPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(SetFluidSupplierTargetPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!player.isWithinBlockInteractionRange(payload.pos(), 4.0)) {
                return;
            }
            if (player.level().getBlockEntity(payload.pos()) instanceof FluidSupplierPipeBlockEntity pipe) {
                pipe.setTarget(payload.fluid(), payload.amountMb());
            }
        });
    }
}

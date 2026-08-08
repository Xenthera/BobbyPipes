package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.menu.FluidRequestMenu;
import com.bobby.bobbypipes.menu.FluidRequestMenus;
import com.bobby.bobbypipes.logistics.FluidRequestService;
import com.bobby.bobbypipes.logistics.PipeNetwork;
import com.bobby.bobbypipes.logistics.RequestChat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

/**
 * Client -> server: submit a request from the fluid request pipe screen.
 *
 * @param allowPartial when false, send nothing unless the whole amount can be supplied
 */
public record RequestFluidPayload(BlockPos pos, FluidResource fluid, int amountMb,
                                  boolean allowPartial)
        implements CustomPacketPayload {

    public static final Type<RequestFluidPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "request_fluid"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestFluidPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestFluidPayload::pos,
                    FluidResource.STREAM_CODEC, RequestFluidPayload::fluid,
                    ByteBufCodecs.VAR_INT, RequestFluidPayload::amountMb,
                    ByteBufCodecs.BOOL, RequestFluidPayload::allowPartial,
                    RequestFluidPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestFluidPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof FluidRequestMenu menu)
                    || !menu.pos().equals(payload.pos())) {
                return;
            }
            if (!menu.stillValid(player) || payload.fluid().isEmpty() || payload.amountMb() <= 0) {
                return;
            }

            PipeNetwork network = PipeNetwork.get(player.level());
            network.rebuildNow(payload.pos());
            int shipped = FluidRequestService.request(player.level(), network, payload.pos(),
                    payload.fluid(), payload.amountMb(), true, payload.allowPartial());
            // Only meaningful when nothing shipped; RequestChat ignores it otherwise.
            String failKey = payload.allowPartial()
                    ? "" : "chat.bobbypipes.request.fail.incomplete.amount";
            RequestChat.fluid(player, payload.fluid(), payload.amountMb(), shipped, failKey);
            FluidRequestMenus.syncStock(player, payload.pos());
        });
    }
}

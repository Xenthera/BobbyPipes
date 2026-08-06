package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.menu.RequestMenu;
import com.bobby.bobbypipes.menu.RequestMenus;
import com.bobby.bobbypipes.network.RequestChat;
import com.bobby.bobbypipes.network.RequestService;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.transfer.item.ItemResource;

/**
 * Client -> server: submit a request from the request pipe screen.
 */
public record RequestItemPayload(BlockPos pos, ItemResource item, int quantity)
        implements CustomPacketPayload {

    public static final Type<RequestItemPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "request_item"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestItemPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestItemPayload::pos,
                    ItemResource.STREAM_CODEC, RequestItemPayload::item,
                    ByteBufCodecs.VAR_INT, RequestItemPayload::quantity,
                    RequestItemPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestItemPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof RequestMenu menu)
                    || !menu.pos().equals(payload.pos())) {
                return;
            }
            if (!menu.stillValid(player) || payload.item().isEmpty() || payload.quantity() <= 0) {
                return;
            }

            RequestService.Outcome outcome = RequestService.requestWhatYouCan(
                    player.level(), payload.pos(), payload.item(), payload.quantity());
            int shipped = outcome.commitment() == null ? 0 : outcome.commitment().shipped();
            RequestChat.item(player, payload.item(), payload.quantity(), shipped);
            context.reply(RequestResultPayload.from(outcome));
            if (outcome.hasPipe()) {
                RequestMenus.syncStock(player, payload.pos());
            }
        });
    }
}

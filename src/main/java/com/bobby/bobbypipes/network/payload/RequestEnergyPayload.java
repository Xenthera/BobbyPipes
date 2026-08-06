package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.menu.EnergyRequestMenu;
import com.bobby.bobbypipes.menu.EnergyRequestMenus;
import com.bobby.bobbypipes.network.EnergyRequestService;
import com.bobby.bobbypipes.network.PipeNetwork;
import com.bobby.bobbypipes.network.RequestChat;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client -> server: submit a request from the energy request pipe screen.
 */
public record RequestEnergyPayload(BlockPos pos, int amountFe) implements CustomPacketPayload {

    public static final Type<RequestEnergyPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "request_energy"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestEnergyPayload> STREAM_CODEC =
            StreamCodec.composite(
                    BlockPos.STREAM_CODEC, RequestEnergyPayload::pos,
                    ByteBufCodecs.VAR_INT, RequestEnergyPayload::amountFe,
                    RequestEnergyPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(RequestEnergyPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) {
                return;
            }
            if (!(player.containerMenu instanceof EnergyRequestMenu menu)
                    || !menu.pos().equals(payload.pos())) {
                return;
            }
            if (!menu.stillValid(player) || payload.amountFe() <= 0) {
                return;
            }

            PipeNetwork network = PipeNetwork.get(player.level());
            network.rebuildNow(payload.pos());
            int shipped = EnergyRequestService.request(
                    player.level(), network, payload.pos(), payload.amountFe());
            RequestChat.energy(player, payload.amountFe(), shipped);
            EnergyRequestMenus.syncStock(player, payload.pos());
        });
    }
}

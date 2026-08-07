package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.logistics.RequestService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client: status line after a request attempt from the GUI.
 *
 * @param reasonKey    translation key explaining a failure, or empty on success / partial
 * @param reasonDetail optional detail (e.g. source → dest), or empty
 */
public record RequestResultPayload(int shipped, int requested, int missing, boolean hasPipe,
                                   java.util.List<Shortfall> shortfalls,
                                   String reasonKey, String reasonDetail)
        implements CustomPacketPayload {

    public static final Type<RequestResultPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "request_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RequestResultPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, RequestResultPayload::shipped,
                    ByteBufCodecs.VAR_INT, RequestResultPayload::requested,
                    ByteBufCodecs.VAR_INT, RequestResultPayload::missing,
                    ByteBufCodecs.BOOL, RequestResultPayload::hasPipe,
                    Shortfall.STREAM_CODEC.apply(ByteBufCodecs.list()), RequestResultPayload::shortfalls,
                    ByteBufCodecs.STRING_UTF8, RequestResultPayload::reasonKey,
                    ByteBufCodecs.STRING_UTF8, RequestResultPayload::reasonDetail,
                    RequestResultPayload::new);

    /**
     * One item the request could not fully source.
     *
     * <p>A bare total says a request failed; this says what to go and get.
     *
     * @param amount how many were still missing, which may exceed a single stack
     */
    public record Shortfall(net.minecraft.world.item.ItemStack item, int amount) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Shortfall> STREAM_CODEC =
                StreamCodec.composite(
                        net.minecraft.world.item.ItemStack.STREAM_CODEC, Shortfall::item,
                        ByteBufCodecs.VAR_INT, Shortfall::amount,
                        Shortfall::new);
    }

    public static RequestResultPayload from(RequestService.Outcome outcome) {
        if (!outcome.hasPipe()) {
            return new RequestResultPayload(0, 0, 0, false, java.util.List.of(),
                    "chat.bobbypipes.request.fail.no_pipe", "");
        }
        int shipped = outcome.commitment() == null ? 0 : outcome.commitment().shipped();
        int requested = outcome.commitment() == null
                ? outcome.plan().withdrawals().stream().mapToInt(w -> w.amount()).sum()
                : outcome.commitment().requested();
        int missing = outcome.plan().missing().stream().mapToInt(d -> d.amount()).sum();
        if (outcome.commitment() != null) {
            missing = Math.max(missing, outcome.commitment().shortfall());
        }
        java.util.List<Shortfall> shortfalls = outcome.plan().missing().stream()
                .filter(demand -> demand.amount() > 0)
                .map(demand -> new Shortfall(demand.item().toStack(1), demand.amount()))
                .toList();

        String reasonKey = "";
        String reasonDetail = "";
        if (shipped <= 0) {
            if (outcome.commitment() != null && outcome.commitment().hasFailReason()) {
                reasonKey = outcome.commitment().failKey();
                reasonDetail = outcome.commitment().failDetail();
            } else if (!shortfalls.isEmpty()) {
                reasonKey = "chat.bobbypipes.request.fail.missing";
            } else if (outcome.plan().isEmpty()) {
                reasonKey = "chat.bobbypipes.request.fail.no_stock";
            } else {
                reasonKey = "chat.bobbypipes.request.fail";
            }
        }
        return new RequestResultPayload(
                shipped, requested, missing, true, shortfalls, reasonKey, reasonDetail);
    }

    public boolean hasFailReason() {
        return reasonKey != null && !reasonKey.isEmpty();
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

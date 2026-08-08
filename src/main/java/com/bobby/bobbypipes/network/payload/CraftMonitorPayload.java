package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Live autocraft queue snapshot for an open Autocraft Monitor.
 *
 * <p>Ticks remaining are relative to send time so the client can show bars without
 * knowing server {@code gameTime}.
 */
public record CraftMonitorPayload(boolean linked, List<Card> cards, List<Order> orders)
        implements CustomPacketPayload {

    /**
     * One outstanding delivery on the network: who owes what to whom.
     *
     * <p>The queue view exists because every routing bug in this system has looked the same
     * from the outside - a crafter waiting - while the cause was in who had been promised
     * what. Logistics Pipes exposes its order manager for the same reason.
     *
     * @param fromCraft true when a crafter owes this, false when it is a provider withdrawal
     */
    public record Order(ItemStack item, BlockPos from, BlockPos to, int remaining,
                        boolean fromCraft, ItemStack machine) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Order> STREAM_CODEC =
                StreamCodec.composite(
                        ItemStack.OPTIONAL_STREAM_CODEC, Order::item,
                        BlockPos.STREAM_CODEC, Order::from,
                        BlockPos.STREAM_CODEC, Order::to,
                        ByteBufCodecs.VAR_INT, Order::remaining,
                        ByteBufCodecs.BOOL, Order::fromCraft,
                        ItemStack.OPTIONAL_STREAM_CODEC, Order::machine,
                        Order::new);
    }

    public static final Type<CraftMonitorPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "craft_monitor"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftMonitorPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.BOOL, CraftMonitorPayload::linked,
                    Card.STREAM_CODEC.apply(ByteBufCodecs.list()), CraftMonitorPayload::cards,
                    Order.STREAM_CODEC.apply(ByteBufCodecs.list()), CraftMonitorPayload::orders,
                    CraftMonitorPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public enum Status {
        GATHER,
        WAIT_OUTPUT,
        QUEUED,
        FAILED;

        public static final StreamCodec<ByteBuf, Status> STREAM_CODEC =
                ByteBufCodecs.idMapper(i -> values()[i], Status::ordinal);
    }

    public record Want(ItemStack stack, boolean fromCraft) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Want> STREAM_CODEC =
                StreamCodec.composite(
                        ItemStack.OPTIONAL_STREAM_CODEC, Want::stack,
                        ByteBufCodecs.BOOL, Want::fromCraft,
                        Want::new);
    }

    /**
     * One row in the monitor.
     *
     * @param jobId identifies the running job so the monitor can cancel it. Zero on a
     *              failure card, which has nothing left to cancel.
     */
    public record Card(
            ItemStack output,
            BlockPos crafter,
            Status status,
            int runsRemaining,
            long jobId,
            String detail,
            List<Want> wants) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Card> STREAM_CODEC =
                StreamCodec.composite(
                        ItemStack.OPTIONAL_STREAM_CODEC, Card::output,
                        BlockPos.STREAM_CODEC, Card::crafter,
                        Status.STREAM_CODEC, Card::status,
                        ByteBufCodecs.VAR_INT, Card::runsRemaining,
                        ByteBufCodecs.VAR_LONG, Card::jobId,
                        ByteBufCodecs.STRING_UTF8, Card::detail,
                        Want.STREAM_CODEC.apply(ByteBufCodecs.list()), Card::wants,
                        Card::new);
    }
}

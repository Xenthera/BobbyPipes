package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Optional;

/**
 * Full snapshot of parcels in flight on one level.
 *
 * <p>{@code enterFrom} is set only while a parcel is on its very first hop, and names the
 * side of the source pipe the container it came from sits on. {@code exitTo} is set only
 * when the current hop ends at the destination, and names the side the target container
 * sits on. Together they let the client draw an item running out along a pipe arm into a
 * chest instead of appearing and vanishing at pipe centres.
 *
 * <p>{@code gameTime} is the server tick this snapshot was taken. The client uses
 * {@code ticksIntoHop} only to seed a new hop clock; ongoing hops advance on client
 * game time so motion stays smooth across sync packets.
 */
public record ParcelSyncPayload(int ticksPerHop, long gameTime, List<Entry> parcels)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ParcelSyncPayload> TYPE =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "parcel_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ParcelSyncPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, ParcelSyncPayload::ticksPerHop,
                    ByteBufCodecs.VAR_LONG, ParcelSyncPayload::gameTime,
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), ParcelSyncPayload::parcels,
                    ParcelSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * One parcel's render-relevant state.
     *
     * @param next        empty when the parcel is stuck or delivering in place
     * @param ticksForHop how long this specific hop actually takes on the server  -
     *                    authoritative, sent directly rather than left for the client to
     *                    guess from {@code enterFrom}/{@code exitTo}, so there is nothing
     *                    for the render clock to independently compute and fall out of
     *                    step with what the server is really doing
     * @param routed      true for logistics parcels (draw the pipe cage); false for
     *                    drifting items in plain pipe
     * @param tier        parcel density as {@link com.bobby.bobbypipes.transit.ParcelTier#wireId()},
     *                    or 0 for items and drift, which have no tier. Energy and fluid
     *                    parcels all draw the same one-item model, so without this a packet
     *                    carrying a million FE and one carrying a thousand are pixel
     *                    identical in the pipe; the renderer sizes them by this instead.
     */
    public record Entry(
            long id,
            BlockPos at,
            Optional<BlockPos> next,
            int ticksIntoHop,
            int ticksForHop,
            ItemStack stack,
            Optional<Direction> enterFrom,
            Optional<Direction> exitTo,
            boolean routed,
            int tier) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_LONG, Entry::id,
                        BlockPos.STREAM_CODEC, Entry::at,
                        ByteBufCodecs.optional(BlockPos.STREAM_CODEC), Entry::next,
                        ByteBufCodecs.VAR_INT, Entry::ticksIntoHop,
                        ByteBufCodecs.VAR_INT, Entry::ticksForHop,
                        ItemStack.STREAM_CODEC, Entry::stack,
                        ByteBufCodecs.optional(Direction.STREAM_CODEC), Entry::enterFrom,
                        ByteBufCodecs.optional(Direction.STREAM_CODEC), Entry::exitTo,
                        ByteBufCodecs.BOOL, Entry::routed,
                        // VAR_INT rather than BYTE: the value is only ever 0-3, so it costs
                        // the same one byte on the wire, and the field stays a plain int.
                        ByteBufCodecs.VAR_INT, Entry::tier,
                        Entry::new);

        /** Untiered: items and drifting items, which ship by the stack. */
        public static final int NO_TIER = 0;
    }
}

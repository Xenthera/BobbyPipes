package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.List;

/**
 * Full replace of network stock + craftables for the open request screen.
 */
public record NetworkStockPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final Type<NetworkStockPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "network_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkStockPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), NetworkStockPayload::entries,
                    NetworkStockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(ItemResource item, int amount, boolean craftable) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        ItemResource.STREAM_CODEC, Entry::item,
                        ByteBufCodecs.VAR_INT, Entry::amount,
                        ByteBufCodecs.BOOL, Entry::craftable,
                        Entry::new);
    }
}

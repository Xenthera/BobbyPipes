package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.transfer.fluid.FluidResource;

import java.util.List;

/**
 * Full replace of network fluid stock for the open fluid request/supplier screen. Mirrors
 * {@link NetworkStockPayload}, no craftable flag, fluid is never crafted.
 */
public record FluidStockPayload(List<Entry> entries) implements CustomPacketPayload {

    public static final Type<FluidStockPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "fluid_stock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FluidStockPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), FluidStockPayload::entries,
                    FluidStockPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record Entry(FluidResource fluid, int amountMb) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        FluidResource.STREAM_CODEC, Entry::fluid,
                        ByteBufCodecs.VAR_INT, Entry::amountMb,
                        Entry::new);
    }
}

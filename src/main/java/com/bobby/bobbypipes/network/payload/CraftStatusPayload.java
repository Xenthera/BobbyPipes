package com.bobby.bobbypipes.network.payload;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Human readable state of every active craft job, for the debug overlay.
 *
 * <p>A stalled chain looks like nothing happening from the outside. This carries what each
 * crafting pipe is actually waiting for, so the broken link can be read off directly
 * rather than inferred.
 */
public record CraftStatusPayload(List<Entry> jobs) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<CraftStatusPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "craft_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftStatusPayload> STREAM_CODEC =
            StreamCodec.composite(
                    Entry.STREAM_CODEC.apply(ByteBufCodecs.list()), CraftStatusPayload::jobs,
                    CraftStatusPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * One crafting pipe's outstanding ingredients.
     *
     * @param pipe  the crafting pipe this describes
     * @param wants what it is still waiting for, drawn above the pipe
     */
    public record Entry(BlockPos pipe, List<Want> wants) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Entry> STREAM_CODEC =
                StreamCodec.composite(
                        BlockPos.STREAM_CODEC, Entry::pipe,
                        Want.STREAM_CODEC.apply(ByteBufCodecs.list()), Entry::wants,
                        Entry::new);
    }

    /**
     * One ingredient a pipe is short of.
     *
     * @param stack    the item, with its count set to how many are still missing
     * @param blocked  true when an upstream crafter owes this rather than a provider
     */
    public record Want(ItemStack stack, boolean blocked) {

        public static final StreamCodec<RegistryFriendlyByteBuf, Want> STREAM_CODEC =
                StreamCodec.composite(
                        ItemStack.OPTIONAL_STREAM_CODEC, Want::stack,
                        ByteBufCodecs.BOOL, Want::blocked,
                        Want::new);
    }
}

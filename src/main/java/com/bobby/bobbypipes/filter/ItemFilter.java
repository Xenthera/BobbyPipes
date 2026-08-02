package com.bobby.bobbypipes.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * An item filter, stored as a data component on the module item that owns it.
 *
 * <p>Living in a component rather than in block entity NBT is the point. The filter
 * travels with the module item, so pulling a configured Item Sink out of a chassis and
 * putting it in another keeps its configuration, it survives being carried in an
 * inventory, it shows in a tooltip, and it syncs to the client without a bespoke packet.
 */
public record ItemFilter(FilterList<ItemFilterEntry> rules) {

    public static final ItemFilter EMPTY = new ItemFilter(FilterList.allowNothing());

    public static final Codec<ItemFilter> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemFilterEntry.CODEC.listOf().fieldOf("entries").forGetter(filter -> filter.rules().entries()),
            Codec.STRING.fieldOf("mode").forGetter(filter -> filter.rules().mode().id())
    ).apply(instance, (entries, mode) -> new ItemFilter(new FilterList<>(entries, MatchMode.byId(mode)))));

    public static final StreamCodec<RegistryFriendlyByteBuf, ItemFilter> STREAM_CODEC = StreamCodec.of(
            (buffer, filter) -> {
                ItemFilterEntry.STREAM_CODEC.apply(ByteBufCodecs.list())
                        .encode(buffer, filter.rules().entries());
                buffer.writeUtf(filter.rules().mode().id());
            },
            buffer -> {
                List<ItemFilterEntry> entries =
                        ItemFilterEntry.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buffer);
                return new ItemFilter(new FilterList<>(entries, MatchMode.byId(buffer.readUtf())));
            });

    /** Whether this filter lets {@code stack} through. */
    public boolean accepts(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }
        return rules.accepts(entry -> entry.test(stack));
    }

    public MatchMode mode() {
        return rules.mode();
    }

    public boolean isEmpty() {
        return rules.isEmpty();
    }

    public ItemFilter withMode(MatchMode mode) {
        return new ItemFilter(rules.withMode(mode));
    }

    public ItemFilter with(ItemFilterEntry entry) {
        return new ItemFilter(rules.with(entry));
    }

    public ItemFilter without(ItemFilterEntry entry) {
        return new ItemFilter(rules.without(entry));
    }

    /** Lines describing the filter, for a tooltip or the filter screen. */
    public List<String> describe() {
        return rules.entries().stream().map(ItemFilterEntry::describe).toList();
    }
}

package com.bobby.bobbypipes.filter;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * One line of an item filter.
 *
 * <p>Two kinds, and the tag form is the reason this is not just a list of items: a filter
 * that says "any plank" keeps working when a new mod adds a wood type, which the 1.12-era
 * ore dictionary approach only approximated.
 */
public sealed interface ItemFilterEntry permits ItemFilterEntry.ByItem, ItemFilterEntry.ByTag {

    Codec<ItemFilterEntry> CODEC = Codec.either(ByItem.CODEC, ByTag.CODEC)
            .xmap(
                    either -> either.map(byItem -> (ItemFilterEntry) byItem, byTag -> (ItemFilterEntry) byTag),
                    entry -> entry instanceof ByItem byItem
                            ? com.mojang.datafixers.util.Either.left(byItem)
                            : com.mojang.datafixers.util.Either.right((ByTag) entry));

    StreamCodec<RegistryFriendlyByteBuf, ItemFilterEntry> STREAM_CODEC =
            StreamCodec.of(ItemFilterEntry::write, ItemFilterEntry::read);

    /** Whether {@code stack} is described by this entry. */
    boolean test(ItemStack stack);

    /** Human readable form, used by tooltips and the filter screen. */
    String describe();

    /** Matches one specific item. */
    record ByItem(Item item) implements ItemFilterEntry {

        public static final Codec<ByItem> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                BuiltInRegistries.ITEM.byNameCodec().fieldOf("item").forGetter(ByItem::item)
        ).apply(instance, ByItem::new));

        @Override
        public boolean test(ItemStack stack) {
            return stack.is(item);
        }

        @Override
        public String describe() {
            return BuiltInRegistries.ITEM.getKey(item).toString();
        }
    }

    /** Matches anything carrying the tag, so the filter tracks the tag as content changes. */
    record ByTag(TagKey<Item> tag) implements ItemFilterEntry {

        public static final Codec<ByTag> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                TagKey.codec(Registries.ITEM).fieldOf("tag").forGetter(ByTag::tag)
        ).apply(instance, ByTag::new));

        public static ByTag of(String namespacedTag) {
            return new ByTag(ItemTags.create(Identifier.parse(namespacedTag)));
        }

        @Override
        public boolean test(ItemStack stack) {
            return stack.is(tag);
        }

        @Override
        public String describe() {
            return "#" + tag.location();
        }
    }

    private static void write(RegistryFriendlyByteBuf buffer, ItemFilterEntry entry) {
        if (entry instanceof ByItem byItem) {
            buffer.writeBoolean(true);
            ByteBufCodecs.registry(Registries.ITEM).encode(buffer, byItem.item());
        } else {
            buffer.writeBoolean(false);
            buffer.writeIdentifier(((ByTag) entry).tag().location());
        }
    }

    private static ItemFilterEntry read(RegistryFriendlyByteBuf buffer) {
        if (buffer.readBoolean()) {
            return new ByItem(ByteBufCodecs.registry(Registries.ITEM).decode(buffer));
        }
        return new ByTag(ItemTags.create(buffer.readIdentifier()));
    }
}

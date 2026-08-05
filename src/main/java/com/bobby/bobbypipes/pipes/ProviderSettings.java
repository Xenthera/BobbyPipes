package com.bobby.bobbypipes.pipes;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.transfer.item.ItemResource;

import java.util.ArrayList;
import java.util.List;

/**
 * Provider pipe config: a 9-slot item filter, include/exclude mode, and leave-stack policy.
 *
 * <p>An empty filter always provides everything (LP semantics), whether include or exclude
 * is selected. A non-empty include list provides only listed items; a non-empty exclude
 * list provides everything except listed items.
 */
public record ProviderSettings(
        List<ItemStack> filter,
        boolean include,
        ProviderLeaveMode leaveMode) {

    public static final int SLOT_COUNT = 9;

    public static final ProviderSettings EMPTY =
            new ProviderSettings(emptySlots(), true, ProviderLeaveMode.NORMAL);

    public static final Codec<ProviderSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("filter").forGetter(ProviderSettings::filter),
            Codec.BOOL.fieldOf("include").forGetter(ProviderSettings::include),
            ProviderLeaveMode.CODEC.optionalFieldOf("leave", ProviderLeaveMode.NORMAL)
                    .forGetter(ProviderSettings::leaveMode)
    ).apply(instance, ProviderSettings::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, ProviderSettings> STREAM_CODEC =
            StreamCodec.composite(
                    ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()),
                    ProviderSettings::filter,
                    ByteBufCodecs.BOOL,
                    ProviderSettings::include,
                    ProviderLeaveMode.STREAM_CODEC,
                    ProviderSettings::leaveMode,
                    ProviderSettings::new);

    public ProviderSettings {
        filter = normalize(filter);
        leaveMode = leaveMode == null ? ProviderLeaveMode.NORMAL : leaveMode;
    }

    public ItemStack slot(int index) {
        return filter.get(index).copy();
    }

    public ProviderSettings withSlot(int index, ItemStack stack) {
        List<ItemStack> next = new ArrayList<>(filter);
        next.set(index, sanitize(stack));
        return new ProviderSettings(next, include, leaveMode);
    }

    public ProviderSettings withInclude(boolean include) {
        return new ProviderSettings(filter, include, leaveMode);
    }

    public ProviderSettings withLeaveMode(ProviderLeaveMode leaveMode) {
        return new ProviderSettings(filter, include, leaveMode);
    }

    public boolean filterEmpty() {
        for (ItemStack stack : filter) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether this provider may offer {@code item}. Empty filter = everything.
     */
    public boolean accepts(ItemResource item) {
        if (item == null || item.isEmpty()) {
            return false;
        }
        if (filterEmpty()) {
            return true;
        }
        boolean listed = false;
        for (ItemStack ghost : filter) {
            if (!ghost.isEmpty() && item.equals(ItemResource.of(ghost))) {
                listed = true;
                break;
            }
        }
        return include == listed;
    }

    private static List<ItemStack> emptySlots() {
        List<ItemStack> list = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            list.add(ItemStack.EMPTY);
        }
        return List.copyOf(list);
    }

    private static List<ItemStack> normalize(List<ItemStack> in) {
        List<ItemStack> out = new ArrayList<>(SLOT_COUNT);
        for (int i = 0; i < SLOT_COUNT; i++) {
            out.add(i < in.size() ? sanitize(in.get(i)) : ItemStack.EMPTY);
        }
        return List.copyOf(out);
    }

    private static ItemStack sanitize(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stack.copyWithCount(1);
    }
}

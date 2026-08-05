package com.bobby.bobbypipes.pipes;

import com.mojang.serialization.Codec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Ghost slots for a Supplier pipe: each non-empty stack is an item to keep stocked, and
 * its count contributes to the target amount in the attached inventory.
 *
 * <p>Identical items across slots sum (two stacks of 64 logs → keep 128).
 */
public record SupplierRequests(List<ItemStack> slots) {

    public static final int SLOT_COUNT = 9;
    public static final int MAX_TARGET = 999;

    public static final SupplierRequests EMPTY = new SupplierRequests(emptySlots());

    public static final Codec<SupplierRequests> CODEC =
            ItemStack.OPTIONAL_CODEC.listOf().xmap(SupplierRequests::new, SupplierRequests::slots);

    public static final StreamCodec<RegistryFriendlyByteBuf, SupplierRequests> STREAM_CODEC =
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list())
                    .map(SupplierRequests::new, SupplierRequests::slots);

    public SupplierRequests {
        slots = normalize(slots);
    }

    public ItemStack slot(int index) {
        return slots.get(index).copy();
    }

    public SupplierRequests withSlot(int index, ItemStack stack) {
        List<ItemStack> next = new ArrayList<>(slots);
        next.set(index, sanitize(stack));
        return new SupplierRequests(next);
    }

    public boolean isEmpty() {
        for (ItemStack stack : slots) {
            if (!stack.isEmpty()) {
                return false;
            }
        }
        return true;
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
        ItemStack copy = stack.copy();
        copy.setCount(Math.max(1, Math.min(MAX_TARGET, copy.getCount())));
        return copy;
    }
}

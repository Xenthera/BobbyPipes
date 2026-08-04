package com.bobby.bobbypipes.craft;

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
 * A recipe hosted by a crafting pipe (or authored on a pattern table).
 *
 * <p>{@link Kind#SHAPED} uses a 3x3 grid (nine input stacks, one result).
 * {@link Kind#PROCESSING} is free-form IO for non-grid machines.
 *
 * <p>{@code satellite} is the LP-style single satellite name. Empty means all ingredients
 * go to the crafting pipe. When set, shaped slots 6-8 (rightmost column) deliver to that
 * satellite instead.
 */
public record CraftPattern(
        Kind kind,
        List<ItemStack> inputs,
        List<ItemStack> outputs,
        String satellite) {

    /** Rightmost column of a 3x3 grid  -  LP satellite slots. */
    public static final int SATELLITE_SLOT_START = 6;
    public static final int SATELLITE_SLOT_END = 9;

    public enum Kind {
        SHAPED,
        PROCESSING;

        public static final Codec<Kind> CODEC = Codec.STRING.xmap(
                id -> Kind.valueOf(id.toUpperCase()),
                kind -> kind.name().toLowerCase());
    }

    public static final CraftPattern EMPTY = new CraftPattern(
            Kind.SHAPED, emptySlots(9), List.of(ItemStack.EMPTY), "");

    public static final Codec<CraftPattern> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Kind.CODEC.fieldOf("kind").forGetter(CraftPattern::kind),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("inputs").forGetter(CraftPattern::inputs),
            ItemStack.OPTIONAL_CODEC.listOf().fieldOf("outputs").forGetter(CraftPattern::outputs),
            Codec.STRING.optionalFieldOf("satellite", "").forGetter(CraftPattern::satellite),
            Codec.STRING.listOf().optionalFieldOf("satellites", List.of()).forGetter(p -> List.of())
    ).apply(instance, (kind, inputs, outputs, satellite, legacySatellites) ->
            new CraftPattern(kind, inputs, outputs, migrateSatellite(satellite, legacySatellites))));

    public static final StreamCodec<RegistryFriendlyByteBuf, CraftPattern> STREAM_CODEC =
            StreamCodec.of(
                    (buf, pattern) -> {
                        buf.writeUtf(pattern.kind().name());
                        ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list())
                                .encode(buf, pattern.inputs());
                        ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list())
                                .encode(buf, pattern.outputs());
                        buf.writeUtf(pattern.satellite());
                    },
                    buf -> new CraftPattern(
                            Kind.valueOf(buf.readUtf()),
                            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
                            buf.readUtf()));

    public CraftPattern {
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        satellite = satellite == null ? "" : satellite.trim();
    }

    public static CraftPattern shaped(List<ItemStack> grid, ItemStack result) {
        return shaped(grid, result, "");
    }

    public static CraftPattern shaped(List<ItemStack> grid, ItemStack result, String satellite) {
        List<ItemStack> inputs = new ArrayList<>(emptySlots(9));
        for (int i = 0; i < Math.min(9, grid.size()); i++) {
            inputs.set(i, copyGhost(grid.get(i)));
        }
        return new CraftPattern(Kind.SHAPED, inputs, List.of(copyGhost(result)), satellite);
    }

    public static CraftPattern processing(List<ItemStack> in, List<ItemStack> out) {
        return processing(in, out, "");
    }

    public static CraftPattern processing(List<ItemStack> in, List<ItemStack> out, String satellite) {
        List<ItemStack> inputs = in.stream().map(CraftPattern::copyGhost).toList();
        List<ItemStack> outputs = out.stream().map(CraftPattern::copyGhost).toList();
        return new CraftPattern(Kind.PROCESSING, inputs, outputs, satellite);
    }

    /** True when there are no inputs and no outputs (nothing programmed). */
    public boolean isEmpty() {
        return inputs.stream().allMatch(ItemStack::isEmpty)
                && outputs.stream().allMatch(ItemStack::isEmpty);
    }

    public boolean hasInputs() {
        return inputs.stream().anyMatch(stack -> !stack.isEmpty());
    }

    public boolean hasSatellite() {
        return !satellite.isBlank();
    }

    public ItemStack primaryOutput() {
        for (ItemStack stack : outputs) {
            if (!stack.isEmpty()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public CraftPattern withSatellite(String name) {
        String next = name == null ? "" : name.trim();
        if (next.equals(satellite)) {
            return this;
        }
        return new CraftPattern(kind, inputs, outputs, next);
    }

    public CraftPattern withInput(int slot, ItemStack stack) {
        if (slot < 0 || slot >= inputs.size()) {
            return this;
        }
        List<ItemStack> next = new ArrayList<>(inputs);
        next.set(slot, copyGhost(stack));
        return new CraftPattern(kind, next, outputs, satellite);
    }

    public CraftPattern withOutput(int slot, ItemStack stack) {
        if (slot < 0 || slot >= outputs.size()) {
            return this;
        }
        List<ItemStack> next = new ArrayList<>(outputs);
        next.set(slot, copyGhost(stack));
        return new CraftPattern(kind, inputs, next, satellite);
    }

    public CraftPattern withKind(Kind newKind) {
        if (newKind == kind) {
            return this;
        }
        if (newKind == Kind.SHAPED) {
            List<ItemStack> grid = new ArrayList<>(emptySlots(9));
            for (int i = 0; i < Math.min(9, inputs.size()); i++) {
                grid.set(i, copyGhost(inputs.get(i)));
            }
            return new CraftPattern(Kind.SHAPED, grid, List.of(copyGhost(primaryOutput())), satellite);
        }
        List<ItemStack> in = inputs.stream().filter(s -> !s.isEmpty()).map(CraftPattern::copyGhost).toList();
        List<ItemStack> out = outputs.stream().filter(s -> !s.isEmpty()).map(CraftPattern::copyGhost).toList();
        if (in.isEmpty()) {
            in = List.of(ItemStack.EMPTY);
        }
        if (out.isEmpty()) {
            out = List.of(ItemStack.EMPTY);
        }
        return new CraftPattern(Kind.PROCESSING, in, out, satellite);
    }

    /**
     * Ingredient demands for one craft run. For shaped patterns with a satellite set,
     * slots 6-8 route to that satellite; everything else goes to the crafter.
     */
    public List<CountedIngredient> ingredients() {
        List<CountedIngredient> list = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            ItemStack stack = inputs.get(i);
            if (stack.isEmpty()) {
                continue;
            }
            ItemResource resource = ItemResource.of(stack);
            String dest = routesToSatellite(i) ? satellite : "";
            list.add(new CountedIngredient(resource, Math.max(1, stack.getCount()), dest, i));
        }
        return list;
    }

    public List<CountedIngredient> results() {
        List<CountedIngredient> list = new ArrayList<>();
        for (ItemStack stack : outputs) {
            if (stack.isEmpty()) {
                continue;
            }
            list.add(new CountedIngredient(
                    ItemResource.of(stack), Math.max(1, stack.getCount()), "", -1));
        }
        return list;
    }

    public boolean routesToSatellite(int slot) {
        return kind == Kind.SHAPED
                && hasSatellite()
                && slot >= SATELLITE_SLOT_START
                && slot < SATELLITE_SLOT_END;
    }

    public record CountedIngredient(ItemResource item, int count, String satellite, int slot) {
    }

    private static String migrateSatellite(String satellite, List<String> legacy) {
        if (satellite != null && !satellite.isBlank()) {
            return satellite.trim();
        }
        if (legacy != null) {
            for (String name : legacy) {
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            }
        }
        return "";
    }

    private static ItemStack copyGhost(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return stack.copy();
    }

    private static List<ItemStack> emptySlots(int n) {
        List<ItemStack> list = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            list.add(ItemStack.EMPTY);
        }
        return list;
    }
}

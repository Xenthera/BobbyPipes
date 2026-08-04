package com.bobby.bobbypipes.block.entity;

import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.craft.CraftPatternResolver;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import com.bobby.bobbypipes.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.neoforged.neoforge.transfer.CombinedResourceHandler;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;
import net.neoforged.neoforge.transfer.transaction.Transaction;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Logistics Crafting Table analogue: ghost matrix + resource buffer + real craft output.
 */
public class PatternTableBlockEntity extends BlockEntity implements MenuProvider {

    public static final int RESOURCE_SLOTS = 18;
    public static final int OUTPUT_SLOTS = 1;

    private CraftPattern pattern = CraftPattern.EMPTY;
    private final ItemStacksResourceHandler resources = new ItemStacksResourceHandler(RESOURCE_SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final ItemStacksResourceHandler output = new ItemStacksResourceHandler(OUTPUT_SLOTS) {
        @Override
        protected void onContentsChanged(int index, ItemStack previousContents) {
            setChanged();
        }
    };
    private final ResourceHandler<ItemResource> exposed =
            new CombinedResourceHandler<>(resources, output);

    public PatternTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PATTERN_TABLE.get(), pos, state);
    }

    public CraftPattern pattern() {
        return pattern;
    }

    public ItemStacksResourceHandler resources() {
        return resources;
    }

    public ItemStacksResourceHandler output() {
        return output;
    }

    public ResourceHandler<ItemResource> itemHandler() {
        return exposed;
    }

    public void setPattern(CraftPattern pattern) {
        CraftPattern next;
        if (pattern == null) {
            next = CraftPattern.EMPTY;
        } else if (pattern.kind() != CraftPattern.Kind.SHAPED || pattern.inputs().size() != 9) {
            next = CraftPattern.shaped(pattern.inputs(), pattern.primaryOutput(), pattern.satellite());
        } else {
            next = pattern;
        }
        if (level instanceof ServerLevel serverLevel) {
            this.pattern = CraftPatternResolver.resolveShapedResult(serverLevel, next);
        } else {
            this.pattern = next;
        }
        setChanged();
        if (level != null && !level.isClientSide()) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    public void setMatrixSlot(int slot, ItemStack ghost) {
        if (slot < 0 || slot >= 9) {
            return;
        }
        setPattern(pattern.withInput(slot, ghost.isEmpty() ? ItemStack.EMPTY : ghost.copyWithCount(1)));
    }

    /** Server tick: craft when resources cover the ghost matrix and output has room. */
    public static void serverTick(Level level, BlockPos pos, BlockState state, PatternTableBlockEntity table) {
        if (level.isClientSide()) {
            return;
        }
        table.tryCraft();
    }

    public boolean tryCraft() {
        if (level == null || level.isClientSide() || pattern.isEmpty()) {
            return false;
        }
        ItemStack ghostResult = pattern.primaryOutput();
        if (ghostResult.isEmpty()) {
            return false;
        }
        ItemStack existingOut = stackIn(output, 0);
        if (!existingOut.isEmpty()) {
            if (!ItemStack.isSameItemSameComponents(existingOut, ghostResult)
                    || existingOut.getCount() + ghostResult.getCount() > existingOut.getMaxStackSize()) {
                return false;
            }
        }

        int[] fromSlot = new int[9];
        int[] used = new int[RESOURCE_SLOTS];
        for (int i = 0; i < 9; i++) {
            ItemStack need = i < pattern.inputs().size() ? pattern.inputs().get(i) : ItemStack.EMPTY;
            if (need.isEmpty()) {
                fromSlot[i] = -1;
                continue;
            }
            ItemResource want = ItemResource.of(need);
            boolean found = false;
            for (int j = 0; j < RESOURCE_SLOTS; j++) {
                ItemResource have = resources.getResource(j);
                if (have.isEmpty() || !want.equals(have)) {
                    continue;
                }
                if (resources.getAmountAsInt(j) > used[j]) {
                    used[j]++;
                    fromSlot[i] = j;
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }

        List<ItemStack> craftStacks = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            int j = fromSlot[i];
            if (j < 0) {
                craftStacks.add(ItemStack.EMPTY);
            } else {
                craftStacks.add(resources.getResource(j).toStack(1));
            }
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        CraftingInput input = CraftingInput.of(3, 3, craftStacks);
        Optional<RecipeHolder<CraftingRecipe>> recipe = serverLevel.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, input, level);
        if (recipe.isEmpty() || !recipe.get().value().matches(input, level)) {
            return false;
        }
        ItemStack crafted = recipe.get().value().assemble(input);
        if (crafted.isEmpty() || !ItemResource.of(crafted).equals(ItemResource.of(ghostResult))) {
            return false;
        }

        try (Transaction tx = Transaction.openRoot()) {
            for (int i = 0; i < 9; i++) {
                int j = fromSlot[i];
                if (j < 0) {
                    continue;
                }
                ItemResource resource = resources.getResource(j);
                if (resources.extract(j, resource, 1, tx) != 1) {
                    return false;
                }
            }
            ItemResource outRes = ItemResource.of(crafted);
            int inserted = output.insert(0, outRes, crafted.getCount(), tx);
            if (inserted < crafted.getCount()) {
                return false;
            }
            NonNullList<ItemStack> remainders = recipe.get().value().getRemainingItems(input);
            for (ItemStack rem : remainders) {
                if (rem.isEmpty()) {
                    continue;
                }
                ItemResource remRes = ItemResource.of(rem);
                int left = rem.getCount();
                for (int j = 0; j < RESOURCE_SLOTS && left > 0; j++) {
                    left -= resources.insert(j, remRes, left, tx);
                }
                if (left > 0) {
                    Block.popResource(serverLevel, worldPosition, remRes.toStack(left));
                }
            }
            tx.commit();
        }
        setChanged();
        return true;
    }

    private static ItemStack stackIn(ItemStacksResourceHandler handler, int index) {
        ItemResource resource = handler.getResource(index);
        if (resource.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return resource.toStack(handler.getAmountAsInt(index));
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("menu.bobbypipes.pattern_table");
    }

    @Override
    public @Nullable AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        return new PatternTableMenu(id, inventory, this);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.store("pattern", CraftPattern.CODEC, pattern);
        resources.serialize(output.child("resources"));
        this.output.serialize(output.child("craft_output"));
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        input.read("pattern", CraftPattern.CODEC).ifPresent(parsed -> pattern = parsed);
        input.child("resources").ifPresent(resources::deserialize);
        input.child("craft_output").ifPresent(output::deserialize);
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level != null) {
            dropHandler(level, pos, resources);
            dropHandler(level, pos, output);
        }
        super.preRemoveSideEffects(pos, state);
    }

    private static void dropHandler(Level level, BlockPos pos, ItemStacksResourceHandler handler) {
        for (int i = 0; i < handler.size(); i++) {
            ItemResource resource = handler.getResource(i);
            int amount = handler.getAmountAsInt(i);
            if (!resource.isEmpty() && amount > 0) {
                Containers.dropItemStack(level, pos.getX(), pos.getY(), pos.getZ(), resource.toStack(amount));
            }
        }
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public @Nullable Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

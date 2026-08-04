package com.bobby.bobbypipes.craft;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fills shaped-pattern result stacks from the live crafting recipe book.
 *
 * <p>Ghost UIs often store result count 1 (click-to-set). Without resolving, the planner
 * treats oak planks as 1/run and schedules four log pulls for four planks.
 */
public final class CraftPatternResolver {

    private CraftPatternResolver() {
    }

    public static CraftPattern resolveShapedResult(ServerLevel level, CraftPattern pattern) {
        if (pattern == null || pattern.isEmpty() || pattern.kind() != CraftPattern.Kind.SHAPED) {
            return pattern == null ? CraftPattern.EMPTY : pattern;
        }
        if (!pattern.hasInputs()) {
            return pattern;
        }
        List<ItemStack> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(i < pattern.inputs().size() ? pattern.inputs().get(i) : ItemStack.EMPTY);
        }
        CraftingInput input = CraftingInput.of(3, 3, grid);
        if (input.isEmpty()) {
            return CraftPattern.shaped(grid, ItemStack.EMPTY, pattern.satellite());
        }
        Optional<RecipeHolder<CraftingRecipe>> recipe = level.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, input, level)
                .filter(holder -> holder.value().matches(input, level));
        ItemStack result = recipe
                .map(holder -> holder.value().assemble(input))
                .orElse(ItemStack.EMPTY);
        return CraftPattern.shaped(grid, result, pattern.satellite());
    }
}

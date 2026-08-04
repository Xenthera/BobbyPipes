package com.bobby.bobbypipes.compat.jei;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.craft.CraftPattern;
import com.bobby.bobbypipes.menu.CraftingPipeMenu;
import com.bobby.bobbypipes.menu.PatternTableMenu;
import com.bobby.bobbypipes.network.payload.SetCraftPatternPayload;
import com.bobby.bobbypipes.registry.ModMenus;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotView;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandlerHelper;
import mezz.jei.api.recipe.transfer.IUniversalRecipeTransferHandler;
import mezz.jei.api.recipe.types.IRecipeType;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JEI: {@code +} on a crafting recipe pastes ghosts into the open pattern table / crafting
 * pipe. Does not pull items from the inventory  -  pattern encoding only.
 *
 * <p>Keep the pattern table (or crafting pipe) open underneath the JEI recipe screen so JEI
 * can see the parent menu; otherwise the transfer button stays hidden.
 */
@JeiPlugin
public class BobbyPipesJeiPlugin implements IModPlugin {

    @Override
    public Identifier getPluginUid() {
        return Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "jei");
    }

    @Override
    public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        IRecipeTransferHandlerHelper helper = registration.getTransferHelper();
        // Typed crafting handler  -  what JEI looks up first for the crafting category.
        registration.addRecipeTransferHandler(
                new PatternTableCraftingTransfer(helper), RecipeTypes.CRAFTING);
        registration.addRecipeTransferHandler(
                new CraftingPipeCraftingTransfer(helper), RecipeTypes.CRAFTING);
        // Fallback for other recipe categories on the crafting pipe (processing patterns).
        registration.addUniversalRecipeTransferHandler(new CraftingPipeUniversalTransfer(helper));
    }

    private static CraftPattern shapedFromCrafting(IRecipeTransferHandlerHelper helper,
                                                   RecipeHolder<CraftingRecipe> recipe,
                                                   IRecipeSlotsView recipeSlots) {
        List<ItemStack> grid = new ArrayList<>(9);
        for (int i = 0; i < 9; i++) {
            grid.add(ItemStack.EMPTY);
        }

        Map<Integer, SlotDisplay> bySlot = helper.getGuiSlotIndexToIngredientMap(recipe);
        if (!bySlot.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level != null) {
                ContextMap context = SlotDisplayContext.fromLevel(minecraft.level);
                for (Map.Entry<Integer, SlotDisplay> entry : bySlot.entrySet()) {
                    int index = entry.getKey();
                    if (index < 0 || index >= 9) {
                        continue;
                    }
                    List<ItemStack> options = entry.getValue().resolveForStacks(context);
                    if (!options.isEmpty()) {
                        grid.set(index, options.getFirst().copy());
                    }
                }
            }
        } else {
            // Fallback: JEI already laid inputs into a 3x3 (possibly with empties).
            List<IRecipeSlotView> inputs = recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
            for (int i = 0; i < Math.min(9, inputs.size()); i++) {
                grid.set(i, inputs.get(i).getDisplayedItemStack().orElse(ItemStack.EMPTY).copy());
            }
        }

        ItemStack result = ItemStack.EMPTY;
        for (IRecipeSlotView slot : recipeSlots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            Optional<ItemStack> shown = slot.getDisplayedItemStack();
            if (shown.isPresent() && !shown.get().isEmpty()) {
                result = shown.get().copy();
                break;
            }
        }
        return CraftPattern.shaped(grid, result);
    }

    private static CraftPattern fromSlotsFallback(IRecipeSlotsView slots, boolean shapedOnly) {
        List<ItemStack> inputs = new ArrayList<>();
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.INPUT)) {
            inputs.add(slot.getDisplayedItemStack().orElse(ItemStack.EMPTY).copy());
        }
        while (inputs.size() < 9) {
            inputs.add(ItemStack.EMPTY);
        }
        if (!shapedOnly && inputs.size() > 9) {
            List<ItemStack> trimmedIn = new ArrayList<>(
                    inputs.stream().filter(s -> !s.isEmpty()).toList());
            List<ItemStack> outputs = new ArrayList<>();
            for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
                slot.getDisplayedItemStack().ifPresent(stack -> outputs.add(stack.copy()));
            }
            if (trimmedIn.isEmpty()) {
                trimmedIn.add(ItemStack.EMPTY);
            }
            if (outputs.isEmpty()) {
                outputs.add(ItemStack.EMPTY);
            }
            return CraftPattern.processing(trimmedIn, outputs);
        }
        ItemStack result = ItemStack.EMPTY;
        for (IRecipeSlotView slot : slots.getSlotViews(RecipeIngredientRole.OUTPUT)) {
            Optional<ItemStack> shown = slot.getDisplayedItemStack();
            if (shown.isPresent() && !shown.get().isEmpty()) {
                result = shown.get().copy();
                break;
            }
        }
        return CraftPattern.shaped(inputs.subList(0, 9), result);
    }

    private static final class PatternTableCraftingTransfer
            implements IRecipeTransferHandler<PatternTableMenu, RecipeHolder<CraftingRecipe>> {

        private final IRecipeTransferHandlerHelper helper;

        private PatternTableCraftingTransfer(IRecipeTransferHandlerHelper helper) {
            this.helper = helper;
        }

        @Override
        public Class<? extends PatternTableMenu> getContainerClass() {
            return PatternTableMenu.class;
        }

        @Override
        public Optional<MenuType<PatternTableMenu>> getMenuType() {
            return Optional.of(ModMenus.PATTERN_TABLE.get());
        }

        @Override
        public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(PatternTableMenu container,
                                                             RecipeHolder<CraftingRecipe> recipe,
                                                             IRecipeSlotsView recipeSlots,
                                                             Player player,
                                                             boolean maxTransfer,
                                                             boolean doTransfer) {
            CraftPattern pattern = shapedFromCrafting(helper, recipe, recipeSlots);
            if (!pattern.hasInputs()) {
                return helper.createInternalError();
            }
            if (doTransfer) {
                container.setPatternLocal(pattern);
                ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                        container.pos(), SetCraftPatternPayload.Target.TABLE, pattern));
            }
            // null = success, no inventory check  -  ghost encode only
            return null;
        }
    }

    private static final class CraftingPipeCraftingTransfer
            implements IRecipeTransferHandler<CraftingPipeMenu, RecipeHolder<CraftingRecipe>> {

        private final IRecipeTransferHandlerHelper helper;

        private CraftingPipeCraftingTransfer(IRecipeTransferHandlerHelper helper) {
            this.helper = helper;
        }

        @Override
        public Class<? extends CraftingPipeMenu> getContainerClass() {
            return CraftingPipeMenu.class;
        }

        @Override
        public Optional<MenuType<CraftingPipeMenu>> getMenuType() {
            return Optional.of(ModMenus.CRAFTING_PIPE.get());
        }

        @Override
        public IRecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
            return RecipeTypes.CRAFTING;
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(CraftingPipeMenu container,
                                                             RecipeHolder<CraftingRecipe> recipe,
                                                             IRecipeSlotsView recipeSlots,
                                                             Player player,
                                                             boolean maxTransfer,
                                                             boolean doTransfer) {
            CraftPattern pattern = shapedFromCrafting(helper, recipe, recipeSlots)
                    .withSatellite(container.pattern().satellite());
            if (!pattern.hasInputs()) {
                return helper.createInternalError();
            }
            if (doTransfer) {
                container.setPatternLocal(pattern);
                ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                        container.pos(), SetCraftPatternPayload.Target.PIPE, pattern));
            }
            return null;
        }
    }

    private static final class CraftingPipeUniversalTransfer
            implements IUniversalRecipeTransferHandler<CraftingPipeMenu> {

        private final IRecipeTransferHandlerHelper helper;

        private CraftingPipeUniversalTransfer(IRecipeTransferHandlerHelper helper) {
            this.helper = Objects.requireNonNull(helper);
        }

        @Override
        public Class<? extends CraftingPipeMenu> getContainerClass() {
            return CraftingPipeMenu.class;
        }

        @Override
        public Optional<MenuType<CraftingPipeMenu>> getMenuType() {
            return Optional.of(ModMenus.CRAFTING_PIPE.get());
        }

        @Override
        public @Nullable IRecipeTransferError transferRecipe(CraftingPipeMenu container,
                                                             Object recipe,
                                                             IRecipeSlotsView recipeSlots,
                                                             Player player,
                                                             boolean maxTransfer,
                                                             boolean doTransfer) {
            // Crafting recipes are handled by CraftingPipeCraftingTransfer.
            if (recipe instanceof RecipeHolder<?>) {
                return helper.createInternalError();
            }
            CraftPattern pattern = fromSlotsFallback(recipeSlots, false)
                    .withSatellite(container.pattern().satellite());
            if (pattern.isEmpty()) {
                return helper.createInternalError();
            }
            if (doTransfer) {
                container.setPatternLocal(pattern);
                ClientPacketDistributor.sendToServer(new SetCraftPatternPayload(
                        container.pos(), SetCraftPatternPayload.Target.PIPE, pattern));
            }
            return null;
        }
    }
}

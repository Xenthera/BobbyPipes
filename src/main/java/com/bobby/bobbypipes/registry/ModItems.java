package com.bobby.bobbypipes.registry;

import com.bobby.bobbypipes.BobbyPipes;
import com.bobby.bobbypipes.transit.ParcelTier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.TooltipDisplay;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Consumer;

public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BobbyPipes.MOD_ID);

    /**
     * Opens the configuration screen of any routed pipe.
     *
     * <p>Pipes sit against chests and machines, so a bare right click is far more often an
     * attempt to open the container behind them. Gating the screens behind a tool keeps
     * that from being hijacked.
     */
    public static final DeferredItem<Item> WRENCH = ITEMS.registerItem("wrench",
            props -> new Item(props.stacksTo(1)) {
                @Override
                public void appendHoverText(ItemStack stack,
                                            Item.TooltipContext context,
                                            TooltipDisplay display,
                                            Consumer<Component> lines,
                                            TooltipFlag flag) {
                    lines.accept(Component.translatable("item.bobbypipes.wrench.tip")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * Hides a pipe inside a full block that can be made to look like any other block.
     *
     * <p>Right click a pipe to fit one, then right click the cover with any block to give
     * it that block's appearance. Sneak and wrench a covered pipe to take it back off.
     */
    public static final DeferredItem<Item> CHAMELEON_COVER = ITEMS.registerItem("chameleon_cover",
            props -> new Item(props) {
                @Override
                public void appendHoverText(ItemStack stack,
                                            Item.TooltipContext context,
                                            TooltipDisplay display,
                                            Consumer<Component> lines,
                                            TooltipFlag flag) {
                    lines.accept(Component.translatable("item.bobbypipes.chameleon_cover.tip")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * Head-slot visor that shows live status of the pipe under the crosshair in world.
     */
    public static final DeferredItem<Item> PIPE_GOGGLES = ITEMS.registerItem("pipe_goggles",
            props -> new Item(props.stacksTo(1).equippable(EquipmentSlot.HEAD)) {
                @Override
                public void appendHoverText(ItemStack stack,
                                            Item.TooltipContext context,
                                            TooltipDisplay display,
                                            Consumer<Component> lines,
                                            TooltipFlag flag) {
                    lines.accept(Component.translatable("item.bobbypipes.pipe_goggles.tip")
                            .withStyle(ChatFormatting.GRAY));
                }
            });

    /**
     * Client-only render stand-in for the routed-parcel cage. Not shown in the creative tab.
     */
    public static final DeferredItem<Item> PARCEL_CAGE = ITEMS.registerItem("parcel_cage",
            props -> new Item(props.stacksTo(1)));

    /**
     * Client-only render stand-ins for energy parcels in transit. Not shown in the creative
     * tab. One item per {@link ParcelTier} so density reads as texture, not scale.
     */
    public static final DeferredItem<Item> ENERGY_PARCEL = ITEMS.registerItem("energy_parcel",
            props -> new Item(props.stacksTo(1)));
    public static final DeferredItem<Item> ENERGY_PARCEL_DENSE =
            ITEMS.registerItem("energy_parcel_dense", props -> new Item(props.stacksTo(1)));
    public static final DeferredItem<Item> ENERGY_PARCEL_BULK =
            ITEMS.registerItem("energy_parcel_bulk", props -> new Item(props.stacksTo(1)));

    /**
     * Client-only render stand-ins for fluid parcels in transit. Not shown in the creative
     * tab. One item per {@link ParcelTier}, same as energy.
     */
    public static final DeferredItem<Item> FLUID_PARCEL = ITEMS.registerItem("fluid_parcel",
            props -> new Item(props.stacksTo(1)));
    public static final DeferredItem<Item> FLUID_PARCEL_DENSE =
            ITEMS.registerItem("fluid_parcel_dense", props -> new Item(props.stacksTo(1)));
    public static final DeferredItem<Item> FLUID_PARCEL_BULK =
            ITEMS.registerItem("fluid_parcel_bulk", props -> new Item(props.stacksTo(1)));

    /** Stand-in item for an energy parcel of the given density tier. */
    public static Item energyParcel(ParcelTier tier) {
        return switch (tier) {
            case STANDARD -> ENERGY_PARCEL.get();
            case DENSE -> ENERGY_PARCEL_DENSE.get();
            case BULK -> ENERGY_PARCEL_BULK.get();
        };
    }

    /** Stand-in item for a fluid parcel of the given density tier. */
    public static Item fluidParcel(ParcelTier tier) {
        return switch (tier) {
            case STANDARD -> FLUID_PARCEL.get();
            case DENSE -> FLUID_PARCEL_DENSE.get();
            case BULK -> FLUID_PARCEL_BULK.get();
        };
    }

    private ModItems() {
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
    }
}

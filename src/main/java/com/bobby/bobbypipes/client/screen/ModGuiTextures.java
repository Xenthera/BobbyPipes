package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbypipes.BobbyPipes;
import net.minecraft.resources.Identifier;

/**
 * Background textures for BobbyPipes container screens (256x256 atlases).
 */
public final class ModGuiTextures {

    public static final Identifier PATTERN_TABLE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/pattern_table.png");
    public static final Identifier CRAFTING_PIPE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/crafting_pipe.png");
    public static final Identifier SUPPLIER_PIPE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/supplier_pipe.png");
    /** Same panel as the Supplier, hue matched to the passive supplier's own pipe. */
    public static final Identifier PASSIVE_SUPPLIER_PIPE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/passive_supplier_pipe.png");
    public static final Identifier BASIC_PIPE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/basic_pipe.png");
    public static final Identifier SATELLITE_PIPE =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/satellite_pipe.png");
    public static final Identifier REQUEST =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/request.png");
    /** 20×20 rim drawn over a selected request-grid slot (1px overhang). */
    public static final Identifier REQUEST_SLOT_SELECTED =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/request_slot_selected.png");
    /** Vanilla paper copy ({@code textures/item/paper.png}). */
    public static final Identifier PAPER =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/paper.png");
    /** Same paper shape with white filled black (alpha preserved). */
    public static final Identifier BLACK_PAPER =
            Identifier.fromNamespaceAndPath(BobbyPipes.MOD_ID, "textures/gui/black_paper.png");

    private ModGuiTextures() {
    }
}

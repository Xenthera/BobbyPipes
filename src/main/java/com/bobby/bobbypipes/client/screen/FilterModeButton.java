package com.bobby.bobbypipes.client.screen;

import com.bobby.bobbycore.client.gui.draw.UiDraw;
import com.bobby.bobbycore.client.gui.theme.UiTheme;
import com.bobby.bobbycore.client.gui.widget.UiButton;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.Consumer;

/**
 * Icon-only whitelist / blacklist toggle (paper / black paper textures).
 */
final class FilterModeButton extends AbstractButton {

    private static final int ICON = 16;
    private static final int PAD = 2;
    static final int SIZE = ICON + PAD * 2;

    private final Consumer<Boolean> onToggle;
    private UiTheme theme;
    private boolean whitelist;

    private FilterModeButton(int x, int y, boolean whitelist,
                             Consumer<Boolean> onToggle, UiTheme theme) {
        super(x, y, SIZE, SIZE, label(whitelist));
        this.whitelist = whitelist;
        this.onToggle = onToggle;
        this.theme = theme;
        setTooltip(Tooltip.create(tip(whitelist)));
    }

    static FilterModeButton create(int x, int y, boolean whitelist,
                                   Consumer<Boolean> onToggle, UiTheme theme) {
        return new FilterModeButton(x, y, whitelist, onToggle, theme);
    }

    void setWhitelist(boolean whitelist) {
        if (this.whitelist == whitelist) {
            return;
        }
        this.whitelist = whitelist;
        setMessage(label(whitelist));
        setTooltip(Tooltip.create(tip(whitelist)));
    }

    @Override
    public void onPress(InputWithModifiers input) {
        setWhitelist(!whitelist);
        onToggle.accept(this.whitelist);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        boolean hovered = this.isHoveredOrFocused();
        int fill = hovered ? theme.buttonFillHover() : theme.buttonFill();
        int x = getX();
        int y = getY();
        UiDraw.fillRounded(graphics, x, y, SIZE, SIZE, UiButton.RADIUS, fill);
        int border = hovered ? theme.accent() : theme.panelBorder();
        UiDraw.borderRounded(graphics, x, y, SIZE, SIZE, UiButton.RADIUS, border, theme.borderWidth());

        Identifier icon = whitelist ? ModGuiTextures.PAPER : ModGuiTextures.BLACK_PAPER;
        graphics.blit(
                RenderPipelines.GUI_TEXTURED,
                icon,
                x + PAD, y + PAD,
                0.0F, 0.0F,
                ICON, ICON,
                ICON, ICON,
                ICON, ICON);
    }

    private static Component label(boolean whitelist) {
        return Component.translatable(whitelist
                ? "gui.bobbypipes.provider.whitelist"
                : "gui.bobbypipes.provider.blacklist");
    }

    private static Component tip(boolean whitelist) {
        return Component.translatable(whitelist
                ? "gui.bobbypipes.provider.whitelist.tip"
                : "gui.bobbypipes.provider.blacklist.tip");
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}

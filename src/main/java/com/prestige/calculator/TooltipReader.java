package com.prestige.calculator;

import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.List;

/**
 * Tooltip extraction is intentionally isolated. Minecraft's GUI tooltip is
 * rendered from item stacks; the next implementation stage will connect this
 * reader to the server's actual prestige-menu screen and verify its item data.
 */
public final class TooltipReader {
    private TooltipReader() {}

    public static List<Text> getTooltipLines(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return List.of();
        MinecraftClient client = MinecraftClient.getInstance();
        return stack.getTooltip(
                net.minecraft.item.Item.TooltipContext.DEFAULT,
                client.player,
                net.minecraft.item.tooltip.TooltipType.BASIC
        );
    }
}

package com.prestige.calculator.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the GUI origin so the scanner can place the real OS cursor
 * over the centre of each inventory slot.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int prestige$getX();

    @Accessor("y")
    int prestige$getY();
}

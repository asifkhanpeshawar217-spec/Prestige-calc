package com.prestige.calculator;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class PrestigeCalculatorClient implements ClientModInitializer {
    private static KeyBinding openCalculatorKey;
    private static int scanTicks;

    @Override
    public void onInitializeClient() {
        openCalculatorKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.prestige_calculator.open",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_P,
                        KeyBinding.Category.MISC
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openCalculatorKey.wasPressed()) {
                if (client.currentScreen == null) {
                    client.setScreen(new CalculatorScreen());
                } else if (client.currentScreen instanceof CalculatorScreen) {
                    client.setScreen(null);
                }
            }

            if (++scanTicks >= 10) {
                scanTicks = 0;
                PrestigeInventoryScanner.scan();
            }
        });
    }
}

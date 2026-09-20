package com.example.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean xrayEnabled = false;
    private static KeyBinding xrayKeyBind;

    @Override
    public void onInitializeClient() {
        // 'X' key register karna X-ray on/off karne ke liye
        xrayKeyBind = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.xray",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                "category.lavaassistant.client"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (xrayKeyBind.wasPressed()) {
                xrayEnabled = !xrayEnabled;
                if (client.player != null) {
                    client.player.sendMessage(
                        net.minecraft.text.Text.literal("§6[LavaAssistant] X-Ray: " + (xrayEnabled ? "§aON" : "§cOFF")), 
                        true
                    );
                }
                // Screen refresh karne ke liye chunk renderer reload karna
                if (client.worldRenderer != null) {
                    client.worldRenderer.reload();
                }
            }
        });
    }
}

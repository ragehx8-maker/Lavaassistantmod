package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean enabled = false;
    private boolean wasPressed = false;

    @Override
    public void onInitializeClient() {
        System.out.println("[LavaAssistant] TEST CLIENT LOADED");

        // Screen HUD Render Callback for active popup
        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled && mc.player != null && mc.currentScreen == null) {
                int w = mc.getWindow().getScaledWidth();
                int h = mc.getWindow().getScaledHeight();
                ctx.drawText(mc.textRenderer, "§a[AutoLava: ACTIVE]", w / 2 - 45, h / 2 + 15, 0xFFFFFFFF, true);
            }
        });

        // Tick Event to listen for 'R' key
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getWindow() == null) return;

            // Direct GLFW check for 'R' key
            boolean isPressed = GLFW.glfwGetKey(mc.getWindow().getHandle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
            if (isPressed && !wasPressed) {
                enabled = !enabled;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fAutoLava Enabled!"
                            : "§c[LavaAssistant] §fAutoLava Disabled!"), false);
                }
            }
            wasPressed = isPressed;
        });
    }
}

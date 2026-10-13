package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean enabled = false;
    public static boolean xrayEnabled = false;
    private boolean wasPressed = false;

    private boolean hasPlaced = false;
    private long lastActionTime = 0;
    private final long delayMs = 80;

    @Override
    public void onInitializeClient() {
        // Screen par On/Off status popup dikhane ke liye HUD render callback
        HudRenderCallback.EVENT.register((drawContext, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled && mc.player != null && mc.currentScreen == null) {
                int screenWidth = mc.getWindow().getScaledWidth();
                int screenHeight = mc.getWindow().getScaledHeight();
                
                // Screen ke center mein status text render karega
                drawContext.drawText(mc.textRenderer, "§a[AutoLava: ACTIVE]", screenWidth / 2 - 40, screenHeight / 2 + 15, 0xFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.window == null)
                return;

            // Direct GLFW check for 'R' key
            boolean isPressed = GLFW.glfwGetKey(mc.window.getHandle(), GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
            if (isPressed && !wasPressed) {
                enabled = !enabled;
                if (!enabled) {
                    hasPlaced = false;
                }
            }
            wasPressed = isPressed;

            if (!enabled || mc.player == null || mc.world == null || mc.currentScreen != null)
                return;

            boolean holdingLava = mc.player.getMainHandStack().getItem() == Items.LAVA_BUCKET;
            boolean holdingEmptyBucket = mc.player.getMainHandStack().getItem() == Items.BUCKET;

            if (!holdingLava && !holdingEmptyBucket) {
                hasPlaced = false;
                return;
            }

            PlayerEntity target = findNearestPlayer(mc, 4.5);
            if (target == null)
                return;

            if (System.currentTimeMillis() - lastActionTime < delayMs)
                return;

            BlockPos targetPos = target.getBlockPos().down();
            Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);

            if (holdingLava && !hasPlaced) {
                if (mc.interactionManager != null) {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                }
                hasPlaced = true;
                lastActionTime = System.currentTimeMillis();
            } else if (holdingEmptyBucket && hasPlaced) {
                if (mc.interactionManager != null) {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
                }
                hasPlaced = false;
                lastActionTime = System.currentTimeMillis();
            }
        });
    }

    private PlayerEntity findNearestPlayer(MinecraftClient mc, double range) {
        PlayerEntity nearest = null;
        double nearestDistanceSq = range * range;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator())
                continue;

            double distanceSq = mc.player.squaredDistanceTo(player);
            if (distanceSq <= nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearest = player;
            }
        }
        return nearest;
    }
}

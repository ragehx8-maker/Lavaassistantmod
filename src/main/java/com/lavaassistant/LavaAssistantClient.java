package com.example.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {

    private static boolean toggleState = false;
    private static long popupShowUntil = 0;
    private static final long POPUP_DURATION_MS = 1500;

    private static KeyBinding toggleKey;
    private int actionTicks = 0;
    private int cooldownTicks = 0;
    private int currentState = 0; // 0: Idle, 1: Placed (Pending Scoop)
    private BlockPos activePos = null;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "key.categories.lavaassistant"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null || client.getNetworkHandler() == null) return;

            while (toggleKey.wasPressed()) {
                toggleState = !toggleState;
                popupShowUntil = System.currentTimeMillis() + POPUP_DURATION_MS;
                currentState = 0;
                actionTicks = 0;
                cooldownTicks = 0;
                activePos = null;
            }

            if (!toggleState) return;

            // Global cooldown management
            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }

            // High-Level Smart Manual / Auto Pickup Detector:
            // Chaise mod ne dala ho ya tune khud manually dala ho, agar haath me empty bucket hai aur lava block mojood hai, toh utha lo!
            if (currentState == 0 && client.player.getMainHandStack().isOf(Items.BUCKET)) {
                BlockPos floorPos = client.player.getBlockPos().down();
                if (client.world.getBlockState(floorPos).isOf(net.minecraft.block.Blocks.LAVA)) {
                    activePos = floorPos;
                    currentState = 1;
                    actionTicks = 6; // Ultra-fast optimized pickup delay (~0.3s)
                }
            }

            // Active Task Handler (Scooping back the lava)
            if (actionTicks > 0) {
                actionTicks--;
                if (actionTicks == 0 && currentState == 1 && activePos != null && client.interactionManager != null) {
                    if (client.player.getMainHandStack().isOf(Items.BUCKET)) {
                        executeLookAt(client, activePos);

                        Vec3d hitVec = new Vec3d(activePos.getX() + 0.5, activePos.getY() + 0.5, activePos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, activePos, false);
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                    }
                    currentState = 0;
                    activePos = null;
                    cooldownTicks = 20; // 1 second clean spacing to prevent packet spam
                }
                return;
            }

            // Must hold Lava Bucket to execute automated placement
            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // Target closest enemy within 4 blocks range (16 sq distance)
            PlayerEntity targetPlayer = null;
            double nearestDistSq = 16.0;

            for (PlayerEntity entity : client.world.getPlayers()) {
                if (entity == client.player) continue;
                double distSq = client.player.squaredDistanceTo(entity);
                if (distSq < nearestDistSq) {
                    targetPlayer = entity;
                    nearestDistSq = distSq;
                }
            }

            if (targetPlayer == null) return;

            // SAFETY: Skip if target is already burning
            if (targetPlayer.isOnFire()) return;

            BlockPos targetBlockPos = targetPlayer.getBlockPos().down();

            // SAFETY: Do not place on water or non-air blocks (Prevents obsidian creation)
            if (client.world.getBlockState(targetBlockPos).isOf(net.minecraft.block.Blocks.WATER) || 
                !client.world.getBlockState(targetBlockPos).isAir()) {
                return;
            }

            if (client.interactionManager != null) {
                activePos = targetBlockPos;

                // Execute precise angle calculations and synchronization
                executeLookAt(client, activePos);

                // Instant placement packet and execution
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

                currentState = 1;
                actionTicks = 8; // Optimized tick duration before triggering the cleanup scoop
            }
        });

        // Native Toggle HUD Notification
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (System.currentTimeMillis() < popupShowUntil) {
                MinecraftClient client = MinecraftClient.getInstance();

                String msg = toggleState ? "ON" : "OFF";
                Formatting color = toggleState ? Formatting.GREEN : Formatting.RED;

                int screenWidth = client.getWindow().getScaledWidth();
                int textWidth = client.textRenderer.getWidth(msg);

                drawContext.drawText(
                        client.textRenderer,
                        Text.literal(msg).formatted(color, Formatting.BOLD),
                        (screenWidth - textWidth) / 2,
                        20,
                        0xFFFFFF,
                        true
                );
            }
        });
    }

    // Helper method for clean, lag-free look rotation and packet dispatch
    private void executeLookAt(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        double deltaX = pos.getX() + 0.5 - client.player.getX();
        double deltaY = (pos.getY() + 0.5) - client.player.getEyeY();
        double deltaZ = pos.getZ() + 0.5 - client.player.getZ();
        double horizontalDist = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        float computedYaw = (float) (Math.toDegrees(Math.atan2(deltaZ, deltaX)) - 90.0);
        float computedPitch = (float) (-Math.toDegrees(Math.atan2(deltaY, horizontalDist)));

        client.player.setYaw(computedYaw);
        client.player.setPitch(computedPitch);

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                computedYaw, computedPitch, client.player.isOnGround()
        ));
    }
            }

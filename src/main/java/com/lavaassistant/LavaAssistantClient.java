package com.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class LavaAssistantClient implements ClientModInitializer {
    private static boolean isEnabled = false;
    private static boolean wasPressedLastFrame = false;
    private int actionDelayTicks = 0;
    private int taskState = 0; // 0 = Idle / Ready to place, 1 = Placed Lava, waiting to scoop
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            long window = client.getWindow().getHandle();
            boolean isRPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;

            // Toggle logic with 'R' key
            if (isRPressed && !wasPressedLastFrame) {
                isEnabled = !isEnabled;
                if (isEnabled) {
                    client.player.sendMessage(Text.literal("§a[LavaAssistant] Enabled"), true);
                } else {
                    client.player.sendMessage(Text.literal("§cLavaAssistant Disabled"), true);
                    taskState = 0;
                    actionDelayTicks = 0;
                }
            }
            wasPressedLastFrame = isRPressed;

            if (!isEnabled) {
                return;
            }

            // Handle anti-cheat safe tick delays
            if (actionDelayTicks > 0) {
                actionDelayTicks--;
                return;
            }

            runAntiCheatSafeLogic(client);
        });
    }

    private void runAntiCheatSafeLogic(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        // State 1: Scoop the lava back up with an empty bucket after a human-like delay
        if (taskState == 1) {
            int emptyBucketSlot = findItemInHotbar(player, Items.BUCKET);
            if (emptyBucketSlot != -1) {
                player.getInventory().selectedSlot = emptyBucketSlot;
                client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            }
            taskState = 0;
            // Randomized delay (4-7 ticks) to mimic human reaction and avoid anti-cheat flags
            actionDelayTicks = 4 + random.nextInt(4);
            return;
        }

        // State 0: Scan for target player/hostile entity and place lava at their feet
        Entity target = null;
        double minDistance = 5.0;

        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            if (entity instanceof LivingEntity && (entity instanceof HostileEntity || entity instanceof PlayerEntity)) {
                double dist = player.distanceTo(entity);
                if (dist <= minDistance) {
                    Vec3d lookDir = player.getRotationVector();
                    Vec3d toEntity = entity.getPos().subtract(player.getPos()).normalize();
                    // Crosshair view direction alignment check
                    if (lookDir.dotProduct(toEntity) > 0.35) {
                        target = entity;
                        minDistance = dist;
                    }
                }
            }
        }

        if (target != null) {
            int lavaSlot = findItemInHotbar(player, Items.LAVA_BUCKET);
            if (lavaSlot != -1) {
                player.getInventory().selectedSlot = lavaSlot;
                BlockPos targetPos = target.getBlockPos();
                
                // Place lava at the target's exact feet position
                client.interactionManager.interactBlock(
                        player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(new Vec3d(targetPos.getX(), targetPos.getY(), targetPos.getZ()), Direction.UP, targetPos, false)
                );
                
                taskState = 1;
                // Randomized delay before scooping back up to prevent instant-packet detection
                actionDelayTicks = 5 + random.nextInt(3);
            }
        }
    }

    private int findItemInHotbar(PlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }
}

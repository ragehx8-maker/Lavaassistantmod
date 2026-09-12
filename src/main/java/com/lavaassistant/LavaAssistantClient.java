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
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class LavaAssistantClient implements ClientModInitializer {
    private static boolean isEnabled = false;
    private static boolean wasCPressedLastFrame = false;
    private int actionDelayTicks = 0;
    private int taskState = 0; // 0 = Idle, 1 = Placed Lava (waiting to scoop), 2 = Post-scoop cooldown
    private BlockPos targetBlockPos = null;
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            // GLFW 'C' key toggle with edge detection for reliable mobile/Pojav execution
            long window = client.getWindow().getHandle();
            boolean isCPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;

            if (isCPressed && !wasCPressedLastFrame) {
                isEnabled = !isEnabled;
                if (isEnabled) {
                    client.player.sendMessage(Text.literal("§a[LavaAssistant] Enabled (C Key)"), true);
                } else {
                    client.player.sendMessage(Text.literal("§cLavaAssistant Disabled"), true);
                    resetState();
                }
            }
            wasCPressedLastFrame = isCPressed;

            if (!isEnabled) return;

            if (actionDelayTicks > 0) {
                actionDelayTicks--;
                return;
            }

            runIntelligentLogic(client);
        });
    }

    private void runIntelligentLogic(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        // State 1: Scoop the lava back up using an empty bucket at the exact placed block position
        if (taskState == 1) {
            int emptyBucketSlot = findItemInHotbar(player, Items.BUCKET);
            if (emptyBucketSlot != -1 && targetBlockPos != null) {
                player.getInventory().selectedSlot = emptyBucketSlot;
                
                // Correct scoop logic: interactBlock on the exact block position with UP face
                BlockHitResult hitResult = new BlockHitResult(
                        new Vec3d(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5),
                        Direction.UP,
                        targetBlockPos,
                        false
                );
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hitResult);
            }
            resetState();
            actionDelayTicks = 4 + random.nextInt(4); // Randomized anti-cheat buffer
            return;
        }

        // State 0: Scan for target with line-of-sight raycasting and crosshair alignment
        Entity target = null;
        double minDistance = 5.0;

        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            if (entity instanceof LivingEntity && (entity instanceof HostileEntity || entity instanceof PlayerEntity)) {
                double dist = player.distanceTo(entity);
                if (dist <= minDistance) {
                    Vec3d lookDir = player.getRotationVector();
                    Vec3d toEntity = entity.getPos().subtract(player.getPos()).normalize();
                    
                    // Crosshair alignment check
                    if (lookDir.dotProduct(toEntity) > 0.35) {
                        // Line-of-sight check to ensure no walls are blocking
                        RaycastContext context = new RaycastContext(
                                player.getEyePos(),
                                entity.getEyePos(),
                                RaycastContext.ShapeType.COLLIDER,
                                RaycastContext.FluidHandling.NONE,
                                player
                        );
                        BlockHitResult rayHit = client.world.raycast(context);
                        if (rayHit.getType() == HitResult.Type.MISS || rayHit.getPos().distanceTo(player.getEyePos()) >= dist) {
                            target = entity;
                            minDistance = dist;
                        }
                    }
                }
            }
        }

        if (target != null) {
            int lavaSlot = findItemInHotbar(player, Items.LAVA_BUCKET);
            if (lavaSlot != -1) {
                player.getInventory().selectedSlot = lavaSlot;
                targetBlockPos = target.getBlockPos();

                // Place lava accurately at the target's feet block position
                BlockHitResult placeHit = new BlockHitResult(
                        new Vec3d(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5),
                        Direction.UP,
                        targetBlockPos,
                        false
                );
                
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, placeHit);
                
                taskState = 1;
                // Randomized human-like delay before scooping back up
                actionDelayTicks = 5 + random.nextInt(3);
            }
        }
    }

    private void resetState() {
        taskState = 0;
        targetBlockPos = null;
        actionDelayTicks = 0;
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

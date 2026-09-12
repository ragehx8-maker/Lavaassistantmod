package com.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
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

import java.util.Random;

public class LavaAssistantClient implements ClientModInitializer {
    private static boolean isEnabled = false;
    private int actionDelayTicks = 0;
    private int taskState = 0; // 0 = Idle, 1 = Placed Lava, waiting to scoop
    private final Random random = new Random();

    @Override
    public void onInitializeClient() {
        // Register client-side commands: type /lava to enable, /lavaoff to disable
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("lava")
                    .executes(context -> {
                        isEnabled = true;
                        if (context.getSource().getPlayer() != null) {
                            context.getSource().getPlayer().sendMessage(Text.literal("§a[LavaAssistant] Enabled!"), false);
                        }
                        return 1;
                    }));

            dispatcher.register(ClientCommandManager.literal("lavaoff")
                    .executes(context -> {
                        isEnabled = false;
                        taskState = 0;
                        actionDelayTicks = 0;
                        if (context.getSource().getPlayer() != null) {
                            context.getSource().getPlayer().sendMessage(Text.literal("§c[LavaAssistant] Disabled!"), false);
                        }
                        return 1;
                    }));
        });

        // Main execution loop hooked into client ticks
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            if (!isEnabled) {
                return;
            }

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
            // Randomized human-like delay before next action cycle
            actionDelayTicks = 4 + random.nextInt(4);
            return;
        }

        // State 0: Scan for target player/hostile mob in front of crosshair and place lava
        Entity target = null;
        double minDistance = 5.0;

        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            if (entity instanceof LivingEntity && (entity instanceof HostileEntity || entity instanceof PlayerEntity)) {
                double dist = player.distanceTo(entity);
                if (dist <= minDistance) {
                    Vec3d lookDir = player.getRotationVector();
                    Vec3d toEntity = entity.getPos().subtract(player.getPos()).normalize();
                    // Crosshair alignment check via dot product
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
                
                // Place lava at target's feet position
                client.interactionManager.interactBlock(
                        player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(new Vec3d(targetPos.getX(), targetPos.getY(), targetPos.getZ()), Direction.UP, targetPos, false)
                );
                
                taskState = 1;
                // Randomized tick delay to bypass anti-cheat instant-packet flags
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

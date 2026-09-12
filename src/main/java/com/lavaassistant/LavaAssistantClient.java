package com.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
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

public class LavaAssistantClient implements ClientModInitializer {
    private static KeyBinding toggleKeyBinding;
    private static boolean isEnabled = false;
    private int cooldownTicks = 0;
    private int taskState = 0;

    @Override
    public void onInitializeClient() {
        toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.lavaassistant.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) return;

            while (toggleKeyBinding.wasPressed()) {
                isEnabled = !isEnabled;
                if (isEnabled) {
                    client.player.sendMessage(Text.literal("§a[LavaAssistant] Enabled").styled(style -> style.withColor(net.minecraft.util.Formatting.GREEN)), true);
                } else {
                    client.player.sendMessage(Text.literal("§cLavaAssistant Disabled").styled(style -> style.withColor(net.minecraft.util.Formatting.RED)), true);
                }
            }

            if (!isEnabled) {
                taskState = 0;
                return;
            }

            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }

            runAssistantLogic(client);
        });
    }

    private void runAssistantLogic(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return;

        if (taskState == 1) {
            selectItem(player, Items.BUCKET);
            if (client.interactionManager != null) {
                client.interactionManager.interactItem(player, Hand.MAIN_HAND);
            }
            taskState = 0;
            cooldownTicks = 10;
            return;
        }

        Entity target = null;
        double minDistance = 5.0;

        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            if (entity instanceof LivingEntity && (entity instanceof HostileEntity || entity instanceof PlayerEntity)) {
                double dist = player.distanceTo(entity);
                if (dist <= minDistance) {
                    Vec3d lookDir = player.getRotationVector();
                    Vec3d toEntity = entity.getPos().subtract(player.getPos()).normalize();
                    if (lookDir.dotProduct(toEntity) > 0.3) {
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
                if (client.interactionManager != null) {
                    client.interactionManager.interactBlock(
                            player,
                            Hand.MAIN_HAND,
                            new BlockHitResult(new Vec3d(targetPos.getX(), targetPos.getY(), targetPos.getZ()), Direction.UP, targetPos, false)
                    );
                    taskState = 1;
                    cooldownTicks = 8;
                }
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

    private void selectItem(PlayerEntity player, net.minecraft.item.Item item) {
        int slot = findItemInHotbar(player, item);
        if (slot != -1) {
            player.getInventory().selectedSlot = slot;
        }
    }
}

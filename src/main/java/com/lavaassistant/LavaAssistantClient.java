package com.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class LavaAssistantClient implements ClientModInitializer {
    private static KeyBinding toggleKeyBinding;
    private static boolean isEnabled = false;

    private static final double TARGET_RANGE = 5.0;
    private static final double AIM_DOT_THRESHOLD = 0.3;
    private static final int PLACE_COOLDOWN_TICKS = 4;
    private static final int SCOOP_COOLDOWN_TICKS = 1;

    private int cooldownTicks = 0;
    private int taskState = 0;
    private int usedSlot = -1;
    private BlockPos usedPos = null;

    @Override
    public void onInitializeClient() {
        toggleKeyBinding = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onClientTick);
    }

    private void onClientTick(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        while (toggleKeyBinding.wasPressed()) {
            isEnabled = !isEnabled;
            client.player.sendMessage(
                    Text.literal("LavaAssistant: " + (isEnabled ? "ON" : "OFF")),
                    true
            );
        }

        if (!isEnabled) {
            resetState();
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        runAssistantLogic(client);
    }

    private void runAssistantLogic(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.interactionManager == null) return;

        if (taskState == 1) {
            if (usedSlot != -1 && usedPos != null) {
                setHotbarSlot(player, usedSlot);
                client.interactionManager.interactBlock(
                        player,
                        Hand.MAIN_HAND,
                        new BlockHitResult(
                                new Vec3d(usedPos.getX() + 0.5, usedPos.getY() + 0.9, usedPos.getZ() + 0.5),
                                Direction.UP,
                                usedPos,
                                false
                        )
                );
            }
            resetState();
            cooldownTicks = SCOOP_COOLDOWN_TICKS;
            return;
        }

        PlayerEntity target = findNearestPlayer(client, player);
        if (target == null) return;

        int lavaSlot = findItemInHotbar(player, Items.LAVA_BUCKET);
        if (lavaSlot == -1) return;

        setHotbarSlot(player, lavaSlot);

        BlockPos feetPos = target.getBlockPos();
        BlockPos groundPos = feetPos.down();

        client.interactionManager.interactBlock(
                player,
                Hand.MAIN_HAND,
                new BlockHitResult(
                        new Vec3d(groundPos.getX() + 0.5, groundPos.getY() + 1.0, groundPos.getZ() + 0.5),
                        Direction.UP,
                        groundPos,
                        false
                )
        );

        usedSlot = lavaSlot;
        usedPos = feetPos;
        taskState = 1;
        cooldownTicks = PLACE_COOLDOWN_TICKS;
    }

    private void resetState() {
        taskState = 0;
        usedSlot = -1;
        usedPos = null;
    }

    private void setHotbarSlot(ClientPlayerEntity player, int slot) {
        try {
            Field field = net.minecraft.entity.player.PlayerInventory.class.getDeclaredField("selectedSlot");
            field.setAccessible(true);
            field.setInt(player.getInventory(), slot);
        } catch (Exception e) {
            // Fallback
        }
        if (player.networkHandler != null) {
            player.networkHandler.sendPacket(new net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket(slot));
        }
    }

    private PlayerEntity findNearestPlayer(MinecraftClient client, ClientPlayerEntity player) {
        PlayerEntity closest = null;
        double closestDistance = TARGET_RANGE;

        Vec3d playerPos = new Vec3d(player.getX(), player.getY(), player.getZ());
        Vec3d lookDirection = player.getRotationVector();

        for (Entity entity : client.world.getEntities()) {
            if (entity == player) continue;
            if (!(entity instanceof PlayerEntity livingTarget)) continue;
            if (!livingTarget.isAlive() || livingTarget.isSpectator()) continue;

            double distance = player.distanceTo(livingTarget);
            if (distance > closestDistance) continue;

            Vec3d targetPos = new Vec3d(livingTarget.getX(), livingTarget.getY(), livingTarget.getZ());
            Vec3d towardsEntity = targetPos.subtract(playerPos).normalize();
            
            if (lookDirection.dotProduct(towardsEntity) <= AIM_DOT_THRESHOLD) continue;

            closest = livingTarget;
            closestDistance = distance;
        }

        return closest;
    }

    private int findItemInHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }
}

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
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
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
    private int actionTimer = 0;
    private int taskStep = 0; // 0: Idle, 1: Placed, Waiting to pickup
    private BlockPos targetBlockPos = null;
    private int originalSlot = 0;

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
                taskStep = 0;
                actionTimer = 0;
            }

            if (!toggleState) return;

            // Handle timer delay for placing and picking up smoothly
            if (actionTimer > 0) {
                actionTimer--;

                // Step 2: Scoop the lava back up using an empty bucket after a short delay (~0.7 seconds / 14 ticks)
                if (taskStep == 1 && actionTimer == 0 && targetBlockPos != null) {
                    int emptyBucketSlot = -1;
                    for (int i = 0; i < 9; i++) {
                        if (client.player.getInventory().getStack(i).isOf(Items.BUCKET)) {
                            emptyBucketSlot = i;
                            break;
                        }
                    }

                    if (emptyBucketSlot != -1) {
                        originalSlot = client.player.getInventory().selectedSlot;
                        client.player.getInventory().selectedSlot = emptyBucketSlot;
                        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(emptyBucketSlot));

                        if (client.interactionManager != null) {
                            Vec3d hitVec = new Vec3d(targetBlockPos.getX() + 0.5, targetBlockPos.getY(), targetBlockPos.getZ() + 0.5);
                            BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetBlockPos, false);
                            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                        }

                        // Switch back to original slot safely
                        client.player.getInventory().selectedSlot = originalSlot;
                        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                    }
                    taskStep = 0; // Reset back to ready state
                    targetBlockPos = null;
                }
                return;
            }

            // Find nearest enemy player within 4.5 blocks range for precise placement
            PlayerEntity target = null;
            double minDistance = 4.5;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double dist = client.player.squaredDistanceTo(player);
                if (dist < minDistance * minDistance) {
                    target = player;
                    minDistance = Math.sqrt(dist);
                }
            }

            if (target != null && taskStep == 0) {
                // Find Lava Bucket in hotbar
                int lavaSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (client.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET)) {
                        lavaSlot = i;
                        break;
                    }
                }

                if (lavaSlot != -1) {
                    originalSlot = client.player.getInventory().selectedSlot;
                    
                    // Switch to lava slot and sync packet with server
                    client.player.getInventory().selectedSlot = lavaSlot;
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(lavaSlot));

                    // Exact block right under the enemy's feet
                    targetBlockPos = target.getBlockPos().down();

                    // Smooth rotation packet to prevent anti-cheat flags
                    double dx = targetBlockPos.getX() + 0.5 - client.player.getX();
                    double dy = (targetBlockPos.getY() + 0.5) - client.player.getEyeY();
                    double dz = targetBlockPos.getZ() + 0.5 - client.player.getZ();
                    double distXZ = Math.sqrt(dx * dx + dz * dz);

                    float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                            client.player.getX(), client.player.getY(), client.player.getZ(),
                            targetYaw, targetPitch, client.player.isOnGround()
                    ));

                    // Place the lava precisely on the block under their feet
                    if (client.interactionManager != null) {
                        Vec3d hitVec = new Vec3d(targetBlockPos.getX() + 0.5, targetBlockPos.getY() + 1.0, targetBlockPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetBlockPos, false);
                        
                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                    }

                    // Switch back to original slot (sword/item) immediately after placing
                    client.player.getInventory().selectedSlot = originalSlot;
                    client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));

                    taskStep = 1; // Mark as placed, now wait to pick it back up
                    actionTimer = 22; // Delay before attempting to scoop it back with an empty bucket
                }
            }
        });

        // Your original ON/OFF HUD Pop-up rendering logic (completely untouched)
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
}

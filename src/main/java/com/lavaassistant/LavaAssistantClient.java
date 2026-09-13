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
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {

    private static boolean toggleState = false;
    private static long popupShowUntil = 0;
    private static final long POPUP_DURATION_MS = 1500;

    private static KeyBinding toggleKey;
    private int taskTimer = 0;
    private int postActionCooldown = 0; // 2-3 seconds ka gap taaki spam na ho
    private int stage = 0; // 0: Ready to place, 1: Waiting to scoop back
    private BlockPos placedPos = null;

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
                stage = 0;
                taskTimer = 0;
                postActionCooldown = 0;
                placedPos = null;
            }

            if (!toggleState) return;

            // Cooldown handling
            if (postActionCooldown > 0) {
                postActionCooldown--;
            }

            // Handle pickup timer (~12 ticks / 0.6 seconds)
            if (taskTimer > 0) {
                taskTimer--;
                if (stage == 1 && taskTimer == 0 && placedPos != null) {
                    if (client.player.getMainHandStack().isOf(Items.BUCKET) && client.interactionManager != null) {
                        double dx = placedPos.getX() + 0.5 - client.player.getX();
                        double dy = (placedPos.getY() + 0.5) - client.player.getEyeY();
                        double dz = placedPos.getZ() + 0.5 - client.player.getZ();
                        double distXZ = Math.sqrt(dx * dx + dz * dz);

                        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                        client.player.setYaw(targetYaw);
                        client.player.setPitch(targetPitch);

                        client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
                    }
                    stage = 0;
                    placedPos = null;
                    postActionCooldown = 50; // Lava wapas uthane ke baad 50 ticks (~2.5 seconds) ka gap
                }
                return;
            }

            // Agar cooldown chal raha hai toh naya lava mat dalo
            if (postActionCooldown > 0) {
                return;
            }

            // Sirf tabhi chale jab haath me Lava Bucket ho
            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // Target enemy detection (4 blocks range)
            PlayerEntity target = null;
            double minDistSq = 16.0;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double distSq = client.player.squaredDistanceTo(player);
                if (distSq < minDistSq) {
                    target = player;
                    minDistSq = distSq;
                }
            }

            // Agar enemy aag se jal raha hai, toh lava mat dalo
            if (target != null && target.isOnFire()) {
                return;
            }

            if (target != null && stage == 0 && client.interactionManager != null) {
                placedPos = target.getBlockPos().down();

                // PANI CHECK: Agar target ke pairon ke niche pani ya block hai, toh skip karo
                if (client.world.getBlockState(placedPos).isOf(net.minecraft.block.Blocks.WATER) || 
                    !client.world.getBlockState(placedPos).isAir()) {
                    return;
                }

                double dx = placedPos.getX() + 0.5 - client.player.getX();
                double dy = (placedPos.getY() + 0.5) - client.player.getEyeY();
                double dz = placedPos.getZ() + 0.5 - client.player.getZ();
                double distXZ = Math.sqrt(dx * dx + dz * dz);

                float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                client.player.setYaw(targetYaw);
                client.player.setPitch(targetPitch);

                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        targetYaw, targetPitch, client.player.isOnGround()
                ));

                // Place lava
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

                stage = 1;
                taskTimer = 12; // 12 ticks baad wapas uthane ke liye timer start
            }
        });

        // Tera original ON/OFF HUD Pop-up rendering logic (Bilkul untouched)
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

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
    private int cooldown = 0;

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
            }

            if (!toggleState) return;

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            // Target enemy detection (within 5 blocks range for anti-cheat sync)
            PlayerEntity target = null;
            double minDistance = 5.0;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double dist = client.player.squaredDistanceTo(player);
                if (dist < minDistance * minDistance) {
                    target = player;
                    minDistance = Math.sqrt(dist);
                }
            }

            if (target != null) {
                // Check hotbar for Lava Bucket
                int lavaSlot = -1;
                for (int i = 0; i < 9; i++) {
                    if (client.player.getInventory().getStack(i).isOf(Items.LAVA_BUCKET)) {
                        lavaSlot = i;
                        break;
                    }
                }

                if (lavaSlot != -1) {
                    int previousSlot = client.player.getInventory().selectedSlot;
                    client.player.getInventory().selectedSlot = lavaSlot;

                    BlockPos targetPos = target.getBlockPos().down(); // Feet level placement

                    // Anti-Cheat Bypass: Calculate look angles to target and send silent rotation packet
                    double dx = targetPos.getX() + 0.5 - client.player.getX();
                    double dy = (targetPos.getY() + 0.5) - client.player.getEyeY();
                    double dz = targetPos.getZ() + 0.5 - client.player.getZ();
                    double distXZ = Math.sqrt(dx * dx + dz * dz);

                    float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                    // Send rotation packet to bypass strict view checks of Grim/Vulcan anti-cheats
                    client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.AndOnGround(
                            client.player.getX(), client.player.getY(), client.player.getZ(),
                            targetYaw, targetPitch, client.player.isOnGround(), false
                    ));

                    if (client.interactionManager != null) {
                        Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                        
                        client.interactionManager.interactItem(client.player, net.minecraft.util.Hand.MAIN_HAND);
                        cooldown = 30; // Increased delay to avoid rate-limit flags by anti-cheat
                    }

                    client.player.getInventory().selectedSlot = previousSlot;
                }
            }
        });

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

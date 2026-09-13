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
import net.minecraft.util.hit.HitResult;
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
    private int stage = 0; // 0: Ready, 1: Placed, waiting to scoop
    private BlockPos targetPos = null;

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
                actionTicks = 0;
                targetPos = null;
            }

            if (!toggleState) return;

            // Timer management for placing & fast scooping
            if (actionTicks > 0) {
                actionTicks--;

                // Stage 1: Scoop the lava back up using the empty bucket quickly
                if (stage == 1 && actionTicks == 0 && targetPos != null) {
                    if (client.player.getMainHandStack().isOf(Items.BUCKET) && client.interactionManager != null) {
                        Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                    }
                    stage = 0;
                    targetPos = null;
                }
                return;
            }

            // Only run if player is manually holding a Lava Bucket
            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // Find closest enemy within 4 blocks range
            PlayerEntity target = null;
            double minDistSq = 16.0; // 4 blocks squared

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double distSq = client.player.squaredDistanceTo(player);
                if (distSq < minDistSq) {
                    target = player;
                    minDistSq = distSq;
                }
            }

            if (target != null && stage == 0 && client.interactionManager != null) {
                // Precise target block under enemy's feet
                targetPos = target.getBlockPos().down();

                // Look packet to ensure server registers the placement accurately at target position
                double dx = targetPos.getX() + 0.5 - client.player.getX();
                double dy = (targetPos.getY() + 0.5) - client.player.getEyeY();
                double dz = targetPos.getZ() + 0.5 - client.player.getZ();
                double distXZ = Math.sqrt(dx * dx + dz * dz);

                float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        targetYaw, targetPitch, client.player.isOnGround()
                ));

                // Instant placement using interactItem / block raycast match
                Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 1.0, targetPos.getZ() + 0.5);
                BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

                stage = 1;
                actionTicks = 10; // Fast pickup delay (10 ticks / ~0.5 seconds) so it scoops right back without getting stuck
            }
        });

        // Untouched original ON/OFF HUD pop-up
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

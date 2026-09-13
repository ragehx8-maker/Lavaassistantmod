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
    private int taskTimer = 0;
    private int postActionCooldown = 0;
    private int stage = 0;
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

            if (postActionCooldown > 0) {
                postActionCooldown--;
                return;
            }

            // Pickup & Scoop Timer Handler
            if (taskTimer > 0) {
                taskTimer--;
                if (taskTimer == 0 && placedPos != null && client.interactionManager != null) {
                    if (client.player.getMainHandStack().isOf(Items.BUCKET)) {
                        double dx = placedPos.getX() + 0.5 - client.player.getX();
                        double dy = (placedPos.getY() + 0.5) - client.player.getEyeY();
                        double dz = placedPos.getZ() + 0.5 - client.player.getZ();
                        double distXZ = Math.sqrt(dx * dx + dz * dz);

                        client.player.setYaw((float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0));
                        client.player.setPitch((float) (-Math.toDegrees(Math.atan2(dy, distXZ))));

                        Vec3d hitVec = new Vec3d(placedPos.getX() + 0.5, placedPos.getY() + 0.5, placedPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, placedPos, false);
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                    }
                    stage = 0;
                    placedPos = null;
                    postActionCooldown = 30;
                }
                return;
            }

            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // STRICT NEAREST PLAYER TARGETING: Saare players me se jo sabse paas hai sirf use choose karega
            PlayerEntity target = null;
            double minDistSq = 16.0; // Max range 4 blocks (4 * 4 = 16)

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double distSq = client.player.squaredDistanceTo(player);
                if (distSq < minDistSq) {
                    target = player;
                    minDistSq = distSq; // Sabse choti distance wala player target ban jayega
                }
            }

            if (target == null) return;

            // SAFETY: Do not place lava if target is already burning
            if (target.isOnFire()) return;

            BlockPos targetPos = target.getBlockPos().down();

            // SAFETY: Do not place on water or non-air blocks
            if (client.world.getBlockState(targetPos).isOf(net.minecraft.block.Blocks.WATER) || 
                !client.world.getBlockState(targetPos).isAir()) {
                return;
            }

            if (client.interactionManager != null) {
                placedPos = targetPos;

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

                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

                stage = 1;
                taskTimer = 12;
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

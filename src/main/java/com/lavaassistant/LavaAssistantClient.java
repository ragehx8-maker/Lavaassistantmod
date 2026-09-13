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
    private int currentTask = 0; // 0: Idle, 1: Waiting to pickup lava back
    private BlockPos lastPlacedPos = null;

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
                currentTask = 0;
                taskTimer = 0;
                lastPlacedPos = null;
            }

            if (!toggleState) return;

            // Handle fast timer delay for picking the lava back up
            if (taskTimer > 0) {
                taskTimer--;
                
                // Fast pickup check
                if (currentTask == 1 && taskTimer == 0 && lastPlacedPos != null) {
                    if (client.player.getMainHandStack().isOf(Items.BUCKET) && client.interactionManager != null) {
                        Vec3d hitVec = new Vec3d(lastPlacedPos.getX() + 0.5, lastPlacedPos.getY(), lastPlacedPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, lastPlacedPos, false);
                        client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
                    }
                    currentTask = 0;
                    lastPlacedPos = null;
                }
                return;
            }

            // Sirf tabhi chale jab tune manually haath me Lava Bucket pakdi ho
            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // Aas-paas ke enemy player ko detect karna (4 blocks range)
            PlayerEntity target = null;
            double minDistance = 4.0;

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;
                double dist = client.player.squaredDistanceTo(player);
                if (dist < minDistance * minDistance) {
                    target = player;
                    minDistance = Math.sqrt(dist);
                }
            }

            if (target != null && currentTask == 0 && client.interactionManager != null) {
                lastPlacedPos = target.getBlockPos().down();

                // Anti-cheat rotation bypass packet
                double dx = lastPlacedPos.getX() + 0.5 - client.player.getX();
                double dy = (lastPlacedPos.getY() + 0.5) - client.player.getEyeY();
                double dz = lastPlacedPos.getZ() + 0.5 - client.player.getZ();
                double distXZ = Math.sqrt(dx * dx + dz * dz);

                float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

                client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                        client.player.getX(), client.player.getY(), client.player.getZ(),
                        targetYaw, targetPitch, client.player.isOnGround()
                ));

                // Lava place karna
                client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);

                currentTask = 1;
                taskTimer = 8; // Fast delay: sirf 8 ticks (~0.4 sec) baad turant wapas utha lega!
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

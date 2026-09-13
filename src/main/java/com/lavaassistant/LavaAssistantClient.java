package com.example.lavaassistant;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {

    private static boolean toggleState = false;
    private static long popupShowUntil = 0;
    private static final long POPUP_DURATION_MS = 1500;

    private static KeyBinding toggleKey;
    private int actionTicks = 0;
    private int cooldownTicks = 0;
    private int stage = 0; // 0: Searching to place, 1: Waiting to scoop back
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
                cooldownTicks = 0;
                targetPos = null;
            }

            if (!toggleState) return;

            if (cooldownTicks > 0) {
                cooldownTicks--;
                return;
            }

            // 1. FULLY AUTO PICKUP: Agar haath me empty bucket hai aur pairon ke niche lava hai
            if (stage == 1 && client.player.getMainHandStack().isOf(Items.BUCKET)) {
                if (actionTicks > 0) {
                    actionTicks--;
                    if (actionTicks == 0 && targetPos != null) {
                        silentLookAt(client, targetPos);

                        Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                        client.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
                        client.player.swingHand(Hand.MAIN_HAND);

                        stage = 0;
                        targetPos = null;
                        cooldownTicks = 12;

                        // Auto switch back to Sword
                        switchToSword(client);
                    }
                }
                return;
            }

            // Fallback manual bucket detection incase stage missed
            if (stage == 0 && client.player.getMainHandStack().isOf(Items.BUCKET)) {
                BlockPos floorPos = client.player.getBlockPos().down();
                if (client.world.getBlockState(floorPos).isOf(net.minecraft.block.Blocks.LAVA)) {
                    targetPos = floorPos;
                    stage = 1;
                    actionTicks = 5;
                    return;
                }
            }

            // Must hold Lava Bucket for automated placement
            if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return;
            }

            // 2. FULLY AUTO TARGETING: Health Priority + Ping + Prediction + LoS + Safety
            PlayerEntity bestTarget = null;
            BlockPos predictedPos = null;
            double bestScore = Double.MAX_VALUE;

            double pingTicks = 0.0;
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null) {
                pingTicks = entry.getLatency() / 50.0;
            }

            for (PlayerEntity player : client.world.getPlayers()) {
                if (player == client.player) continue;

                double distSq = client.player.squaredDistanceTo(player);
                if (distSq > 16.0) continue; // 4 blocks range

                // Safety: Skip burning targets
                if (player.isOnFire()) continue;

                // FOV Check: Front 80 degrees
                Vec3d toPlayer = player.getPos().subtract(client.player.getPos()).normalize();
                Vec3d lookDir = client.player.getRotationVector();
                if (lookDir.dotProduct(toPlayer) < 0.25) continue;

                // Prediction based on velocity & ping
                double predictionFactor = 1.0 + (pingTicks * 0.5);
                Vec3d futurePos = player.getPos().add(player.getVelocity().multiply(predictionFactor));
                BlockPos pPos = BlockPos.ofFloored(futurePos).down();

                if (!client.world.getBlockState(pPos).isAir()) {
                    pPos = player.getBlockPos().down();
                }

                // Line of Sight Check
                if (!hasLineOfSight(client, pPos)) continue;

                // Water / Non-air Safety Check
                if (client.world.getBlockState(pPos).isOf(net.minecraft.block.Blocks.WATER) || 
                    !client.world.getBlockState(pPos).isAir()) {
                    continue;
                }

                // Health & Distance Scoring
                double healthWeight = (20.0 - player.getHealth()) * 0.5;
                double score = distSq - healthWeight;

                if (score < bestScore) {
                    bestScore = score;
                    bestTarget = player;
                    predictedPos = pPos;
                }
            }

            if (bestTarget != null && predictedPos != null) {
                targetPos = predictedPos;
                silentLookAt(client, targetPos);

                // FULLY AUTO PLACE: Send packet to place lava instantly
                client.getNetworkHandler().sendPacket(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 0, client.player.getYaw(), client.player.getPitch()));
                client.player.swingHand(Hand.MAIN_HAND);

                stage = 1;
                actionTicks = 10; // Wait 10 ticks for lava to settle before auto pickup
            } else {
                targetPos = null;
            }
        });

        // Visual ESP Box
        WorldRenderEvents.LAST.register(context -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !toggleState || targetPos == null) return;

            Vec3d cameraPos = client.gameRenderer.getCamera().getPos();
            Matrix4f matrix = context.matrixStack().peek().getPositionMatrix();
            Tessellator tessellator = Tessellator.getInstance();
            BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.LINES, VertexFormats.POSITION_COLOR);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            Box box = new Box(targetPos).offset(-cameraPos.x, -cameraPos.y, -cameraPos.z);
            WorldRenderer.drawBox(context.matrixStack(), buffer, box.minX, box.minY, box.minZ, box.maxX, box.maxY, box.maxZ, 1.0f, 0.3f, 0.0f, 0.8f);
            BufferRenderer.drawWithGlobalProgram(buffer.end());

            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
        });

        // Toggle HUD Indicator
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (System.currentTimeMillis() < popupShowUntil) {
                MinecraftClient client = MinecraftClient.getInstance();

                String msg = toggleState ? "ON (Fully Auto)" : "OFF";
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

    private void silentLookAt(MinecraftClient client, BlockPos pos) {
        if (client.player == null || client.getNetworkHandler() == null) return;

        double dx = pos.getX() + 0.5 - client.player.getX();
        double dy = (pos.getY() + 0.5) - client.player.getEyeY();
        double dz = pos.getZ() + 0.5 - client.player.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                targetYaw, targetPitch, client.player.isOnGround()
        ));
    }

    private boolean hasLineOfSight(MinecraftClient client, BlockPos pos) {
        if (client.world == null || client.player == null) return false;
        Vec3d eyes = client.player.getEyePos();
        Vec3d targetCenter = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        
        BlockHitResult result = client.world.raycast(new RaycastContext(
                eyes, targetCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                client.player
        ));
        return result.getType() == HitResult.Type.MISS || result.getBlockPos().equals(pos);
    }

    private void switchToSword(MinecraftClient client) {
        if (client.player == null) return;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).getItem() instanceof SwordItem) {
                client.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }
}

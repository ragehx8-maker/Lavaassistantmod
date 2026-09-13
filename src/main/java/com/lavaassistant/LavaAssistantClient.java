package com.example.lavaassistant;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.*;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LavaAssistantClient implements ClientModInitializer {

    private static boolean toggleState = false;
    private static long popupShowUntil = 0;
    private static final long POPUP_DURATION_MS = 1500;

    private static KeyBinding toggleKey;
    
    // Professional Configuration: Target-specific hit tracking
    public static final int TARGET_HIT_COUNT = 4;
    private final Map<UUID, Integer> playerHitCounts = new HashMap<>();

    private int state = 0; // 0 = Idle, 1 = Placed (Waiting to scoop back)
    private int timer = 0;
    private int cooldown = 0;
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
                state = 0;
                timer = 0;
                cooldown = 0;
                playerHitCounts.clear();
                targetPos = null;
            }

            if (!toggleState) return;

            if (cooldown > 0) {
                cooldown--;
                return;
            }

            // Fail-safe cleanup: Agar player mar gaya ya world change ho gaya
            if (client.player.isDead()) {
                state = 0;
                targetPos = null;
                playerHitCounts.clear();
                return;
            }

            // STATE 1: Auto-Scoop back lava after ~0.75 seconds (15 ticks)
            if (state == 1) {
                timer++;
                if (timer >= 15 && targetPos != null) {
                    // Check if empty bucket exists in hotbar
                    if (!client.player.getMainHandStack().isOf(Items.BUCKET)) {
                        switchToEmptyBucket(client);
                    }

                    if (client.player.getMainHandStack().isOf(Items.BUCKET)) {
                        silentLookAt(client, targetPos);

                        Vec3d hitVec = new Vec3d(targetPos.getX() + 0.5, targetPos.getY() + 0.5, targetPos.getZ() + 0.5);
                        BlockHitResult hitResult = new BlockHitResult(hitVec, Direction.UP, targetPos, false);
                        client.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, 0));
                        client.player.swingHand(Hand.MAIN_HAND);
                    }

                    state = 0;
                    timer = 0;
                    targetPos = null;
                    cooldown = 10;
                }
            }
        });

        // PROFESSIONAL HIT-TRIGGERED SYSTEM (UUID-Based Target Tracking)
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player == null || !toggleState || client.getNetworkHandler() == null) return ActionResult.PASS;
            if (player != client.player) return ActionResult.PASS;
            
            if (state != 0 || !client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                return ActionResult.PASS;
            }

            if (entity instanceof PlayerEntity targetPlayer) {
                if (targetPlayer == client.player) return ActionResult.PASS;

                // SAFETY CHECK: Target already burning check (INTACT)
                if (targetPlayer.isOnFire()) {
                    return ActionResult.PASS;
                }

                // Range Check (Max 4 blocks squared = 16.0)
                double distSq = client.player.squaredDistanceTo(targetPlayer);
                if (distSq > 16.0) return ActionResult.PASS;

                // Target-Specific Hit Counter using UUID map
                UUID targetUuid = targetPlayer.getUuid();
                int currentHits = playerHitCounts.getOrDefault(targetUuid, 0) + 1;
                
                if (currentHits < TARGET_HIT_COUNT) {
                    playerHitCounts.put(targetUuid, currentHits);
                    return ActionResult.PASS;
                }

                // Reset hit count for this specific target once threshold is met
                playerHitCounts.put(targetUuid, 0);

                BlockPos pPos = targetPlayer.getBlockPos().down();
                if (client.world.getBlockState(pPos).isOf(net.minecraft.block.Blocks.LAVA)) {
                    return ActionResult.PASS;
                }

                // Line of Sight & Fluid/Solid Safety Checks
                if (!hasLineOfSight(client, pPos)) return ActionResult.PASS;
                if (client.world.getBlockState(pPos).isOf(net.minecraft.block.Blocks.WATER) || 
                    (!client.world.getBlockState(pPos).isAir() && !client.world.getBlockState(pPos).isOf(net.minecraft.block.Blocks.LAVA))) {
                    return ActionResult.PASS;
                }

                targetPos = pPos;
                silentLookAt(client, targetPos);

                // Place Lava Packet
                BlockPos supportPos = targetPos.down();
                Vec3d hitVec = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5);
                BlockHitResult blockHitResult = new BlockHitResult(hitVec, Direction.UP, supportPos, false);
                
                client.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, blockHitResult, 0));
                client.player.swingHand(Hand.MAIN_HAND);

                // Move to State 1 to scoop it back shortly
                state = 1;
                timer = 0;
            }

            return ActionResult.PASS;
        });

        // Visual ESP Box Outline
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

        // Toggle Status HUD Popup
        HudRenderCallback.EVENT.register((drawContext, tickDelta) -> {
            if (System.currentTimeMillis() < popupShowUntil) {
                MinecraftClient client = MinecraftClient.getInstance();

                String msg = toggleState ? "ON (Every " + TARGET_HIT_COUNT + " Hits - Pro)" : "OFF";
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

    private void switchToEmptyBucket(MinecraftClient client) {
        if (client.player == null) return;
        for (int i = 0; i < 9; i++) {
            if (client.player.getInventory().getStack(i).getItem().equals(Items.BUCKET)) {
                client.player.getInventory().selectedSlot = i;
                break;
            }
        }
    }
}

package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean enabled = false;

    private static KeyBinding toggleKey;
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lavaassistant", "main"));

    private static final double RANGE = 4.5;
    private static final int PICKUP_TIMEOUT_TICKS = 25;
    private static final int PLACE_COOLDOWN_TICKS = 8;

    private BlockPos placedPos = null;
    private int ticksSincePlace = 0;
    private int cooldown = 0;

    @Override
    public void onInitializeClient() {
        System.out.println("[LavaAssistant] CLIENT LOADED");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                CATEGORY
        ));

        HudRenderCallback.EVENT.register((ctx, tickCounter) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (enabled && mc.player != null && mc.currentScreen == null) {
                ctx.drawText(mc.textRenderer, Text.literal("§a[AutoLava: ON]"), 10, 10, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                placedPos = null;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fEnabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null
                    || mc.interactionManager == null || mc.currentScreen != null) return;

            if (cooldown > 0) cooldown--;

            Item item = mc.player.getMainHandStack().getItem();

            // ---------- THUNDERHACK STYLE: HEAD TURNS IN 3RD PERSON, 1ST PERSON STABLE ----------
            if (item == Items.LAVA_BUCKET) {
                if (cooldown > 0) return;

                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                // Motion prediction
                Vec3d vel = target.getVelocity();
                Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
                Vec3d pred = targetPos.add(vel.x * 1.8, vel.y * 0.5, vel.z * 1.8);
                BlockPos feet = BlockPos.ofFloored(pred.x, target.getY(), pred.z);
                BlockPos supportPos = feet.down();

                if (mc.world.getBlockState(supportPos).isAir()) return;
                FluidState existingFs = mc.world.getFluidState(feet);
                if (existingFs.isOf(Fluids.LAVA)) return;

                Vec3d aim = Vec3d.ofCenter(supportPos);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                // Calculate target look angles
                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                // 1. Send look packet to server for anti-cheat and other players
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                // 2. Turn the head visually in 3rd person without shaking the 1st person screen camera
                mc.player.setHeadYaw(targetYaw);

                // 3. Direct accurate block placement
                Vec3d hitVec = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5);
                BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, supportPos, false);

                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
                mc.player.swingHand(Hand.MAIN_HAND);

                placedPos = feet;
                ticksSincePlace = 0;
                cooldown = PLACE_COOLDOWN_TICKS;
                return;
            }

            // ---------- THUNDERHACK STYLE AUTO PICKUP ----------
            if (item == Items.BUCKET && placedPos != null) {
                ticksSincePlace++;

                if (ticksSincePlace > PICKUP_TIMEOUT_TICKS) {
                    placedPos = null;
                    return;
                }

                FluidState fs = mc.world.getFluidState(placedPos);
                if (!fs.isOf(Fluids.LAVA) || !fs.isStill()) return;

                Vec3d aim = Vec3d.ofCenter(placedPos);
                if (mc.player.getEyePos().distanceTo(aim) >RANGE) return;

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                mc.player.setHeadYaw(targetYaw);

                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);

                placedPos = null;
                cooldown = PLACE_COOLDOWN_TICKS;
            }
        });
    }

    private PlayerEntity findNearestPlayer(MinecraftClient mc, double range) {
        PlayerEntity nearest = null;
        double best = range * range;
        for (PlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player || p.isSpectator()) continue;
            double d = mc.player.squaredDistanceTo(p);
            if (d <= best) {
                best = d;
                nearest = p.isAlive() ? p : null;
            }
        }
        return nearest;
    }
}

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
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean enabled = false;

    private static KeyBinding toggleKey;
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lavaassistant", "main"));

    private static final double RANGE = 4.5;
    private static final int COOLDOWN_TICKS = 8;
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

            // ---------- 1. AIMASSIST ONLY WHEN HOLDING LAVA BUCKET (MANUAL PLACE) ----------
            if (item == Items.LAVA_BUCKET) {
                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null) return;

                // Fire Check: Agar enemy pehle se jal raha hai toh aimassist trigger mat karo
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                // Motion prediction for moving enemies
                Vec3d vel = target.getVelocity();
                Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
                Vec3d pred = targetPos.add(vel.x * 1.8, vel.y * 0.5, vel.z * 1.8);
                BlockPos feet = BlockPos.ofFloored(pred.x, target.getY(), pred.z);

                Vec3d aim = Vec3d.ofBottomCenter(feet);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                // Smooth aimassist rotation towards enemy feet (Character won't freeze or lag)
                float currentYaw = mc.player.getYaw();
                float currentPitch = mc.player.getPitch();

                float newYaw = smoothAngle(currentYaw, targetYaw, 60.0f);
                float newPitch = MathHelper.clamp(currentPitch + MathHelper.wrapDegrees(targetPitch - currentPitch) * 0.5f, -90.0f, 90.0f);

                mc.player.setYaw(newYaw);
                mc.player.setPitch(newPitch);
                mc.player.setHeadYaw(newYaw);
                return;
            }

            // ---------- 2. AUTO PICKUP WHEN HOLDING EMPTY BUCKET ----------
            if (item == Items.BUCKET) {
                if (cooldown > 0) return;

                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null) return;

                BlockPos feet = BlockPos.ofFloored(target.getX(), target.getY(), target.getZ());
                FluidState fs = mc.world.getFluidState(feet);
                if (!fs.isOf(Fluids.LAVA) || !fs.isStill()) return;

                Vec3d aim = Vec3d.ofCenter(feet);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                // Smoothly aim at the placed lava block
                float currentYaw = mc.player.getYaw();
                float currentPitch = mc.player.getPitch();

                float newYaw = smoothAngle(currentYaw, targetYaw, 60.0f);
                float newPitch = MathHelper.clamp(currentPitch + MathHelper.wrapDegrees(targetPitch - currentPitch) * 0.5f, -90.0f, 90.0f);

                mc.player.setYaw(newYaw);
                mc.player.setPitch(newPitch);
                mc.player.setHeadYaw(newYaw);

                if (Math.abs(MathHelper.wrapDegrees(targetYaw - newYaw)) < 10.0f) {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.swingHand(Hand.MAIN_HAND);
                    cooldown = COOLDOWN_TICKS;
                }
            }
        });
    }

    private float smoothAngle(float current, float target, float maxDelta) {
        float diff = MathHelper.wrapDegrees(target - current);
        if (diff > maxDelta) diff = maxDelta;
        if (diff < -maxDelta) diff = -maxDelta;
        return current + diff;
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

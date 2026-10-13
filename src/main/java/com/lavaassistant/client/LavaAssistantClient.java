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
import net.minecraft.util.hit.HitResult;
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
    private static final int PICKUP_TIMEOUT_TICKS = 25;
    private static final int PLACE_COOLDOWN_TICKS = 10;

    private BlockPos placedPos = null;
    private int ticksSincePlace = 0;
    private int cooldown = 0;

    // ThunderHack Style Smooth Rotation State Variables
    private boolean isAnimating = false;
    private int animStep = 0;
    private float origYaw, origTargetYaw;
    private float origPitch, origTargetPitch;
    private boolean isPlacingAction = true; // true = Place Lava, false = Pickup Lava
    private BlockPos targetBlockPos = null;

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
                isAnimating = false;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fEnabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null
                    || mc.interactionManager == null || mc.currentScreen != null) return;

            if (cooldown > 0) cooldown--;

            // ---------- THUNDERHACK STYLE SMOOTH ANIMATION HANDLER ----------
            if (isAnimating) {
                animStep++;
                
                // Interpolate smoothly towards target angles over 3 steps
                float progress = Math.min(1.0f, animStep / 3.0f);
                float currentYaw = smoothAngle(origYaw, origTargetYaw, 40.0f * animStep);
                float currentPitch = origPitch + (origTargetPitch - origPitch) * progress;

                mc.player.setYaw(currentYaw);
                mc.player.setPitch(currentPitch);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            currentYaw, currentPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                // On step 3, perform the action (Place or Pickup)
                if (animStep == 3) {
                    if (isPlacingAction && targetBlockPos != null) {
                        HitResult hr = mc.player.raycast(RANGE, 1.0f, false);
                        if (hr.getType() == HitResult.Type.BLOCK) {
                            BlockHitResult bhr = (BlockHitResult) hr;
                            BlockPos willPlace = bhr.getBlockPos().offset(bhr.getSide());
                            if (willPlace.equals(targetBlockPos) || willPlace.equals(targetBlockPos.down())) {
                                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                                mc.player.swingHand(Hand.MAIN_HAND);
                                placedPos = targetBlockPos;
                                ticksSincePlace = 0;
                                cooldown = PLACE_COOLDOWN_TICKS;
                            }
                        }
                    } else if (!isPlacingAction && placedPos != null) {
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        placedPos = null;
                        cooldown = PLACE_COOLDOWN_TICKS;
                    }
                }

                // Finish animation after 5 ticks and restore original view smoothly
                if (animStep >= 5) {
                    mc.player.setYaw(origYaw);
                    mc.player.setPitch(origPitch);
                    isAnimating = false;
                }
                return;
            }

            Item item = mc.player.getMainHandStack().getItem();

            // ---------- TRIGGER: AUTO LAVA PLACE ----------
            if (item == Items.LAVA_BUCKET) {
                if (cooldown > 0) return;

                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                Vec3d vel = target.getVelocity();
                Vec3d targetPos = new Vec3d(target.getX(), target.getY(), target.getZ());
                Vec3d pred = targetPos.add(vel.x * 2.2, vel.y * 1.0, vel.z * 2.2);
                BlockPos feet = BlockPos.ofFloored(pred.x, target.getY(), pred.z);

                FluidState existingFs = mc.world.getFluidState(feet);
                if (existingFs.isOf(Fluids.LAVA)) return;

                Vec3d aim = Vec3d.ofBottomCenter(feet);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                // Setup Thunderhack style smooth animation
                origYaw = mc.player.getYaw();
                origPitch = mc.player.getPitch();

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                origTargetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                origTargetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                isPlacingAction = true;
                targetBlockPos = feet;
                animStep = 0;
                isAnimating = true;
                return;
            }

            // ---------- TRIGGER: AUTO PICKUP ----------
            if (item == Items.BUCKET && placedPos != null) {
                ticksSincePlace++;

                if (ticksSincePlace > PICKUP_TIMEOUT_TICKS) {
                    placedPos = null;
                    return;
                }

                FluidState fs = mc.world.getFluidState(placedPos);
                if (!fs.isOf(Fluids.LAVA) || !fs.isStill()) return;

                Vec3d aim = Vec3d.ofCenter(placedPos);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                origYaw = mc.player.getYaw();
                origPitch = mc.player.getPitch();

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                origTargetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                origTargetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                isPlacingAction = false;
                animStep = 0;
                isAnimating = true;
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

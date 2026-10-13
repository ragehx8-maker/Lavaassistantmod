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
import net.minecraft.registry.tag.FluidTags;
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
    private static final int PLACE_COOLDOWN_TICKS = 25;

    private BlockPos placedPos = null;
    private BlockPos waterPlacedPos = null;
    private int ticksSincePlace = 0;
    private int waterTicksSincePlace = 0;
    private int cooldown = 0;
    private int checkTimer = 0;

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
                waterPlacedPos = null;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fEnabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null
                    || mc.interactionManager == null || mc.currentScreen != null) return;

            if (cooldown > 0) cooldown--;

            // ---------- AUTO WATER / SELF-EXTINGUISH (Highest Priority when burning) ----------
            if (mc.player.isOnFire() || mc.player.getFireTicks() > 0) {
                Item mainItem = mc.player.getMainHandStack().getItem();
                
                // Agar empty bucket haath mein hai aur pani pehle rakha tha, toh use wapas utha lo
                if (mainItem == Items.BUCKET && waterPlacedPos != null) {
                    waterTicksSincePlace++;
                    if (waterTicksSincePlace > PICKUP_TIMEOUT_TICKS) {
                        waterPlacedPos = null;
                    } else {
                        FluidState fs = mc.world.getFluidState(waterPlacedPos);
                        if (fs.isOf(Fluids.WATER) && fs.isStill()) {
                            Vec3d aim = Vec3d.ofCenter(waterPlacedPos);
                            if (mc.player.getEyePos().distanceTo(aim) <= RANGE) {
                                float oldYaw = mc.player.getYaw();
                                float oldPitch = mc.player.getPitch();

                                Vec3d eye = mc.player.getEyePos();
                                double dx = aim.x - eye.x;
                                double dy = aim.y - eye.y;
                                double dz = aim.z - eye.z;
                                double horizontal = Math.sqrt(dx * dx + dz * dz);
                                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                                float smoothedYaw = smoothAngle(oldYaw, targetYaw, 45.0f);
                                float smoothedPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

                                mc.player.setYaw(smoothedYaw);
                                mc.player.setPitch(smoothedPitch);
                                if (mc.getNetworkHandler() != null) {
                                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                                            smoothedYaw, smoothedPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                                }

                                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                                mc.player.swingHand(Hand.MAIN_HAND);

                                mc.player.setYaw(oldYaw);
                                mc.player.setPitch(oldPitch);
                                waterPlacedPos = null;
                                return;
                            }
                        }
                    }
                }

                // Agar water bucket haath mein hai, toh pairo ke neeche silent place karo
                if (mainItem == Items.WATER_BUCKET) {
                    BlockPos feet = mc.player.getBlockPos();
                    FluidState fs = mc.world.getFluidState(feet);
                    if (!fs.isOf(Fluids.WATER)) {
                        Vec3d aim = Vec3d.ofBottomCenter(feet);
                        float oldYaw = mc.player.getYaw();
                        float oldPitch = mc.player.getPitch();

                        Vec3d eye = mc.player.getEyePos();
                        double dx = aim.x - eye.x;
                        double dy = aim.y - eye.y;
                        double dz = aim.z - eye.z;
                        double horizontal = Math.sqrt(dx * dx + dz * dz);
                        float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                        float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                        float smoothedYaw = smoothAngle(oldYaw, targetYaw, 45.0f);
                        float smoothedPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

                        mc.player.setYaw(smoothedYaw);
                        mc.player.setPitch(smoothedPitch);
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                                    smoothedYaw, smoothedPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                        }

                        HitResult hr = mc.player.raycast(RANGE, 1.0f, false);
                        if (hr.getType() == HitResult.Type.BLOCK) {
                            BlockHitResult bhr = (BlockHitResult) hr;
                            BlockPos willPlace = bhr.getBlockPos().offset(bhr.getSide());
                            if (willPlace.equals(feet)) {
                                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                                mc.player.swingHand(Hand.MAIN_HAND);
                                waterPlacedPos = feet;
                                waterTicksSincePlace = 0;
                            }
                        }

                        mc.player.setYaw(oldYaw);
                        mc.player.setPitch(oldPitch);
                        return;
                    }
                } else {
                    // Hotbar se automatically water bucket dhund kar switch karo
                    int waterSlot = -1;
                    for (int i = 0; i < 9; i++) {
                        if (mc.player.getInventory().getStack(i).getItem() == Items.WATER_BUCKET) {
                            waterSlot = i;
                            break;
                        }
                    }
                    if (waterSlot != -1) {
                        mc.player.getInventory().selectedSlot = waterSlot;
                        return;
                    }
                }
            }

            Item item = mc.player.getMainHandStack().getItem();

            // ---------- VULCAN & GRIM BYPASS: SILENT SMOOTH AUTO LAVA PLACE ----------
            if (item == Items.LAVA_BUCKET) {
                if (cooldown > 0) return;

                checkTimer++;
                if (checkTimer < 3) return;
                checkTimer = 0;

                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null || !target.isOnGround()) return;
                if (target.isTouchingWater()) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                double speed = target.getVelocity().horizontalLength();
                if (speed > 0.08) return; 

                BlockPos feet = target.getBlockPos();

                FluidState existingFs = mc.world.getFluidState(feet);
                if (existingFs.isOf(Fluids.LAVA)) return;
                if (nearWater(mc, feet)) return;

                Vec3d aim = Vec3d.ofBottomCenter(feet);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                float oldYaw = mc.player.getYaw();
                float oldPitch = mc.player.getPitch();

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                float smoothedYaw = smoothAngle(oldYaw, targetYaw, 45.0f);
                float smoothedPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

                mc.player.setYaw(smoothedYaw);
                mc.player.setPitch(smoothedPitch);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            smoothedYaw, smoothedPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                HitResult hr = mc.player.raycast(RANGE, 1.0f, false);
                if (hr.getType() == HitResult.Type.BLOCK) {
                    BlockHitResult bhr = (BlockHitResult) hr;
                    BlockPos willPlace = bhr.getBlockPos().offset(bhr.getSide());
                    if (willPlace.equals(feet)) {
                        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                        mc.player.swingHand(Hand.MAIN_HAND);
                        placedPos = feet;
                        ticksSincePlace = 0;
                        cooldown = PLACE_COOLDOWN_TICKS;
                    }
                }

                mc.player.setYaw(oldYaw);
                mc.player.setPitch(oldPitch);
                return;
            }

            // ---------- VULCAN & GRIM BYPASS: SILENT SMOOTH AUTO PICKUP ----------
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

                float oldYaw = mc.player.getYaw();
                float oldPitch = mc.player.getPitch();

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                float smoothedYaw = smoothAngle(oldYaw, targetYaw, 45.0f);
                float smoothedPitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

                mc.player.setYaw(smoothedYaw);
                mc.player.setPitch(smoothedPitch);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            smoothedYaw, smoothedPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);

                mc.player.setYaw(oldYaw);
                mc.player.setPitch(oldPitch);

                placedPos = null;
                cooldown = PLACE_COOLDOWN_TICKS;
            }
        });
    }

    private float smoothAngle(float current, float target, float maxDelta) {
        float diff = MathHelper.wrapDegrees(target - current);
        if (diff > maxDelta) diff = maxDelta;
        if (diff < -maxDelta) diff = -maxDelta;
        return current + diff;
    }

    private boolean nearWater(MinecraftClient mc, BlockPos pos) {
        BlockPos[] check = {
                pos, pos.up(), pos.down(),
                pos.north(), pos.south(), pos.east(), pos.west()
        };
        for (BlockPos p : check) {
            if (mc.world.getFluidState(p).isIn(FluidTags.WATER)) return true;
        }
        return false;
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

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
    private static final int PLACE_COOLDOWN_TICKS = 15;

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

            // ---------- SILENT AUTO LAVA PLACE ----------
            if (item == Items.LAVA_BUCKET) {
                if (cooldown > 0) return;

                PlayerEntity target = findNearestPlayer(mc, RANGE);
                if (target == null || !target.isOnGround()) return;
                if (target.isTouchingWater()) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                Vec3d vel = target.getVelocity();
                Vec3d pred = new Vec3d(target.getX(), target.getY(), target.getZ()).add(vel.x * 1.5, 0, vel.z * 1.5);
                BlockPos feet = BlockPos.ofFloored(pred.x, target.getY() + 0.01, pred.z);

                FluidState existingFs = mc.world.getFluidState(feet);
                if (existingFs.isOf(Fluids.LAVA)) return;
                if (nearWater(mc, feet)) return;

                Vec3d aim = new Vec3d(pred.x, feet.getY(), pred.z);
                if (mc.player.getEyePos().distanceTo(aim) > RANGE) return;

                // Save original client view angles so screen doesn't move at all
                float oldYaw = mc.player.getYaw();
                float oldPitch = mc.player.getPitch();

                // Calculate target look angles
                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                // Set temporarily for packet and raycast
                mc.player.setYaw(yaw);
                mc.player.setPitch(pitch);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
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

                // Instantly restore original client view so screen remains completely normal
                mc.player.setYaw(oldYaw);
                mc.player.setPitch(oldPitch);
                return;
            }

            // ---------- SILENT AUTO PICKUP ----------
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

                // Save original client view angles
                float oldYaw = mc.player.getYaw();
                float oldPitch = mc.player.getPitch();

                Vec3d eye = mc.player.getEyePos();
                double dx = aim.x - eye.x;
                double dy = aim.y - eye.y;
                double dz = aim.z - eye.z;
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                float pitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                mc.player.setYaw(yaw);
                mc.player.setPitch(pitch);
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                }

                mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                mc.player.swingHand(Hand.MAIN_HAND);

                // Restore original client view
                mc.player.setYaw(oldYaw);
                mc.player.setPitch(oldPitch);

                placedPos = null;
                cooldown = PLACE_COOLDOWN_TICKS;
            }
        });
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

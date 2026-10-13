package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

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
                ctx.drawText(mc.textRenderer, Text.literal("§a[HitLava: ON]"), 10, 10, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                placedPos = null;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fHit-Lava Enabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null
                    || mc.interactionManager == null || mc.currentScreen != null) return;

            if (cooldown > 0) cooldown--;

            // ---------- HIT-LAVA (SILENT PLACEMENT ON TARGET) ----------
            if (cooldown == 0 && mc.targetedEntity instanceof PlayerEntity target) {
                if (target == mc.player || target.isSpectator()) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return; // Fire Check

                if (mc.player.distanceTo(target) <= RANGE) {
                    int lavaSlot = findItemInHotbar(mc, Items.LAVA_BUCKET);
                    if (lavaSlot != -1) {
                        int originalSlot = getSelectedSlot(mc.player.getInventory());

                        // Motion prediction for enemy feet (Fixed: using new Vec3d safely)
                        Vec3d vel = target.getVelocity();
                        Vec3d pred = new Vec3d(target.getX() + vel.x * 1.5, target.getY(), target.getZ() + vel.z * 1.5);
                        BlockPos feet = BlockPos.ofFloored(pred.getX(), target.getY(), pred.getZ());
                        BlockPos supportPos = feet.down();

                        if (mc.world.getBlockState(supportPos).isAir()) return;
                        FluidState existingFs = mc.world.getFluidState(feet);
                        if (existingFs.isOf(Fluids.LAVA)) return;

                        Vec3d aim = Vec3d.ofCenter(supportPos);

                        Vec3d eye = mc.player.getEyePos();
                        double dx = aim.x - eye.x;
                        double dy = aim.y - eye.y;
                        double dz = aim.z - eye.z;
                        double horizontal = Math.sqrt(dx * dx + dz * dz);
                        float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
                        float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

                        // 1. Send silent look packet
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                                    targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                        }
                        mc.player.setHeadYaw(targetYaw);

                        // 2. Silent hotbar slot switch to lava bucket
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(lavaSlot));
                        }

                        // 3. Place lava silently
                        Vec3d hitVec = new Vec3d(supportPos.getX() + 0.5, supportPos.getY() + 1.0, supportPos.getZ() + 0.5);
                        BlockHitResult bhr = new BlockHitResult(hitVec, Direction.UP, supportPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);

                        // 4. Instantly switch back to original slot
                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                        }

                        placedPos = feet;
                        ticksSincePlace = 0;
                        cooldown = PLACE_COOLDOWN_TICKS;
                    }
                }
            }

            // ---------- SILENT AUTO PICKUP BUCKET ----------
            if (placedPos != null) {
                ticksSincePlace++;
                if (ticksSincePlace > PICKUP_TIMEOUT_TICKS) {
                    placedPos = null;
                    return;
                }

                FluidState fs = mc.world.getFluidState(placedPos);
                if (fs.isOf(Fluids.LAVA) && fs.isStill()) {
                    int bucketSlot = findItemInHotbar(mc, Items.BUCKET);
                    if (bucketSlot != -1) {
                        int originalSlot = getSelectedSlot(mc.player.getInventory());

                        Vec3d aim = Vec3d.ofCenter(placedPos);
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

                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(bucketSlot));
                        }

                        BlockHitResult bhr = new BlockHitResult(aim, Direction.UP, placedPos, false);
                        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);

                        if (mc.getNetworkHandler() != null) {
                            mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                        }

                        placedPos = null;
                        cooldown = PLACE_COOLDOWN_TICKS;
                    }
                }
            }
        });
    }

    private int findItemInHotbar(MinecraftClient mc, net.minecraft.item.Item targetItem) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(targetItem)) {
                return i;
            }
        }
        return -1;
    }

    private int getSelectedSlot(PlayerInventory inventory) {
        try {
            Field field = PlayerInventory.class.getDeclaredField("selectedSlot");
            field.setAccessible(true);
            return field.getInt(inventory);
        } catch (Exception e) {
            try {
                Field field = PlayerInventory.class.getDeclaredField("field_7545");
                field.setAccessible(true);
                return field.getInt(inventory);
            } catch (Exception ex) {
                return 0;
            }
        }
    }
}

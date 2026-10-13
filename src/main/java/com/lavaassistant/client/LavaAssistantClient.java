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
    
    // State machine variables for anti-cheat bypass
    private int taskState = 0; // 0: Idle, 1: Prepare & Rotate, 2: Place, 3: Cleanup
    private int taskTimer = 0;
    private int originalSlot = 0;
    private BlockPos targetFeetPos = null;
    private BlockPos placedLavaPos = null;
    
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
                ctx.drawText(mc.textRenderer, Text.literal("§a[ProLava: ON]"), 10, 10, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                taskState = 0;
                placedLavaPos = null;
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fPro-Lava Enabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null
                    || mc.interactionManager == null || mc.currentScreen != null) return;

            if (cooldown > 0) cooldown--;

            // ---------- MULTI-TICK STATE MACHINE FOR AUTO LAVA ----------
            if (taskState > 0) {
                executeTask(mc);
                return;
            }

            if (cooldown > 0) return;

            // Trigger when targeting a player
            if (mc.targetedEntity instanceof PlayerEntity target) {
                if (target == mc.player || target.isSpectator()) return;
                if (target.isOnFire() || target.getFireTicks() > 0) return;

                if (mc.player.distanceTo(target) <= RANGE) {
                    int lavaSlot = findItemInHotbar(mc, Items.LAVA_BUCKET);
                    if (lavaSlot != -1) {
                        Vec3d vel = target.getVelocity();
                        Vec3d pred = new Vec3d(target.getX() + vel.x * 1.5, target.getY(), target.getZ() + vel.z * 1.5);
                        BlockPos feet = BlockPos.ofFloored(pred.getX(), target.getY(), pred.getZ());
                        BlockPos supportPos = feet.down();

                        if (mc.world.getBlockState(supportPos).isAir()) return;
                        FluidState existingFs = mc.world.getFluidState(feet);
                        if (existingFs.isOf(Fluids.LAVA)) return;

                        // Start multi-tick task safely
                        originalSlot = getSelectedSlot(mc.player.getInventory());
                        targetFeetPos = supportPos;
                        taskState = 1;
                        taskTimer = 0;
                    }
                }
            }

            // ---------- AUTO PICKUP BUCKET (SMART CHECK) ----------
            if (placedLavaPos != null && taskState == 0) {
                FluidState fs = mc.world.getFluidState(placedLavaPos);
                if (fs.isOf(Fluids.LAVA) && fs.isStill()) {
                    int bucketSlot = findItemInHotbar(mc, Items.BUCKET);
                    if (bucketSlot != -1) {
                        originalSlot = getSelectedSlot(mc.player.getInventory());
                        targetFeetPos = placedLavaPos;
                        taskState = 10; // Pickup state
                        taskTimer = 0;
                    }
                }
            }
        });
    }

    private void executeTask(MinecraftClient mc) {
        taskTimer++;

        // Placement Sequence
        if (taskState >= 1 && taskState <= 3) {
            int lavaSlot = findItemInHotbar(mc, Items.LAVA_BUCKET);
            if (lavaSlot == -1) {
                taskState = 0;
                return;
            }

            Vec3d aim = Vec3d.ofCenter(targetFeetPos);
            Vec3d eye = mc.player.getEyePos();
            double dx = aim.x - eye.x;
            double dy = aim.y - eye.y;
            double dz = aim.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

            if (taskTimer == 1) {
                // Tick 1: Silent look & slot switch
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(lavaSlot));
                }
                mc.player.setHeadYaw(targetYaw);
            } else if (taskTimer == 2) {
                // Tick 2: Place block interaction
                BlockHitResult bhr = new BlockHitResult(aim, Direction.UP, targetFeetPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            } else if (taskTimer >= 3) {
                // Tick 3: Switch back to original slot
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                }
                placedLavaPos = targetFeetPos.up();
                taskState = 0;
                cooldown = 20; // 1 second cooldown
            }
        }

        // Pickup Sequence
        if (taskState >= 10 && taskState <= 12) {
            int bucketSlot = findItemInHotbar(mc, Items.BUCKET);
            if (bucketSlot == -1) {
                taskState = 0;
                return;
            }

            Vec3d aim = Vec3d.ofCenter(targetFeetPos);
            Vec3d eye = mc.player.getEyePos();
            double dx = aim.x - eye.x;
            double dy = aim.y - eye.y;
            double dz = aim.z - eye.z;
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            float targetYaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;
            float targetPitch = (float) -(MathHelper.atan2(dy, horizontal) * 180.0 / Math.PI);

            if (taskTimer == 1) {
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(
                            targetYaw, targetPitch, mc.player.isOnGround(), mc.player.horizontalCollision));
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(bucketSlot));
                }
                mc.player.setHeadYaw(targetYaw);
            } else if (taskTimer == 2) {
                BlockHitResult bhr = new BlockHitResult(aim, Direction.UP, targetFeetPos, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, bhr);
            } else if (taskTimer >= 3) {
                if (mc.getNetworkHandler() != null) {
                    mc.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(originalSlot));
                }
                placedLavaPos = null;
                taskState = 0;
                cooldown = 20;
            }
        }
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

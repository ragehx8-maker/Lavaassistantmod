package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class LavaAssistantClient implements ClientModInitializer {
    public static boolean enabled = false;

    private static KeyBinding toggleKey;
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lavaassistant", "main"));

    private BlockPos targetPos;
    private int state = 0;
    private int delayTicks = 0;
    private boolean keyWasPressed = false;

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
                ctx.drawText(mc.textRenderer, Text.literal("§a[Auto Lava: ON]"), 10, 10, 0xFFFFFFFF, true);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                resetState();
                if (mc.player != null) {
                    mc.player.sendMessage(Text.literal(enabled
                            ? "§a[LavaAssistant] §fAuto Lava Enabled!"
                            : "§c[LavaAssistant] §fDisabled!"), false);
                }
            }

            if (!enabled || mc.player == null || mc.world == null || mc.currentScreen != null)
                return;

            boolean pressed = toggleKey.isPressed();

            if (pressed && !keyWasPressed && state == 0) {
                if (!hasItemInHotbar(mc, Items.LAVA_BUCKET)) {
                    keyWasPressed = pressed;
                    return;
                }

                if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
                    Entity entity = ((EntityHitResult) mc.crosshairTarget).getEntity();
                    if (entity instanceof PlayerEntity player) {
                        // 1. FIRE CHECK: Agar enemy pehle se jal raha hai toh trigger mat karo
                        if (player.isOnFire() || player.getFireTicks() > 0) {
                            keyWasPressed = pressed;
                            return;
                        }

                        targetPos = predictLandingPos(mc, player);
                        if (targetPos != null && isValidPlacement(mc, targetPos))
                            state = 1;
                        else
                            targetPos = null;
                    }
                }
            }

            keyWasPressed = pressed;

            if (state != 0) {
                if (delayTicks > 0) {
                    delayTicks--;
                } else {
                    mc.execute(() -> step(mc));
                }
            }
        });
    }

    private void step(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null)
            return;

        switch (state) {
            case 1 -> {
                if (selectItemFromHotbar(mc, Items.LAVA_BUCKET)) {
                    state = 2;
                    delayTicks = 1;
                } else {
                    resetState();
                }
            }
            case 2 -> {
                if (placeLava(mc)) {
                    state = 3;
                    delayTicks = 6; // Lava settle hone ka wait taaki bucket se wapas utha sakein
                } else {
                    resetState();
                }
            }
            case 3 -> {
                pickLavaBackUp(mc);
                resetState();
            }
        }
    }

    private BlockPos predictLandingPos(MinecraftClient mc, PlayerEntity player) {
        Vec3d pos = player.getPos();
        Vec3d velocity = player.getVelocity();

        if (player.isOnGround() && velocity.horizontalLength() < 0.1)
            return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));

        double x = pos.x, y = pos.y, z = pos.z;
        double vx = velocity.x, vy = velocity.y, vz = velocity.z;

        for (int i = 0; i < 20; i++) {
            vy = (vy - 0.08D) * 0.98D;
            vx *= 0.91D;
            vz *= 0.91D;
            x += vx;
            y += vy;
            z += vz;

            BlockPos below = new BlockPos((int) Math.floor(x), (int) Math.floor(y) - 1, (int) Math.floor(z));
            if (!mc.world.getBlockState(below).isAir())
                return new BlockPos((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }

        return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));
    }

    private boolean isValidPlacement(MinecraftClient mc, BlockPos pos) {
        BlockPos below = pos.down();
        if (!mc.world.getBlockState(below).isSolidBlock(mc.world, below))
            return false;
        if (!mc.world.getBlockState(pos).isReplaceable() && !mc.world.getBlockState(pos).isAir())
            return false;
        
        // 2. WATER CHECK: Agar wahan pani hai toh placement block kar do taaki obsidian na bane
        if (mc.world.getFluidState(pos).isOf(Fluids.WATER) || mc.world.getFluidState(pos.down()).isOf(Fluids.WATER))
            return false;

        return mc.player.getPos().squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 25.0D;
    }

    private boolean placeLava(MinecraftClient mc) {
        if (targetPos == null)
            return false;

        if (!mc.player.getMainHandStack().isOf(Items.LAVA_BUCKET))
            return false;

        lookAt(mc, targetPos.down().toCenterPos().add(0.0D, 0.5D, 0.0D));
        ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (result.isAccepted()) {
            mc.player.swingHand(Hand.MAIN_HAND);
            return true;
        }
        return false;
    }

    private void pickLavaBackUp(MinecraftClient mc) {
        if (targetPos == null)
            return;

        if (!hasItemInHotbar(mc, Items.BUCKET))
            return;

        if (selectItemFromHotbar(mc, Items.BUCKET)) {
            lookAt(mc, targetPos.toCenterPos());
            ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            if (result.isAccepted())
                mc.player.swingHand(Hand.MAIN_HAND);
        }
    }

    private void lookAt(MinecraftClient mc, Vec3d target) {
        Vec3d delta = target.subtract(mc.player.getEyePos());
        double horizontal = delta.horizontalLength();
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        mc.player.setPitch((float) (-Math.toDegrees(Math.atan2(delta.y, horizontal))));
    }

    private boolean hasItemInHotbar(MinecraftClient mc, net.minecraft.item.Item targetItem) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(targetItem))
                return true;
        }
        return false;
    }

    private boolean selectItemFromHotbar(MinecraftClient mc, net.minecraft.item.Item targetItem) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(targetItem)) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private void resetState() {
        targetPos = null;
        state = 0;
        delayTicks = 0;
        keyWasPressed = false;
    }
}

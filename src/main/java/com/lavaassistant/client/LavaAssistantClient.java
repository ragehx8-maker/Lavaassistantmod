package com.lavaassistant.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.block.Blocks;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;

public class LavaAssistantClient implements ClientModInitializer {
    private static KeyBinding triggerKey;
    private static final KeyBinding.Category CATEGORY =
            KeyBinding.Category.create(Identifier.of("lavaassistant", "main"));

    private BlockPos targetPos;
    private int state = 0;
    private int delayTicks = 0;
    private int originalSlot = 0;

    @Override
    public void onInitializeClient() {
        System.out.println("[LavaAssistant] CLIENT LOADED");

        triggerKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.trigger",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MinecraftClient mc = MinecraftClient.getInstance();

            if (mc.player == null || mc.world == null || mc.currentScreen != null)
                return;

            // Agar state machine chal rahi hai, toh steps execute karo
            if (state != 0) {
                if (delayTicks > 0) {
                    delayTicks--;
                } else {
                    mc.execute(() -> step(mc));
                }
                return;
            }

            // KEY PRESS TRIGGER: Jab R dabayenge tabhi action start hoga
            if (triggerKey.wasPressed()) {
                // Pehle check karo ki kahin aas-paas un-picked lava toh nahi pada? Agar hai toh pehle wahi utha lo!
                if (hasItemInHotbar(mc, Items.BUCKET)) {
                    BlockPos nearbyLava = findNearbyLava(mc, 4.0);
                    if (nearbyLava != null) {
                        targetPos = nearbyLava;
                        originalSlot = getSelectedSlot(mc.player.getInventory());
                        state = 3;
                        delayTicks = 0;
                        return;
                    }
                }

                if (!hasItemInHotbar(mc, Items.LAVA_BUCKET)) {
                    mc.player.sendMessage(Text.literal("§c[LavaAssistant] Lava Bucket not found in hotbar!"), true);
                    return;
                }

                if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.ENTITY) {
                    Entity entity = ((EntityHitResult) mc.crosshairTarget).getEntity();
                    if (entity instanceof PlayerEntity player) {
                        if (player == mc.player || player.isSpectator() || !player.isAlive())
                            return;

                        // FIRE CHECK
                        if (player.isOnFire() || player.getFireTicks() > 0) {
                            mc.player.sendMessage(Text.literal("§e[LavaAssistant] Target is already on fire!"), true);
                            return;
                        }

                        targetPos = predictLandingPos(mc, player);
                        if (targetPos != null && isValidPlacement(mc, player, targetPos)) {
                            originalSlot = getSelectedSlot(mc.player.getInventory());
                            state = 1; // Lava placement sequence start!
                        } else {
                            mc.player.sendMessage(Text.literal("§c[LavaAssistant] Invalid spot (Water or unsafe)!"), true);
                        }
                    } else {
                        mc.player.sendMessage(Text.literal("§c[LavaAssistant] Crosshair not on a player!"), true);
                    }
                }
            }
        });
    }

    private void step(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.interactionManager == null)
            return;

        switch (state) {
            case 1 -> {
                // Hotbar se lava bucket select karo
                if (selectItemFromHotbar(mc, Items.LAVA_BUCKET)) {
                    state = 2;
                    delayTicks = 1;
                } else {
                    resetState();
                }
            }
            case 2 -> {
                // Lava place karo
                if (placeLava(mc)) {
                    state = 25;
                    delayTicks = 1; // Server packet safety delay
                } else {
                    setSelectedSlot(mc.player.getInventory(), originalSlot);
                    resetState();
                }
            }
            case 25 -> {
                // TURANT sword par wapas switch ho jao taaki fight disturb na ho
                setSelectedSlot(mc.player.getInventory(), originalSlot);
                state = 3;
                delayTicks = 8; // Lava spread aur settle hone ka wait
            }
            case 3 -> {
                // Lava ko wapas bucket mein uthao
                if (targetPos != null) {
                    FluidState fs = mc.world.getFluidState(targetPos);
                    if (fs.isOf(Fluids.LAVA) || mc.world.getBlockState(targetPos).getBlock() == Blocks.LAVA) {
                        if (selectItemFromHotbar(mc, Items.BUCKET)) {
                            lookAt(mc, targetPos.toCenterPos());
                            ActionResult result = mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                            if (result.isAccepted()) {
                                mc.player.swingHand(Hand.MAIN_HAND);
                            }
                        }
                    }
                }
                setSelectedSlot(mc.player.getInventory(), originalSlot); // Switch back to sword after pickup
                resetState();
            }
        }
    }

    private BlockPos findNearbyLava(MinecraftClient mc, double range) {
        BlockPos playerPos = mc.player.getBlockPos();
        int r = (int) Math.ceil(range);
        for (int x = -r; x <= r; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -r; z <= r; z++) {
                    BlockPos p = playerPos.add(x, y, z);
                    FluidState fs = mc.world.getFluidState(p);
                    if ((fs.isOf(Fluids.LAVA) || mc.world.getBlockState(p).getBlock() == Blocks.LAVA) &&
                        mc.player.getEntityPos().squaredDistanceTo(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) <= range * range) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    private BlockPos predictLandingPos(MinecraftClient mc, PlayerEntity player) {
        Vec3d pos = player.getEntityPos();
        Vec3d velocity = player.getVelocity();

        if (player.isOnGround() && velocity.horizontalLength() < 0.1)
            return new BlockPos((int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z));

        double x = pos.x, y = pos.y, z = pos.z;
        double vx = velocity.x, vy = velocity.y, vz = velocity.z;

        for (int i = 0; i < 15; i++) {
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

    private boolean isValidPlacement(MinecraftClient mc, PlayerEntity player, BlockPos pos) {
        BlockPos below = pos.down();
        if (!mc.world.getBlockState(below).isSolidBlock(mc.world, below))
            return false;
        if (!mc.world.getBlockState(pos).isReplaceable() && !mc.world.getBlockState(pos).isAir())
            return false;
        
        // STRICT WATER CHECK: Pani par lava na gire
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos checkPos = pos.add(dx, dy, dz);
                    FluidState fs = mc.world.getFluidState(checkPos);
                    if (fs.isOf(Fluids.WATER) || fs.isOf(Fluids.FLOWING_WATER) ||
                        mc.world.getBlockState(checkPos).getBlock() == Blocks.WATER) {
                        return false;
                    }
                }
            }
        }

        // SELF-PROTECTION CHECK: Apne aap ko jalane se bachane ke liye
        Vec3d playerPos = mc.player.getEntityPos();
        if (playerPos.squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 2.0D) {
            return false;
        }

        return player.getEntityPos().squaredDistanceTo(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) < 25.0D;
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
                setSelectedSlot(mc.player.getInventory(), i);
                return true;
            }
        }
        return false;
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

    private void setSelectedSlot(PlayerInventory inventory, int slot) {
        try {
            Field field = PlayerInventory.class.getDeclaredField("selectedSlot");
            field.setAccessible(true);
            field.setInt(inventory, slot);
        } catch (Exception e) {
            try {
                Field field = PlayerInventory.class.getDeclaredField("field_7545");
                field.setAccessible(true);
                field.setInt(inventory, slot);
            } catch (Exception ex) {
                // ignore
            }
        }
    }

    private void resetState() {
        targetPos = null;
        state = 0;
        delayTicks = 0;
    }
                        }

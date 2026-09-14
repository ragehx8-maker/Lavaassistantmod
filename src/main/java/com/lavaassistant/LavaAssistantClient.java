package com.example.lavaassistant;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public final class AutoLavaModule {

    private AutoLavaModule() {}

    public static boolean toggled = false;

    private enum State {
        IDLE,
        WAITING_FOR_LAVA,
        WAITING_FOR_PICKUP
    }

    private static State state = State.IDLE;

    private static BlockPos placedLavaPos;
    private static int originalSlot = -1;
    private static int stateTicks = 0;
    private static int pickupAttempts = 0;
    private static long nextActionTime = 0L;

    // Advanced Settings
    private static final int PICKUP_DELAY_TICKS = 6;
    private static final int CONFIRM_TIMEOUT_TICKS = 20;
    private static final int MAX_PICKUP_ATTEMPTS = 2;

    private static final double MAX_RANGE_SQUARED = 36.0D;
    private static final double MIN_SELF_DISTANCE_SQUARED = 9.0D;
    private static final long ACTION_DELAY_MS = 100L;

    public static void toggle() {
        toggled = !toggled;
        if (!toggled) {
            resetState(MinecraftClient.getInstance());
        }
    }

    public static void onPlayerTick(MinecraftClient client) {
        if (!toggled || client == null || client.player == null || client.world == null) {
            resetState(client);
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now < nextActionTime) {
            return;
        }

        if (state == State.WAITING_FOR_LAVA) {
            handleLavaPickup(client);
            return;
        }

        if (state == State.WAITING_FOR_PICKUP) {
            handlePickupConfirmation(client);
            return;
        }

        if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
            return;
        }

        Entity target = getTargetEntity(client);
        if (!(target instanceof PlayerEntity targetPlayer)) {
            return;
        }

        if (!isValidTarget(client, targetPlayer)) {
            return;
        }

        if (targetPlayer.isOnFire()) {
            return;
        }

        if (targetPlayer.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            return;
        }

        // Dynamic Prediction Ticks based on Ping/Latency
        int predictionTicks = getDynamicPredictionTicks(client, targetPlayer);

        Vec3d predictedPosition = targetPlayer
                .getPos()
                .add(targetPlayer.getVelocity().multiply(predictionTicks));

        BlockPos lavaPos = BlockPos.ofFloored(predictedPosition);
        BlockPos supportPos = lavaPos.down();

        if (!isValidPlacement(client, lavaPos, supportPos)) {
            return;
        }

        if (!isValidRaycast(client, supportPos)) {
            return;
        }

        originalSlot = client.player.getInventory().selectedSlot;

        if (!performPlacement(client, supportPos)) {
            resetState(client);
            return;
        }

        placedLavaPos = lavaPos;
        state = State.WAITING_FOR_LAVA;
        stateTicks = 0;
        pickupAttempts = 0;
        nextActionTime = now + ACTION_DELAY_MS;
    }

    private static boolean isValidTarget(MinecraftClient client, PlayerEntity target) {
        if (target == client.player) return false;
        if (!target.isAlive() || target.isSpectator()) return false;
        
        // Native Team/Friend Check (Agar teammate hai toh ignore karega)
        if (target.isTeammate(client.player)) return false;

        return client.player.squaredDistanceTo(target) <= MAX_RANGE_SQUARED;
    }

    private static int getDynamicPredictionTicks(MinecraftClient client, PlayerEntity target) {
        if (client.getNetworkHandler() == null) return 2;
        PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
        if (entry == null) return 2;
        
        int ping = entry.getLatency();
        // High ping hone par lookahead ticks badha do taaki prediction accurate rahe
        if (ping > 150) return 3;
        if (ping > 250) return 4;
        return 2;
    }

    private static boolean isValidPlacement(MinecraftClient client, BlockPos lavaPos, BlockPos supportPos) {
        if (!client.world.getBlockState(lavaPos).isReplaceable()) return false;
        if (client.world.getBlockState(supportPos).isAir()) return false;
        if (client.world.getFluidState(lavaPos).getFluid() == Fluids.LAVA) return false;

        Vec3d lavaCenter = Vec3d.ofCenter(lavaPos);
        if (client.player.getPos().squaredDistanceTo(lavaCenter) < MIN_SELF_DISTANCE_SQUARED) {
            return false;
        }

        return true;
    }

    private static Entity getTargetEntity(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit == null || hit.getType() != HitResult.Type.ENTITY) return null;
        return ((EntityHitResult) hit).getEntity();
    }

    private static boolean isValidRaycast(MinecraftClient client, BlockPos supportPos) {
        Vec3d eyePos = client.player.getEyePos();
        Vec3d topFaceCenter = new Vec3d(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D);

        BlockHitResult result = client.world.raycast(new RaycastContext(
                eyePos, topFaceCenter,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                client.player
        ));

        return result.getType() == HitResult.Type.BLOCK && result.getBlockPos().equals(supportPos);
    }

    private static boolean performPlacement(MinecraftClient client, BlockPos supportPos) {
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) return false;

        Vec3d hitPos = new Vec3d(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D);
        sendSilentLook(client, hitPos);

        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, supportPos, false);
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        if (!result.isAccepted()) return false;

        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private static void handleLavaPickup(MinecraftClient client) {
        if (placedLavaPos == null) {
            resetState(client);
            return;
        }

        stateTicks++;
        if (stateTicks < PICKUP_DELAY_TICKS) return;

        boolean lavaExists = client.world.getFluidState(placedLavaPos).getFluid() == Fluids.LAVA;
        if (!lavaExists) {
            if (stateTicks >= CONFIRM_TIMEOUT_TICKS) resetState(client);
            return;
        }

        int bucketSlot = findItemInHotbar(client, Items.BUCKET);
        if (bucketSlot == -1) {
            if (stateTicks >= CONFIRM_TIMEOUT_TICKS) resetState(client);
            return;
        }

        selectHotbarSlot(client, bucketSlot);

        if (!performPickup(client, placedLavaPos)) {
            if (pickupAttempts >= MAX_PICKUP_ATTEMPTS) {
                resetState(client);
                return;
            }
            pickupAttempts++;
            nextActionTime = System.currentTimeMillis() + ACTION_DELAY_MS;
            return;
        }

        state = State.WAITING_FOR_PICKUP;
        stateTicks = 0;
        nextActionTime = System.currentTimeMillis() + ACTION_DELAY_MS;
    }

    private static boolean performPickup(MinecraftClient client, BlockPos lavaPos) {
        if (client.player == null || client.interactionManager == null || client.getNetworkHandler() == null) return false;

        Vec3d hitPos = new Vec3d(lavaPos.getX() + 0.5D, lavaPos.getY() + 0.5D, lavaPos.getZ() + 0.5D);
        sendSilentLook(client, hitPos);

        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, lavaPos, false);
        ActionResult result = client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);

        if (!result.isAccepted()) return false;

        client.player.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private static void handlePickupConfirmation(MinecraftClient client) {
        if (placedLavaPos == null) {
            resetState(client);
            return;
        }

        stateTicks++;
        boolean lavaStillExists = client.world.getFluidState(placedLavaPos).getFluid() == Fluids.LAVA;

        if (!lavaStillExists) {
            restoreOriginalSlot(client);
            resetState(client);
            return;
        }

        if (stateTicks >= 5 && pickupAttempts < MAX_PICKUP_ATTEMPTS) {
            pickupAttempts++;
            state = State.WAITING_FOR_LAVA;
            stateTicks = PICKUP_DELAY_TICKS;
            nextActionTime = System.currentTimeMillis() + ACTION_DELAY_MS;
            return;
        }

        if (stateTicks >= CONFIRM_TIMEOUT_TICKS) {
            restoreOriginalSlot(client);
            resetState(client);
        }
    }

    private static void sendSilentLook(MinecraftClient client, Vec3d target) {
        double dx = target.x - client.player.getX();
        double dy = target.y - client.player.getEyeY();
        double dz = target.z - client.player.getZ();

        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        if (horizontalDistance < 0.0001D) horizontalDistance = 0.0001D;

        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontalDistance)));

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                yaw, pitch, client.player.isOnGround()
        ));
    }

    private static int findItemInHotbar(MinecraftClient client, Item item) {
        for (int slot = 0; slot < 9; slot++) {
            if (client.player.getInventory().getStack(slot).isOf(item)) {
                return slot;
            }
        }
        return -1;
    }

    private static void selectHotbarSlot(MinecraftClient client, int slot) {
        if (slot < 0 || slot > 8) return;
        client.player.getInventory().selectedSlot = slot;
        client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(slot));
    }

    private static void restoreOriginalSlot(MinecraftClient client) {
        if (originalSlot >= 0 && originalSlot <= 8 && client.player != null) {
            selectHotbarSlot(client, originalSlot);
        }
    }

    public static void resetState(MinecraftClient client) {
        if (client != null && client.player != null) {
            restoreOriginalSlot(client);
        }
        state = State.IDLE;
        placedLavaPos = null;
        originalSlot = -1;
        stateTicks = 0;
        pickupAttempts = 0;
        nextActionTime = 0L;
    }
}

package com.example.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.util.Random;

public class LavaAssistantClient implements ClientModInitializer {

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lavaassistant.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.lavaassistant.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player != null) {
                while (toggleKey.wasPressed()) {
                    AutoLavaModule.toggle();
                    String status = AutoLavaModule.toggled ? "§aON" : "§cOFF";
                    client.player.sendMessage(Text.literal("§6[LavaAssistant] §fUniversal Auto Lava: " + status), true);
                }
            }
            AutoLavaModule.onPlayerTick(client);
        });
    }
}

final class AutoLavaModule {

    private AutoLavaModule() {}

    public static boolean toggled = false;
    private static int cooldownTicks = 0;
    private static boolean hasAttemptedPlacement = false;

    private static final double MAX_COMBAT_RANGE_SQ = 20.0D;
    private static final Random RANDOM = new Random();

    public static void toggle() {
        toggled = !toggled;
        if (!toggled) {
            resetState();
        }
    }

    public static void onPlayerTick(MinecraftClient client) {
        if (!toggled || client == null || client.player == null || client.world == null) {
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        // Agar player ne haath se lava bucket hata di hai, toh flag reset kar do
        if (!client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
            hasAttemptedPlacement = false;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        Item heldItem = client.player.getMainHandStack().getItem();

        // 1. Agar haath mein KHAALI BUCKET hai, toh kahin bhi rakha hua lava automatic utha lo
        if (heldItem == Items.BUCKET) {
            if (tryUniversalAutoPickup(client)) {
                cooldownTicks = 6;
                return;
            }
        }

        // 2. Agar haath mein LAVA BUCKET hai, toh enemy ke pairon ke niche lava place karo
        if (heldItem == Items.LAVA_BUCKET) {
            if (!hasAttemptedPlacement) {
                handleUniversalCombatPlacement(client);
            }
        }
    }

    private static void handleUniversalCombatPlacement(MinecraftClient client) {
        PlayerEntity target = getNearestTargetPlayer(client);
        if (target == null) return;

        if (target.isOnFire() || target.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            return;
        }

        double selfDistSq = client.player.squaredDistanceTo(target);
        if (selfDistSq < 6.0D && !client.player.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            return;
        }

        BlockPos lavaPos = target.getBlockPos();
        BlockPos supportPos = lavaPos.down();

        if (!isSafePlacement(client.world, lavaPos) || !client.world.getBlockState(supportPos).isOpaqueFullCube(client.world, supportPos)) {
            return;
        }

        Vec3d blockCenter = new Vec3d(supportPos.getX() + 0.5D, supportPos.getY() + 1.0D, supportPos.getZ() + 0.5D);
        
        // Instant look sync taaki server placement ko turant accept kare
        setLookAndSync(client, supportPos);

        BlockHitResult hitResult = new BlockHitResult(
                blockCenter,
                Direction.UP,
                supportPos,
                false
        );

        if (client.interactionManager != null) {
            client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, hitResult);
        }
        client.player.swingHand(Hand.MAIN_HAND);

        hasAttemptedPlacement = true;
        cooldownTicks = 12;
    }

    private static boolean tryUniversalAutoPickup(MinecraftClient client) {
        BlockPos playerPos = client.player.getBlockPos();
        int radius = 4;

        // Aas-paas ke blocks ko scan karo ki kahan lava hai
        for (int x = -radius; x <= radius; x++) {
            for (int y = -2; y <= 2; y++) {
                for (int z = -radius; z <= radius; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    
                    if (client.world.getFluidState(pos).getFluid() == Fluids.LAVA) {
                        // Check karo ki range mein hai ya nahi
                        if (client.player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 25.0D) {
                            
                            setLookAndSync(client, pos);

                            BlockHitResult pickupHit = new BlockHitResult(
                                    new Vec3d(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D),
                                    Direction.UP,
                                    pos,
                                    false
                            );

                            if (client.interactionManager != null) {
                                client.interactionManager.interactBlock(client.player, Hand.MAIN_HAND, pickupHit);
                            }
                            client.player.swingHand(Hand.MAIN_HAND);
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean isSafePlacement(net.minecraft.world.World world, BlockPos pos) {
        if (!world.getBlockState(pos).isReplaceable()) {
            return false;
        }
        if (world.getFluidState(pos).getFluid() == Fluids.WATER || world.getFluidState(pos).getFluid() == Fluids.FLOWING_WATER) {
            return false;
        }
        return true;
    }

    private static PlayerEntity getNearestTargetPlayer(MinecraftClient client) {
        PlayerEntity nearest = null;
        double minDistanceSq = MAX_COMBAT_RANGE_SQ + 1.0D;

        for (PlayerEntity player : client.world.getPlayers()) {
            if (player == client.player) continue;
            if (!player.isAlive() || player.isSpectator() || player.isTeammate(client.player)) continue;

            double distSq = client.player.squaredDistanceTo(player);
            if (distSq < minDistanceSq) {
                minDistanceSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    private static void setLookAndSync(MinecraftClient client, BlockPos pos) {
        double dx = pos.getX() + 0.5D - client.player.getX();
        double dy = pos.getY() + 0.5D - client.player.getEyeY();
        double dz = pos.getZ() + 0.5D - client.player.getZ();

        double distH = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distH)));

        client.player.setYaw(targetYaw);
        client.player.setPitch(targetPitch);

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                targetYaw, targetPitch, client.player.isOnGround()
        ));
    }

    public static void resetState() {
        cooldownTicks = 0;
        hasAttemptedPlacement = false;
    }
}

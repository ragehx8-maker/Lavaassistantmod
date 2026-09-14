package com.example.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
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
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
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
                    client.player.sendMessage(Text.literal("§6[LavaAssistant] §fAuto Lava Assistant: " + status), true);
                }
            }
            AutoLavaModule.onPlayerTick(client);
        });

        UseBlockCallback.EVENT.register(AutoLavaModule::onUseBlock);
    }
}

final class AutoLavaModule {

    private AutoLavaModule() {}

    public static boolean toggled = false;

    private enum State {
        IDLE,
        WAITING_FOR_LAVA,
        WAITING_FOR_PICKUP
    }

    private static State state = State.IDLE;
    private static BlockPos targetLavaPos;
    private static int stateTicks = 0;
    private static int cooldownTicks = 0;

    private static final int PLACEMENT_TIMEOUT_TICKS = 20;
    private static final int PICKUP_TIMEOUT_TICKS = 40;
    private static final int PICKUP_SETTLE_TICKS = 3;
    private static final int PICKUP_RETRY_COOLDOWN = 4;
    private static final double MAX_COMBAT_RANGE_SQ = 20.0D;

    private static final Random RANDOM = new Random();

    public static void toggle() {
        toggled = !toggled;
        if (!toggled) {
            resetState();
        }
    }

    public static ActionResult onUseBlock(PlayerEntity player,
                                           net.minecraft.world.World world,
                                           Hand hand,
                                           BlockHitResult hitResult) {

        if (!toggled || !world.isClient) return ActionResult.PASS;
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (state != State.IDLE) return ActionResult.PASS;

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player) return ActionResult.PASS;

        Item heldItem = player.getMainHandStack().getItem();
        if (heldItem != Items.LAVA_BUCKET) return ActionResult.PASS;

        BlockPos clickedPos = hitResult.getBlockPos();
        boolean replaceable = world.getBlockState(clickedPos).isReplaceable();
        BlockPos placementPos = replaceable ? clickedPos : clickedPos.offset(hitResult.getSide());

        targetLavaPos = placementPos;
        state = State.WAITING_FOR_LAVA;
        stateTicks = 0;
        cooldownTicks = 0;

        return ActionResult.PASS;
    }

    public static void onPlayerTick(MinecraftClient client) {
        if (!toggled || client == null || client.player == null || client.world == null) {
            return;
        }

        if (client.currentScreen != null) {
            return;
        }

        if (cooldownTicks > 0) {
            cooldownTicks--;
            return;
        }

        if (state == State.WAITING_FOR_LAVA) {
            handleLavaPlacementCheck(client);
        } else if (state == State.WAITING_FOR_PICKUP) {
            handleAutoPickup(client);
        } else if (state == State.IDLE) {
            if (client.player.getMainHandStack().isOf(Items.LAVA_BUCKET)) {
                handleAutoCombatPlacement(client);
            }
        }
    }

    private static void handleAutoCombatPlacement(MinecraftClient client) {
        PlayerEntity target = getNearestTargetPlayer(client);
        if (target == null) return;

        if (target.isOnFire() || target.hasStatusEffect(StatusEffects.FIRE_RESISTANCE)) {
            return;
        }

        BlockPos lavaPos = target.getBlockPos();
        BlockPos supportPos = lavaPos.down();

        if (!client.world.getBlockState(lavaPos).isReplaceable() || client.world.getBlockState(supportPos).isAir()) {
            return;
        }

        smoothLookAt(client, supportPos);

        if (client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
        client.player.swingHand(Hand.MAIN_HAND);

        targetLavaPos = lavaPos;
        state = State.WAITING_FOR_LAVA;
        stateTicks = 0;
        cooldownTicks = 6 + RANDOM.nextInt(4);
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

    private static void smoothLookAt(MinecraftClient client, BlockPos pos) {
        double dx = pos.getX() + 0.5D - client.player.getX();
        double dy = pos.getY() + 0.5D - client.player.getEyeY();
        double dz = pos.getZ() + 0.5D - client.player.getZ();

        double distH = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float targetPitch = (float) (-Math.toDegrees(Math.atan2(dy, distH)));

        float currentYaw = client.player.getYaw();
        float currentPitch = client.player.getPitch();

        float diffYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        float diffPitch = targetPitch - currentPitch;

        double sensitivity = client.options.getMouseSensitivity().getValue() * 0.6D + 0.2D;
        double gcd = sensitivity * sensitivity * sensitivity * 1.2D;

        // Natural micro-jitter added to completely break anti-cheat heuristic angle checks
        double jitterYaw = (RANDOM.nextDouble() - 0.5D) * 0.08D;
        double jitterPitch = (RANDOM.nextDouble() - 0.5D) * 0.08D;

        float finalYaw = (float) (currentYaw + Math.round(diffYaw / gcd) * gcd + jitterYaw);
        float finalPitch = (float) (currentPitch + Math.round(diffPitch / gcd) * gcd + jitterPitch);

        client.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                finalYaw, finalPitch, client.player.isOnGround()
        ));
    }

    private static void handleLavaPlacementCheck(MinecraftClient client) {
        stateTicks++;
        if (targetLavaPos == null) {
            resetState();
            return;
        }

        boolean lavaPlaced = client.world.getFluidState(targetLavaPos).getFluid() == Fluids.LAVA;

        if (lavaPlaced) {
            state = State.WAITING_FOR_PICKUP;
            stateTicks = 0;
            cooldownTicks = PICKUP_SETTLE_TICKS;
        } else if (stateTicks > PLACEMENT_TIMEOUT_TICKS) {
            resetState();
        }
    }

    private static void handleAutoPickup(MinecraftClient client) {
        stateTicks++;
        if (targetLavaPos == null || stateTicks > PICKUP_TIMEOUT_TICKS) {
            resetState();
            return;
        }

        boolean lavaExists = client.world.getFluidState(targetLavaPos).getFluid() == Fluids.LAVA;
        if (!lavaExists) {
            resetState();
            return;
        }

        if (!client.player.getMainHandStack().isOf(Items.BUCKET)) {
            resetState();
            return;
        }

        if (client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
        client.player.swingHand(Hand.MAIN_HAND);

        cooldownTicks = PICKUP_RETRY_COOLDOWN + RANDOM.nextInt(2);
    }

    public static void resetState() {
        state = State.IDLE;
        targetLavaPos = null;
        stateTicks = 0;
        cooldownTicks = 0;
    }
}

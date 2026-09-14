package com.example.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

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
                    client.player.sendMessage(Text.literal("§6[LavaAssistant] §fAuto Pickup: " + status), true);
                }
            }
            AutoLavaModule.onPlayerTick(client);
        });
    }
}

final class AutoLavaModule {

    private AutoLavaModule() {}

    public static boolean toggled = false;

    private enum State {
        IDLE,
        WAITING_FOR_PICKUP
    }

    private static State state = State.IDLE;
    private static BlockPos trackedLavaPos;
    private static int originalSlot = -1;
    private static int stateTicks = 0;
    private static long nextActionTime = 0L;

    private static final int PICKUP_DELAY_TICKS = 5;
    private static final int TIMEOUT_TICKS = 30;
    private static final long ACTION_DELAY_MS = 50L;

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

        // Agar hum pickup state mein hain toh use handle karo
        if (state == State.WAITING_FOR_PICKUP) {
            handlePickup(client);
            return;
        }

        // IDLE state mein check karo ki kya player ne abhi crosshair ke samne block par lava place kiya hai
        if (client.player.getMainHandStack().isOf(Items.BUCKET)) {
            HitResult hit = client.crosshairTarget;
            if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                BlockHitResult blockHit = (BlockHitResult) hit;
                BlockPos neighborPos = blockHit.getBlockPos().offset(blockHit.getSide());
                
                // Agar us position par fluid state LAVA ban chuki hai, toh track karna shuru karo
                if (client.world.getFluidState(neighborPos).getFluid() == Fluids.LAVA) {
                    trackedLavaPos = neighborPos;
                    originalSlot = client.player.getInventory().selectedSlot;
                    state = State.WAITING_FOR_PICKUP;
                    stateTicks = 0;
                    nextActionTime = now + ACTION_DELAY_MS;
                }
            }
        }
    }

    private static void handlePickup(MinecraftClient client) {
        if (trackedLavaPos == null) {
            resetState(client);
            return;
        }

        stateTicks++;
        if (stateTicks < PICKUP_DELAY_TICKS) {
            return;
        }

        // Check karo ki lava abhi bhi wahan hai ya nahi
        boolean lavaExists = client.world.getFluidState(trackedLavaPos).getFluid() == Fluids.LAVA;
        if (!lavaExists) {
            resetState(client);
            return;
        }

        if (stateTicks > TIMEOUT_TICKS) {
            resetState(client);
            return;
        }

        // Agar main hand mein empty bucket nahi hai, toh hotbar se dhundh kar select karo
        if (!client.player.getMainHandStack().isOf(Items.BUCKET)) {
            int bucketSlot = findItemInHotbar(client, Items.BUCKET);
            if (bucketSlot == -1) {
                resetState(client);
                return;
            }
            selectHotbarSlot(client, bucketSlot);
        }

        // Lava uthane ke liye interact packet bhejo
        if (performPickup(client, trackedLavaPos)) {
            nextActionTime = System.currentTimeMillis() + 150L;
        }
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
        trackedLavaPos = null;
        originalSlot = -1;
        stateTicks = 0;
        nextActionTime = 0L;
    }
}

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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
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
                    client.player.sendMessage(Text.literal("§6[LavaAssistant] §fAuto Drain Pickup: " + status), true);
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

    private static final int PICKUP_DELAY_TICKS = 2;
    private static final int TIMEOUT_TICKS = 25;
    private static final long ACTION_DELAY_MS = 25L;
    private static final int SCAN_RADIUS = 3; // Auto drain style radius check

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

        if (state == State.WAITING_FOR_PICKUP) {
            handleDrainPickup(client);
            return;
        }

        // Auto Drain style scanner: Player ke aas-paas radius mein check karo ki kya koi lava block hai
        if (client.player.getInventory().contains(Items.LAVA_BUCKET) || client.player.getMainHandStack().isOf(Items.BUCKET) || hasEmptyBucket(client)) {
            BlockPos playerPos = client.player.getBlockPos();
            
            for (int x = -SCAN_RADIUS; x <= SCAN_RADIUS; x++) {
                for (int y = -SCAN_RADIUS; y <= SCAN_RADIUS; y++) {
                    for (int z = -SCAN_RADIUS; z <= SCAN_RADIUS; z++) {
                        BlockPos checkPos = playerPos.add(x, y, z);
                        
                        // Agar range ke andar lava mil gaya
                        if (client.world.getFluidState(checkPos).getFluid() == Fluids.LAVA) {
                            trackedLavaPos = checkPos;
                            originalSlot = client.player.getInventory().selectedSlot;
                            state = State.WAITING_FOR_PICKUP;
                            stateTicks = 0;
                            nextActionTime = now + ACTION_DELAY_MS;
                            return;
                        }
                    }
                }
            }
        }
    }

    private static boolean hasEmptyBucket(MinecraftClient client) {
        return findItemInHotbar(client, Items.BUCKET) != -1;
    }

    private static void handleDrainPickup(MinecraftClient client) {
        if (trackedLavaPos == null) {
            resetState(client);
            return;
        }

        stateTicks++;
        if (stateTicks < PICKUP_DELAY_TICKS) {
            return;
        }

        boolean lavaExists = client.world.getFluidState(trackedLavaPos).getFluid() == Fluids.LAVA;
        if (!lavaExists) {
            resetState(client);
            return;
        }

        if (stateTicks > TIMEOUT_TICKS) {
            resetState(client);
            return;
        }

        if (!client.player.getMainHandStack().isOf(Items.BUCKET)) {
            int bucketSlot = findItemInHotbar(client, Items.BUCKET);
            if (bucketSlot == -1) {
                resetState(client);
                return;
            }
            selectHotbarSlot(client, bucketSlot);
        }

        if (performDrainPickup(client, trackedLavaPos)) {
            nextActionTime = System.currentTimeMillis() + 50L;
        }
    }

    private static boolean performDrainPickup(MinecraftClient client, BlockPos lavaPos) {
        if (client.player == null || client.getNetworkHandler() == null) return false;

        Vec3d hitPos = new Vec3d(lavaPos.getX() + 0.5D, lavaPos.getY() + 0.5D, lavaPos.getZ() + 0.5D);
        BlockHitResult hitResult = new BlockHitResult(hitPos, Direction.UP, lavaPos, false);
        
        int sequence = client.world.getPendingUpdateManager().getNewSequence();
        client.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, hitResult, sequence));
        client.player.swingHand(Hand.MAIN_HAND);
        
        return true;
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

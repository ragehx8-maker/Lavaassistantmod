package com.example.lavaassistant;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
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
                    client.player.sendMessage(Text.literal("§6[LavaAssistant] §fAuto Lava Pickup: " + status), true);
                }
            }
            AutoLavaModule.onPlayerTick(client);
        });

        // Jab bhi player khud lava bucket se lava place kare, isko detect karega
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
    private static int originalSlot = -1; // wo slot jahan lava bucket tha
    private static int stateTicks = 0;
    private static int cooldownTicks = 0;

    private static final int PLACEMENT_TIMEOUT_TICKS = 20;   // 1 second
    private static final int PICKUP_TIMEOUT_TICKS = 30;       // 1.5 second
    private static final int PICKUP_RETRY_COOLDOWN = 1;       // har tick try karega

    public static void toggle() {
        toggled = !toggled;
        if (!toggled) {
            resetState(MinecraftClient.getInstance());
        }
    }

    /**
     * Ye tab fire hota hai jab player khud kisi block pe right-click karta hai.
     * Hum sirf check karte hain: kya iske haath mein lava bucket hai?
     * Agar haan, to hum future mein us jagah ka fluid track karenge.
     */
    public static ActionResult onUseBlock(net.minecraft.entity.player.PlayerEntity player,
                                           net.minecraft.world.World world,
                                           Hand hand,
                                           BlockHitResult hitResult) {

        if (!toggled || !world.isClient) return ActionResult.PASS;
        if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
        if (state != State.IDLE) return ActionResult.PASS; // pehle se koi lava track ho raha hai

        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != player) return ActionResult.PASS; // sirf apna hi player

        Item heldItem = player.getMainHandStack().getItem();
        if (heldItem != Items.LAVA_BUCKET) return ActionResult.PASS;

        BlockPos clickedPos = hitResult.getBlockPos();
        boolean replaceable = world.getBlockState(clickedPos).isReplaceable();
        BlockPos placementPos = replaceable ? clickedPos : clickedPos.offset(hitResult.getSide());

        targetLavaPos = placementPos;
        originalSlot = client.player.getInventory().selectedSlot; // lava bucket wala slot
        state = State.WAITING_FOR_LAVA;
        stateTicks = 0;
        cooldownTicks = 0;

        return ActionResult.PASS; // normal placement hone do, hum sirf observe kar rahe hain
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
        }
    }

    private static void handleLavaPlacementCheck(MinecraftClient client) {
        stateTicks++;
        if (targetLavaPos == null) {
            resetState(client);
            return;
        }

        boolean lavaPlaced = client.world.getFluidState(targetLavaPos).getFluid() == Fluids.LAVA;

        if (lavaPlaced) {
            int bucketSlot = findItemInHotbar(client, Items.BUCKET);
            if (bucketSlot == -1) {
                // khali bucket hotbar mein nahi mila, auto-pickup cancel
                client.player.sendMessage(Text.literal("§6[LavaAssistant] §cEmpty bucket nahi mila, pickup skip."), true);
                resetState(client);
                return;
            }
            selectHotbarSlot(client, bucketSlot);
            state = State.WAITING_FOR_PICKUP;
            stateTicks = 0;
            cooldownTicks = 0; // turant next tick pickup try karo, jab tak player abhi bhi wahi dekh raha ho
        } else if (stateTicks > PLACEMENT_TIMEOUT_TICKS) {
            // lava place nahi hua (misclick ya blocked), cancel karo
            resetState(client);
        }
    }

    private static void handleAutoPickup(MinecraftClient client) {
        stateTicks++;
        if (targetLavaPos == null || stateTicks > PICKUP_TIMEOUT_TICKS) {
            resetState(client);
            return;
        }

        boolean lavaExists = client.world.getFluidState(targetLavaPos).getFluid() == Fluids.LAVA;
        if (!lavaExists) {
            // lava uth chuka hai, original slot wapas lao
            resetState(client);
            return;
        }

        // held item empty bucket hi hai ye confirm karo, warna packet bhejna galat hoga
        if (!client.player.getMainHandStack().isOf(Items.BUCKET)) {
            int bucketSlot = findItemInHotbar(client, Items.BUCKET);
            if (bucketSlot == -1) {
                resetState(client);
                return;
            }
            selectHotbarSlot(client, bucketSlot);
        }

        // IMPORTANT: lava bucket se fluid uthana Item.use() codepath se hota hai
        // (interactItem), jo khud player ki current look direction se raycast
        // karta hai — hum koi custom BlockHitResult nahi bhej sakte iske liye.
        // Isliye zaroori hai ki ye call turant ho, jab tak player ka camera
        // abhi bhi wahi (jahan lava girega hai) point kar raha ho.
        if (client.interactionManager != null) {
            client.interactionManager.interactItem(client.player, Hand.MAIN_HAND);
        }
        client.player.swingHand(Hand.MAIN_HAND);

        cooldownTicks = PICKUP_RETRY_COOLDOWN;
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
        targetLavaPos = null;
        originalSlot = -1;
        stateTicks = 0;
        cooldownTicks = 0;
    }
}

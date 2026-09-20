package com.lavaassistant.mixin;

import com.lavaassistant.client.LavaAssistantClient;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(BlockState.class)
public abstract class BlockStateMixin {
    @Shadow public abstract net.minecraft.block.Block getBlock();

    @Inject(method = "isOpaque", at = @At("HEAD"), cancellable = true)
    private void makeOpaqueTransparent(CallbackInfoReturnable<Boolean> cir) {
        if (LavaAssistantClient.xrayEnabled) {
            BlockState state = (BlockState) (Object) this;
            if (state.isOf(Blocks.DIAMOND_ORE) || 
                state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE) || 
                state.isOf(Blocks.GOLD_ORE) || 
                state.isOf(Blocks.DEEPSLATE_GOLD_ORE) ||
                state.isOf(Blocks.IRON_ORE) ||
                state.isOf(Blocks.DEEPSLATE_IRON_ORE) ||
                state.isOf(Blocks.ANCIENT_DEBRIS)) {
                cir.setReturnValue(true);
            } else {
                cir.setReturnValue(false);
            }
        }
    }
}

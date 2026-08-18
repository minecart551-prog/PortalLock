package com.example.portallock.mixin;

import com.example.portallock.ReflectPortalLogic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
    @Inject(method = "entityInside", at = @At("HEAD"), cancellable = true)
    private void portallock$onEndPortalInside(
            BlockState state,
            Level level,
            BlockPos pos,
            Entity entity,
            CallbackInfo ci
    ) {
        ReflectPortalLogic.onEndPortalInside(level, pos, entity, ci);
    }
}

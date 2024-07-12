package com.zen.fogman.mixin;

import com.zen.fogman.entity.the_man.TheManEntity;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
    @ModifyVariable(method = "travel", at = @At("STORE"), name = "h")
    public float fakeDepthStrider(float depthStriderBonus) {
        if ((Object) this instanceof TheManEntity) {
            return 1.5f;
        }

        return depthStriderBonus;
    }
}

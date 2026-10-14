package kaptainwutax.seedcrackerX.mixin;

import autismclient.api.module.EnumSetting;
import com.autism.seedcracker.modules.SusChunkFinderModule;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

@Mixin(value = EnumSetting.class, remap = false)
public abstract class SusModeSettingMixin {
    @Shadow @Final private Enum<?>[] values;

    @ModifyVariable(method = "decode(Ljava/lang/String;)Ljava/lang/Enum;", at = @At("HEAD"), argsOnly = true, remap = false)
    private String migrateSusMode(String token) { return SusChunkFinderModule.Mode.migrate(token, values); }
}
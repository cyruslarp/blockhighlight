package com.example.blockhighlight;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

/** Client-side night vision (never sent to the server) to make everything fully bright. */
public final class Fullbright {
    private static boolean applied = false;

    private Fullbright() {}

    public static void tick(Minecraft mc) {
        if (mc.player == null) { applied = false; return; }
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        boolean want = cfg.fullbrightAlways || (cfg.fullbrightFreecam && Freecam.isActive());

        if (want) {
            if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
                mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION,
                        MobEffectInstance.INFINITE_DURATION, 0, false, false, false));
            }
            applied = true;
        } else if (applied) {
            MobEffectInstance e = mc.player.getEffect(MobEffects.NIGHT_VISION);
            if (e != null && e.isInfiniteDuration()) mc.player.removeEffect(MobEffects.NIGHT_VISION);
            applied = false;
        }
    }
}

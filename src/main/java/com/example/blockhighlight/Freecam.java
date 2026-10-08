package com.example.blockhighlight;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Marker;
import net.minecraft.world.phys.Vec3;

/**
 * Client-side freecam. The camera is a separate invisible entity; the real player is frozen
 * (all movement/rotation/attack/use input is blocked), so the server never sees your body move.
 */
public final class Freecam {
    private static boolean active = false;
    private static Marker cam;
    private static double x, y, z;
    private static float yaw, pitch;
    private static float bodyYaw, bodyPitch;
    private static long lastNanos;

    private Freecam() {}

    public static boolean isActive() { return active; }

    /** Where scanning/highlighting should be centered. */
    public static BlockPos center(Minecraft mc) {
        if (active && cam != null) return BlockPos.containing(x, y, z);
        return mc.player.blockPosition();
    }

    public static void toggle(Minecraft mc) {
        if (active) exit(mc, true); else enter(mc);
    }

    private static void enter(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return;
        Vec3 eye = p.getEyePosition();
        x = eye.x; y = eye.y; z = eye.z;
        yaw = p.getYRot(); pitch = p.getXRot();
        bodyYaw = yaw; bodyPitch = pitch;
        EntityType<?> markerType = BuiltInRegistries.ENTITY_TYPE
                .getOptional(Identifier.fromNamespaceAndPath("minecraft", "marker")).orElseThrow();
        cam = new Marker(markerType, mc.level);
        applyCam();
        mc.setCameraEntity(cam);
        lastNanos = System.nanoTime();
        active = true;
        BlockHighlightClient.rescan = true;
        p.sendSystemMessage(Component.literal("Freecam: ON (your body is frozen)"));
    }

    public static void exit(Minecraft mc, boolean message) {
        if (!active) return;
        active = false;
        cam = null;
        if (mc.player != null) {
            mc.setCameraEntity(mc.player);
            if (message) mc.player.sendSystemMessage(Component.literal("Freecam: OFF"));
        }
        BlockHighlightClient.rescan = true;
    }

    /** Runs at the start of every client tick, before the player reads its input. */
    public static void tickBlockInput(Minecraft mc) {
        if (!active) return;
        if (mc.player == null || mc.level == null || cam == null
                || cam.level() != mc.level || mc.player.isDeadOrDying()) {
            exit(mc, false);
            return;
        }
        Options o = mc.options;
        for (KeyMapping k : new KeyMapping[]{o.keyUp, o.keyDown, o.keyLeft, o.keyRight,
                o.keyJump, o.keyShift, o.keySprint, o.keyAttack, o.keyUse}) {
            k.setDown(false);
        }
        while (o.keyAttack.consumeClick()) { }
        while (o.keyUse.consumeClick()) { }
    }

    /** Runs every rendered frame so mouse look and flying are smooth. */
    public static void frame(Minecraft mc) {
        if (!active) return;
        LocalPlayer p = mc.player;
        if (p == null || cam == null) return;

        // Mouse look turned the real player: move that rotation onto the camera instead.
        yaw += p.getYRot() - bodyYaw;
        pitch = Mth.clamp(pitch + (p.getXRot() - bodyPitch), -90f, 90f);
        p.setYRot(bodyYaw);
        p.setXRot(bodyPitch);

        long now = System.nanoTime();
        double dt = Math.min((now - lastNanos) / 1_000_000_000.0, 0.1);
        lastNanos = now;

        if (mc.gui.screen() == null) {
            var w = mc.getWindow();
            double f = (down(w, InputConstants.KEY_W) ? 1 : 0) - (down(w, InputConstants.KEY_S) ? 1 : 0);
            double s = (down(w, InputConstants.KEY_D) ? 1 : 0) - (down(w, InputConstants.KEY_A) ? 1 : 0);
            double v = (down(w, InputConstants.KEY_SPACE) ? 1 : 0) - (down(w, InputConstants.KEY_LSHIFT) ? 1 : 0);
            double speed = HighlightConfig.INSTANCE.freecamSpeed * (down(w, InputConstants.KEY_LCONTROL) ? 3 : 1) * dt;

            double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
            double fx = -Math.sin(yr) * Math.cos(pr), fy = -Math.sin(pr), fz = Math.cos(yr) * Math.cos(pr);
            double rx = -Math.cos(yr), rz = -Math.sin(yr);
            x += (fx * f + rx * s) * speed;
            y += (fy * f + v) * speed;
            z += (fz * f + rz * s) * speed;
        }
        applyCam();
    }

    private static boolean down(Object window, int key) {
        return InputConstants.isKeyDown((com.mojang.blaze3d.platform.Window) window, key);
    }

    private static void applyCam() {
        cam.setPos(x, y, z);
        cam.xo = x; cam.yo = y; cam.zo = z;
        cam.setYRot(yaw); cam.setXRot(pitch);
        cam.yRotO = yaw; cam.xRotO = pitch;
    }
}

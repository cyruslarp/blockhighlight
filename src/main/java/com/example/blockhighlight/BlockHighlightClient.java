package com.example.blockhighlight;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

public class BlockHighlightClient implements ClientModInitializer {
    public static final String MOD_ID = "blockhighlight";

    record Hit(int x, int y, int z, int rgb) {}
    /** Immutable snapshot used by the draw phase. */
    record RenderState(List<Hit> hits, boolean fill, boolean outline, float alpha,
                       List<SusChunks.Sus> sus, float minY, float maxY) {}

    private static final int BUFFER_SIZE = 8 * 1024 * 1024;
    private static final int VERTEX_BYTES = 16;

    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(MOD_ID, "pipeline/filled_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer stagedBuffer = new StagedVertexBuffer(() -> "Block Highlight Buffer", BUFFER_SIZE);

    public static volatile List<Hit> hits = List.of();
    public static boolean rescan = true;
    private RenderState renderState;
    private int tick = 0;

    @Override
    public void onInitializeClient() {
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockhighlight.toggle", InputConstants.Type.KEYSYM, InputConstants.KEY_X, category));
        KeyMapping menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockhighlight.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

        KeyMapping freecamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.blockhighlight.freecam", InputConstants.Type.KEYSYM, InputConstants.KEY_V, category));

        ClientTickEvents.START_CLIENT_TICK.register(Freecam::tickBlockInput);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            HighlightConfig cfg = HighlightConfig.INSTANCE;
            while (toggleKey.consumeClick()) {
                cfg.enabled = !cfg.enabled;
                cfg.save();
                rescan = true;
                if (mc.player != null)
                    mc.player.sendSystemMessage(Component.literal("Block Highlight: " + (cfg.enabled ? "ON" : "OFF")));
            }
            StashFinder.tick(mc);
            SusChunks.tick(mc);
            Fullbright.tick(mc);
            StaffWatch.tick(mc);
            while (freecamKey.consumeClick()) Freecam.toggle(mc);
            while (menuKey.consumeClick()) mc.gui.setScreen(new HighlightScreen());

            if (mc.level == null || mc.player == null || !cfg.enabled) {
                hits = List.of();
                return;
            }
            if (rescan || ++tick % 20 == 0) {
                rescan = false;
                scan(mc, cfg);
            }
        });

        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "stash_alerts"), StashFinder::render);
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(MOD_ID, "staff_watch"), StaffWatch::render);
        LevelExtractionEvents.END_EXTRACTION.register(ctx -> Freecam.frame(Minecraft.getInstance()));
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::renderAndDraw);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> stagedBuffer.close());
    }

    // ---------- scanning (client tick) ----------

    private void scan(Minecraft mc, HighlightConfig cfg) {
        Set<Block> targets = new HashSet<>();
        for (String s : cfg.blocks) {
            Identifier id = Identifier.tryParse(s);
            if (id != null) BuiltInRegistries.BLOCK.getOptional(id).ifPresent(targets::add);
        }
        if (targets.isEmpty()) { hits = List.of(); return; }

        List<Hit> out = new ArrayList<>();
        BlockPos c = Freecam.center(mc);
        int r = cfg.radius;
        int minY = Math.max(mc.level.getMinY(), c.getY() - r);
        int maxY = Math.min(mc.level.getMaxY(), c.getY() + r);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int x = c.getX() - r; x <= c.getX() + r; x++)
            for (int z = c.getZ() - r; z <= c.getZ() + r; z++)
                for (int y = minY; y <= maxY; y++) {
                    p.set(x, y, z);
                    Block b = mc.level.getBlockState(p).getBlock();
                    if (targets.contains(b)) {
                        out.add(new Hit(x, y, z, HighlightConfig.colorFor(BuiltInRegistries.BLOCK.getKey(b).toString())));
                        if (out.size() >= 20000) break;
                    }
                }

        // keep the nearest ones if there are too many for the vertex buffer
        int perHit = (cfg.fill ? 24 : 0) + (cfg.outline ? 12 * 24 : 0);
        int maxHits = perHit == 0 ? 0 : (int) ((BUFFER_SIZE / (double) VERTEX_BYTES - 8000) / perHit * 0.9);
        if (out.size() > maxHits) {
            out.sort(Comparator.comparingDouble(h -> c.distSqr(new BlockPos(h.x(), h.y(), h.z()))));
            out = new ArrayList<>(out.subList(0, maxHits));
        }
        hits = out;
    }

    // ---------- extraction phase ----------

    private void extract(LevelExtractionContext context) {
        HighlightConfig cfg = HighlightConfig.INSTANCE;
        List<Hit> list = cfg.enabled ? hits : List.of();
        List<SusChunks.Sus> sus = cfg.susChunks ? SusChunks.drawList() : List.of();
        boolean blocks = !list.isEmpty() && (cfg.fill || cfg.outline);
        Minecraft mc = Minecraft.getInstance();
        if ((!blocks && sus.isEmpty()) || mc.level == null) {
            renderState = null;
            return;
        }
        renderState = new RenderState(blocks ? list : List.of(), cfg.fill, cfg.outline,
                Math.max(0.02f, cfg.opacity / 100f), sus, mc.level.getMinY(), mc.level.getMaxY() + 1);
    }

    // ---------- drawing phase ----------

    private void renderAndDraw(LevelRenderContext context) {
        RenderState state = this.renderState;
        if (state == null || (state.hits().isEmpty() && state.sus().isEmpty())) return;

        RenderPipeline pipeline = THROUGH_WALLS;
        VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
        assert formatBinding != null;

        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = stagedBuffer.appendDraw(formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc m = matrices.last().pose();
        VertexConsumer builder = stagedBuffer.getVertexBuilder(draw);

        for (Hit h : state.hits()) {
            float r = ((h.rgb() >> 16) & 255) / 255f, g = ((h.rgb() >> 8) & 255) / 255f, b = (h.rgb() & 255) / 255f;
            if (state.fill()) {
                float e = 0.002f;
                box(m, builder, h.x() - e, h.y() - e, h.z() - e, h.x() + 1 + e, h.y() + 1 + e, h.z() + 1 + e, r, g, b, state.alpha());
            }
            if (state.outline()) edges(m, builder, h.x(), h.y(), h.z(), r, g, b);
        }

        for (SusChunks.Sus sc : state.sus()) {
            float x0 = sc.cx() * 16f, z0 = sc.cz() * 16f, x1 = x0 + 16f, z1 = z0 + 16f;
            float py = (float) Math.floor(camera.y);
            box(m, builder, x0, py, z0, x1, py + 0.05f, z1, 1f, 0.1f, 0.1f, 0.25f);
            float t = 0.2f;
            float[] cxs = {x0, x1}, czs = {z0, z1};
            for (float cx : cxs)
                for (float cz : czs)
                    box(m, builder, cx - t, state.minY(), cz - t, cx + t, state.maxY(), cz + t, 1f, 0.1f, 0.1f, 0.7f);
            BlockPos bp = sc.pos();
            box(m, builder, bp.getX() - 0.05f, bp.getY() - 0.05f, bp.getZ() - 0.05f,
                    bp.getX() + 1.05f, bp.getY() + 1.05f, bp.getZ() + 1.05f, 1f, 0.1f, 0.1f, 0.6f);
        }
        matrices.popPose();

        stagedBuffer.upload();
        StagedVertexBuffer.ExecuteInfo info = stagedBuffer.getExecuteInfo(draw);
        if (info != null) draw(Minecraft.getInstance(), info, pipeline);
        stagedBuffer.endFrame();
    }

    /** Outline = 12 thin opaque boxes along the block's edges. */
    private static void edges(Matrix4fc m, VertexConsumer v, float x, float y, float z, float r, float g, float b) {
        float t = 0.025f, x1 = x + 1, y1 = y + 1, z1 = z + 1;
        for (float yy : new float[]{y, y1})
            for (float zz : new float[]{z, z1})
                box(m, v, x - t, yy - t, zz - t, x1 + t, yy + t, zz + t, r, g, b, 1f);
        for (float xx : new float[]{x, x1})
            for (float zz : new float[]{z, z1})
                box(m, v, xx - t, y - t, zz - t, xx + t, y1 + t, zz + t, r, g, b, 1f);
        for (float xx : new float[]{x, x1})
            for (float yy : new float[]{y, y1})
                box(m, v, xx - t, yy - t, z - t, xx + t, yy + t, z1 + t, r, g, b, 1f);
    }

    private static void box(Matrix4fc m, VertexConsumer v, float x0, float y0, float z0, float x1, float y1, float z1,
                            float r, float g, float b, float a) {
        // front
        v.addVertex(m, x0, y0, z1).setColor(r, g, b, a); v.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
        v.addVertex(m, x1, y1, z1).setColor(r, g, b, a); v.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
        // back
        v.addVertex(m, x1, y0, z0).setColor(r, g, b, a); v.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
        v.addVertex(m, x0, y1, z0).setColor(r, g, b, a); v.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
        // left
        v.addVertex(m, x0, y0, z0).setColor(r, g, b, a); v.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
        v.addVertex(m, x0, y1, z1).setColor(r, g, b, a); v.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        // right
        v.addVertex(m, x1, y0, z1).setColor(r, g, b, a); v.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
        v.addVertex(m, x1, y1, z0).setColor(r, g, b, a); v.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        // top
        v.addVertex(m, x0, y1, z1).setColor(r, g, b, a); v.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
        v.addVertex(m, x1, y1, z0).setColor(r, g, b, a); v.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
        // bottom
        v.addVertex(m, x0, y0, z0).setColor(r, g, b, a); v.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
        v.addVertex(m, x1, y0, z1).setColor(r, g, b, a); v.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        assert colorTexture != null;

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> MOD_ID + " highlight pass", colorTexture, Optional.empty(),
                        mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            renderPass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, info.vertexBuffer().slice());
            renderPass.setIndexBuffer(info.indexBuffer(), info.indexType());
            renderPass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }
}

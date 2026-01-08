package com.frames;

import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.orbit.EventHandler;

import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.item.FilledMapItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.map.MapState;
import net.minecraft.block.MapColor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.MapIdComponent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;


import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MapRgbScanner extends Module {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "MapScanner-Worker");
        t.setDaemon(true);
        return t;
    });

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Set<String> scannedMaps = new HashSet<>();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private ScanDb db;

    private final Setting<String> outputFolder = sgGeneral.add(new StringSetting.Builder()
        .name("output-folder")
        .description("Folder under the Minecraft game directory to store archives (ex: mapframe_archive).")
        .defaultValue("mapframe_archive")
        .build()
    );

    private final Setting<String> webhookUrl = sgGeneral.add(new StringSetting.Builder()
        .name("webhook-url")
        .description("Discord webhook URL.")
        .defaultValue("")
        .build()
    );

    private final Setting<Integer> radius = sgGeneral.add(new IntSetting.Builder()
        .name("radius")
        .description("Scan radius in blocks.")
        .defaultValue(255)
        .min(1)
        .sliderMax(512)
        .build()
    );

    private final Setting<Integer> scanIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("How often to scan (in ticks).")
        .defaultValue(3)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> maxMapsPerScan = sgGeneral.add(new IntSetting.Builder()
        .name("max-maps-per-scan")
        .description("")
        .defaultValue(64)
        .min(1)
        .sliderMax(256)
        .build()
    );

    private final Setting<Boolean> rescanOnEnable = sgGeneral.add(new BoolSetting.Builder()
        .name("rescan-on-enable")
        .description("Clear cache on reset")
        .defaultValue(true)
        .build()
    );

    public MapRgbScanner() {
        super(
            Categories.Render,
            "map-scanner",
            "Map Scanner",
            "Scans nearby item-frame maps and sends their images to a Discord webhook."
        );
    }

    private static class MapJob {
        final byte[] colors;
        final String mapId;
        final BlockPos pos;

        MapJob(byte[] colors, String mapId, BlockPos pos) {
            this.colors = colors;
            this.mapId = mapId;
            this.pos = pos;
        }
    }

    @Override
    public void onActivate() {
        super.onActivate();

        if (rescanOnEnable.get()) scannedMaps.clear();

        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            String folder = outputFolder.get().trim().isEmpty() ? "mapframe_archive" : outputFolder.get().trim();
            Path dbPath = gameDir.resolve(folder).resolve("map_archive.db");

            db = new ScanDb(dbPath);
            info("Map DB ready: " + dbPath.toAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            warning("Failed to open Map DB. Maps will NOT be saved.");
            db = null;
        }

        if (webhookUrl.get().isBlank()) {
            warning("Webhook URL is empty. Maps will be scanned but NOT sent.");
        } else if (!looksLikeDiscordWebhook(webhookUrl.get())) {
            warning("Webhook URL doesn't look like a Discord webhook. Sending may fail.");
        }

        info("Map Scanner enabled.");
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        if (db != null) {
            db.close();
            db = null;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (++tickCounter % scanIntervalTicks.get() != 0) return;

        int r = radius.get();
        double radiusSq = (double) r * (double) r;

        List<MapJob> jobs = new ArrayList<>();

        Box box = new Box(
            mc.player.getX() - r, mc.player.getY() - r, mc.player.getZ() - r,
            mc.player.getX() + r, mc.player.getY() + r, mc.player.getZ() + r
        );

        List<ItemFrameEntity> frames = mc.world.getEntitiesByClass(ItemFrameEntity.class, box, f -> true);

        for (ItemFrameEntity frame : frames) {
            if (frame.squaredDistanceTo(mc.player) > radiusSq) continue;

            ItemStack stack = frame.getHeldItemStack();
            if (!(stack.getItem() instanceof FilledMapItem)) continue;

            MapState state = FilledMapItem.getMapState(stack, mc.world);
            if (state == null || state.colors == null) continue;

            byte[] colors = state.colors;
            int w = 128, h = 128;
            if (colors.length < w * h) continue;

// stack is already a FilledMapItem above, so no need to re-check.
// But we MUST NOT "return" from inside the loop; use continue.

            MapIdComponent idComp = stack.getComponents().get(DataComponentTypes.MAP_ID);
            if (idComp == null) continue;

            String idText = "map_" + idComp.id();


            if (!"unknown".equals(idText) && scannedMaps.contains(idText)) continue;
            if (!"unknown".equals(idText)) scannedMaps.add(idText);

            byte[] snapshot = Arrays.copyOf(colors, w * h);
            BlockPos pos = frame.getBlockPos();
            jobs.add(new MapJob(snapshot, idText, pos));

            if (jobs.size() >= maxMapsPerScan.get()) break;
        }

        if (jobs.isEmpty()) return;

        String url = webhookUrl.get().trim();
        boolean sendDiscord = !url.isEmpty();

        if (!sendDiscord) {
            info("Found " + jobs.size() + " new map(s). (Not sending — webhook-url is blank.)");
        } else {
            info("Found " + jobs.size() + " new map(s); sending to Discord in background.");
        }

        EXECUTOR.submit(() -> processJobs(jobs, url));
    }

    private int tickCounter = 0;

    private void processJobs(List<MapJob> jobs, String url) {
        int stored = 0;
        int sent = 0;

        String dimension = (mc.world != null && mc.world.getRegistryKey() != null)
            ? mc.world.getRegistryKey().getValue().toString()
            : "unknown";

        boolean sendDiscord = url != null && !url.isBlank();

        for (MapJob job : jobs) {
            try {
                BufferedImage img = mapToImage(job.colors);
                byte[] png = imageToPng(img);

                if (db != null && job.mapId != null && !job.mapId.equals("unknown")) {
                    db.upsertMap(
                        job.mapId,
                        dimension,
                        job.pos.getX(), job.pos.getY(), job.pos.getZ(),
                        png,
                        job.colors
                    );
                    stored++;
                }

                if (sendDiscord) {
                    String info = "Map " + job.mapId + " at " + job.pos.toShortString();
                    DiscordWebhookSender.sendPng(url, png, "map.png", info);
                    sent++;
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }

        if (stored > 0) System.out.println("[MapScanner] Stored " + stored + " map(s) to DB.");
        if (sent > 0) System.out.println("[MapScanner] Sent " + sent + " map(s) to Discord.");
    }

    private BufferedImage mapToImage(byte[] colors) {
        int w = 128, h = 128;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);

        if (colors == null || colors.length < w * h) return img;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int idx = y * w + x;
                int colorByte = colors[idx] & 0xFF;

                int rgb = (colorByte == 0)
                    ? 0x202020
                    : MapColor.getRenderColor(colorByte);

                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private byte[] imageToPng(BufferedImage img) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "PNG", out);
        return out.toByteArray();
    }

    private boolean looksLikeDiscordWebhook(String url) {
        return url.startsWith("https://discord.com/api/webhooks/")
            || url.startsWith("https://discordapp.com/api/webhooks/");
    }
}

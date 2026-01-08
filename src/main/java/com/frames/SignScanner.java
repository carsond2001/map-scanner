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
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SignScanner extends Module {
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SignScanner-Worker");
        t.setDaemon(true);
        return t;
    });

    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Set<String> scanned = new HashSet<>();
    private int tickCounter = 0;

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
        .defaultValue(64)
        .min(1)
        .sliderMax(256)
        .build()
    );

    private final Setting<Integer> verticalRange = sgGeneral.add(new IntSetting.Builder()
        .name("vertical-range")
        .description("How far up/down from you to scan (performance).")
        .defaultValue(48)
        .min(4)
        .sliderMax(256)
        .build()
    );

    private final Setting<Integer> scanIntervalTicks = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval-ticks")
        .description("How often to scan (in ticks).")
        .defaultValue(10)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private final Setting<Integer> maxSignsPerScan = sgGeneral.add(new IntSetting.Builder()
        .name("max-signs-per-scan")
        .description("Max new signs to process per scan.")
        .defaultValue(25)
        .min(1)
        .sliderMax(200)
        .build()
    );

    private final Setting<Boolean> includeBack = sgGeneral.add(new BoolSetting.Builder()
        .name("include-back")
        .description("Include back-side text when present.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rescanOnEnable = sgGeneral.add(new BoolSetting.Builder()
        .name("rescan-on-enable")
        .description("Clear in-memory cache on enable.")
        .defaultValue(true)
        .build()
    );

    public SignScanner() {
        super(
            Categories.Render,
            "sign-scanner",
            "Sign Scanner",
            "Scans nearby signs, archives them to SQLite, and optionally sends to a Discord webhook."
        );
    }

    private static class SignJob {
        final long firstSeenMs;
        final BlockPos pos;
        final String front;
        final String back;
        final String contentKey;
        final String dimension;
        final String server;
        final String discordMessage;

        SignJob(long firstSeenMs, BlockPos pos, String front, String back,
                String contentKey, String dimension, String server, String discordMessage) {
            this.firstSeenMs = firstSeenMs;
            this.pos = pos;
            this.front = front;
            this.back = back;
            this.contentKey = contentKey;
            this.dimension = dimension;
            this.server = server;
            this.discordMessage = discordMessage;
        }
    }

    private Path getSignDbPath() {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        String folder = outputFolder.get().trim().isEmpty() ? "mapframe_archive" : outputFolder.get().trim();
        return gameDir.resolve(folder).resolve("sign_archive.sqlite");
    }

    @Override
    public void onActivate() {
        super.onActivate();

        if (rescanOnEnable.get()) scanned.clear();

        try {
            Path dbPath = getSignDbPath();
            SignArchiveDb.init(dbPath);
            info("Sign DB ready: " + dbPath.toAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
            warning("Failed to init SignArchiveDb (see logs).");
        }

        if (webhookUrl.get().isBlank()) {
            warning("Webhook URL is empty. Signs will be scanned and archived but NOT sent.");
        } else if (!looksLikeDiscordWebhook(webhookUrl.get())) {
            warning("Webhook URL doesn't look like a Discord webhook. Sending may fail.");
        }

        info("Sign Scanner enabled.");
    }

    @Override
    public void onDeactivate() {
        super.onDeactivate();
        // Optional: close DB when module disabled
        SignArchiveDb.close();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (++tickCounter % scanIntervalTicks.get() != 0) return;

        int r = radius.get();
        int rSq = r * r;

        int v = verticalRange.get();
        int yMin = mc.player.getBlockY() - v;
        int yMax = mc.player.getBlockY() + v;

        BlockPos playerPos = mc.player.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        List<SignJob> jobs = new ArrayList<>();

        outer:
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > rSq) continue;

                int x = playerPos.getX() + dx;
                int z = playerPos.getZ() + dz;

                for (int y = yMin; y <= yMax; y++) {
                    pos.set(x, y, z);

                    BlockState state = mc.world.getBlockState(pos);
                    if (!state.isIn(BlockTags.SIGNS)) continue;

                    BlockEntity be = mc.world.getBlockEntity(pos);
                    if (!(be instanceof SignBlockEntity sign)) continue;

                    String front = signTextToPlain(sign.getFrontText());
                    String back = includeBack.get() ? signTextToPlain(sign.getBackText()) : "";

                    if (front.isBlank() && back.isBlank()) continue;

                    String key = pos.asLong() + "|" + front + "|" + back;
                    if (!scanned.add(key)) continue;

                    BlockPos immutablePos = pos.toImmutable();

                    String dim = (mc.world.getRegistryKey() != null)
                        ? mc.world.getRegistryKey().getValue().toString()
                        : null;

                    String srv = (mc.getCurrentServerEntry() != null)
                        ? mc.getCurrentServerEntry().address
                        : "singleplayer";

                    String msg = formatDiscordMessage(immutablePos, front, back);

                    jobs.add(new SignJob(
                        System.currentTimeMillis(),
                        immutablePos,
                        front,
                        back,
                        key,
                        dim,
                        srv,
                        msg
                    ));

                    if (jobs.size() >= maxSignsPerScan.get()) break outer;
                }
            }
        }

        if (jobs.isEmpty()) return;

        String url = webhookUrl.get().trim();

        if (url.isEmpty()) {
            info("Found " + jobs.size() + " new sign(s); archiving locally (webhook-url blank, not sending).");
        } else {
            info("Found " + jobs.size() + " new sign(s); archiving + sending in background.");
        }

        EXECUTOR.submit(() -> processJobs(jobs, url));
    }

    private void processJobs(List<SignJob> jobs, String url) {
        int stored = 0;
        int sent = 0;

        // Ensure DB open in worker thread too
        try {
            SignArchiveDb.init(getSignDbPath());
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        for (SignJob job : jobs) {
            try {
                SignArchiveDb.insertSign(
                    job.firstSeenMs,
                    job.dimension,
                    job.server,
                    job.pos,
                    job.front,
                    job.back,
                    job.contentKey
                );
                stored++;
            } catch (Exception ex) {
                ex.printStackTrace();
            }

            if (url != null && !url.isBlank()) {
                try {
                    DiscordWebhookSender.sendMessage(url, job.discordMessage);
                    sent++;
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }

        System.out.println("[SignScanner] Stored " + stored + " sign(s); sent " + sent + " to Discord.");
    }

    private String formatDiscordMessage(BlockPos pos, String front, String back) {
        StringBuilder sb = new StringBuilder();
        sb.append("**Sign** at `")
            .append(pos.getX()).append(", ")
            .append(pos.getY()).append(", ")
            .append(pos.getZ()).append("`");

        sb.append("\n**Front:**\n");
        sb.append(codeBlock(front));

        if (includeBack.get() && !isBlank(back) && !back.equals(front)) {
            sb.append("\n**Back:**\n");
            sb.append(codeBlock(back));
        }
        return sb.toString();
    }

    private String codeBlock(String s) {
        String safe = (s == null) ? "" : s.replace("```", "`\u200B``");
        return "```text\n" + safe + "\n```";
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private String signTextToPlain(SignText st) {
        if (st == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            Text t = st.getMessage(i, false);
            String line = (t == null) ? "" : t.getString();
            if (i > 0) sb.append("\n");
            sb.append(line);
        }
        return sb.toString().trim();
    }

    private boolean looksLikeDiscordWebhook(String url) {
        return url.startsWith("https://discord.com/api/webhooks/")
            || url.startsWith("https://discordapp.com/api/webhooks/");
    }
}

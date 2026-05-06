package com.vaicos.csnexport;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Environment(EnvType.CLIENT)
public class CsnExportClient implements ClientModInitializer {

    private static final int     PERIOD_START_DAY     = 1;
    private static final long    PAGE_REQUEST_DELAY_MS = 200;   // 5 commands/sec
    private static final long    LAST_PAGE_FLUSH_MS    = 1_500;   // wait for last page's entries after header
    private static final long    HARD_TIMEOUT_MS       = 10 * 60 * 1_000;  // emergency fallback only

    private static final Pattern PAGE_RE = Pattern.compile(
            "Page\\s+(\\d+)\\s*/\\s*(\\d+|\\?)");

    private static final Pattern ENTRY_RE = Pattern.compile(
            "^\\s*[+\\-]?\\s*(.+?)\\s+(bought|sold)(?:\\s+you)?\\s+(\\d+)x\\s*(.+?)\\s+" +
            "(?:(\\d+)d\\s*)?(?:(\\d+)h\\s*)?(?:(\\d+)m\\s*)?(?:(\\d+)s\\s*)?ago\\s*" +
            "\\(([+\\-])([\\d,]+(?:\\.\\d+)?)\\s+Coins\\)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CSN_FOOTER_RE = Pattern.compile(
            ".*(To remove all entries|/csn clear|csn history).*",
            Pattern.CASE_INSENSITIVE);

    static Path configDir;
    static String discordWebhook = "";
    static Map<String, String> brewAliases = new HashMap<>();

    private KeyBinding exportKey;
    private KeyBinding settingsKey;
    private boolean running = false;

    private long startedAtMs;
    private long nextActionAtMs;
    private long lastPageReceivedAtMs;  // set when page N/N header arrives; 0 = not yet
    private int  currentPage;
    private int  totalPages;
    private int  requestedPage;
    private int  pendingPage;

    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> seenChatLinesThisRun = new HashSet<>();
    private String sellerName = "UNKNOWN";
    private String runTimestampIso;

    record Entry(
            String actor,
            String seller,
            String verb,
            int    quantity,
            String item,
            double amountCoins,
            String timestampIso
    ) {}

    record ExportTargets(Path dataFile, Path seenFile, Path monthlyFile) {}

    enum AppendResult { APPEND, NO_NEW_ENTRIES }

    @Override
    public void onInitializeClient() {
        exportKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.csnexport.export",
                InputUtil.Type.KEYSYM,
                -1,
                "category.csnexport"
        ));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.csnexport.settings",
                InputUtil.Type.KEYSYM,
                -1,
                "category.csnexport"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
        ClientReceiveMessageEvents.GAME.register(this::onReceiveGameMessage);
        ClientTickEvents.START_CLIENT_TICK.register(client -> {
            if (configDir == null && client.player != null)
                loadConfig(client);
        });
    }

    private void onEndTick(MinecraftClient client) {
        if (client.player == null) return;

        long now = System.currentTimeMillis();

        if (settingsKey.wasPressed()) {
            client.setScreen(new CsnSettingsScreen(client.currentScreen));
            return;
        }

        if (exportKey.wasPressed()) {
            if (running) {
                client.player.sendMessage(
                    Text.literal(String.format("[CSN] Already running… (page %d / %d)", currentPage, totalPages)), false);
            } else {
                start(client);
            }
            return;
        }

        if (!running) return;

        // Emergency fallback — should never trigger under normal conditions
        if ((now - startedAtMs) > HARD_TIMEOUT_MS) {
            finish(client, "timeout");
            return;
        }

        // Deterministic completion: last page header received, flush window elapsed
        if (lastPageReceivedAtMs > 0 && (now - lastPageReceivedAtMs) >= LAST_PAGE_FLUSH_MS) {
            finish(client, "complete");
            return;
        }

        // Request next page — only while we haven't received the final page header yet
        if (lastPageReceivedAtMs == 0 && now >= nextActionAtMs && pendingPage != requestedPage) {
            sendCommand(client, "csn history " + pendingPage);
            requestedPage  = pendingPage;
            nextActionAtMs = now + PAGE_REQUEST_DELAY_MS;
        }
    }

    private void start(MinecraftClient client) {
        if (running) {
            client.player.sendMessage(Text.literal("CSN export already running"), false);
            return;
        }

        loadConfig(client);

        running               = true;
        startedAtMs           = System.currentTimeMillis();
        nextActionAtMs        = startedAtMs + 500;
        lastPageReceivedAtMs  = 0;
        currentPage           = 0;
        totalPages            = 0;
        requestedPage         = 0;
        pendingPage           = 1;
        entries.clear();
        seenChatLinesThisRun.clear();
        sellerName           = client.player.getGameProfile().getName();
        runTimestampIso      = Instant.now().toString();

        sendCommand(client, "csn history 1");
        requestedPage = 1;
        client.player.sendMessage(Text.literal("[CSN] Export started…"), false);
    }

    static void loadConfig(MinecraftClient client) {
        configDir = client.runDirectory.toPath().resolve("sales");
        Path configFile = configDir.resolve("csn_config.json");

        brewAliases    = new HashMap<>();
        discordWebhook = "";

        if (!Files.exists(configFile, LinkOption.NOFOLLOW_LINKS)) {
            writeDefaultConfig(configFile);
            return;
        }

        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            if (root.has("discord_webhook") && !root.get("discord_webhook").isJsonNull())
                discordWebhook = root.get("discord_webhook").getAsString().trim();

            if (root.has("brew_aliases") && root.get("brew_aliases").isJsonObject()) {
                JsonObject aliases = root.getAsJsonObject("brew_aliases");
                for (Map.Entry<String, com.google.gson.JsonElement> e : aliases.entrySet()) {
                    String code = e.getKey().strip();
                    String name = e.getValue().getAsString().strip();
                    if (!code.isEmpty() && !name.isEmpty())
                        brewAliases.put(code, name);
                }
            }

            if (client.player != null) {
                client.player.sendMessage(
                    Text.literal(String.format("[CSN] Config — %d alias(es)%s",
                        brewAliases.size(),
                        discordWebhook.isEmpty() ? "" : ", auto-post enabled")), false);
            }

        } catch (Exception e) {
            System.err.println("[CSN] Failed to load csn_config.json: " + e.getMessage());
            if (client.player != null)
                client.player.sendMessage(
                    Text.literal("[CSN] Could not read csn_config.json: " + e.getMessage()), false);
        }
    }

    private static void writeDefaultConfig(Path configFile) {
        String template = "{\n  \"discord_webhook\": \"\",\n  \"brew_aliases\": {}\n}\n";
        try {
            Files.createDirectories(configFile.getParent());
            Files.writeString(configFile, template, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (IOException ignored) {}
    }

    static void saveConfig() {
        if (configDir == null) return;
        try {
            Files.createDirectories(configDir);
            JsonObject root = new JsonObject();
            root.addProperty("discord_webhook", discordWebhook);
            JsonObject aliases = new JsonObject();
            brewAliases.forEach(aliases::addProperty);
            root.add("brew_aliases", aliases);
            Files.writeString(configDir.resolve("csn_config.json"),
                    new Gson().toJson(root), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            System.err.println("[CSN] Failed to save config: " + e.getMessage());
        }
    }

    private String resolveAlias(String rawItem) {
        return brewAliases.getOrDefault(rawItem, rawItem);
    }

    private void onReceiveGameMessage(Text message, boolean overlay) {
        if (!running || overlay) return;
        String line = message.getString();

        if (!seenChatLinesThisRun.add(line)) return;

        long now = System.currentTimeMillis();

        Matcher pm = PAGE_RE.matcher(line);
        if (pm.find()) {
            currentPage = Integer.parseInt(pm.group(1));
            try { totalPages = Integer.parseInt(pm.group(2)); }
            catch (NumberFormatException ignored) {}

            if (totalPages > 0 && currentPage >= totalPages) {
                // Final page header received — start flush window, stop requesting
                if (lastPageReceivedAtMs == 0) lastPageReceivedAtMs = now;
            } else if (currentPage < totalPages) {
                // Queue the next page
                pendingPage    = currentPage + 1;
                nextActionAtMs = now + PAGE_REQUEST_DELAY_MS;
            }

            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && (currentPage % 10 == 0 || currentPage == totalPages)) {
                mc.player.sendMessage(
                    Text.literal(String.format("[CSN] Page %d / %d…", currentPage, totalPages)), true);
            }
            return;
        }

        Matcher em = ENTRY_RE.matcher(line);
        if (!em.matches()) return;

        String actor  = em.group(1).trim();
        String verb   = em.group(2).toLowerCase(Locale.ROOT);
        int    qty    = Integer.parseInt(em.group(3));
        // Strip CSN color code suffixes like "#aFe" or "#1a2b3c" appended to item names
        String item   = em.group(4).trim().replaceAll("#[0-9a-fA-F]{1,6}$", "").trim();

        int days  = parseOrZero(em.group(5));
        int hours = parseOrZero(em.group(6));
        int mins  = parseOrZero(em.group(7));
        int secs  = parseOrZero(em.group(8));

        String sign  = em.group(9);
        double coins = parseCoins(em.group(10));
        if ("-".equals(sign)) coins = -coins;

        long agoMs   = ((long) days * 86_400 + hours * 3_600L + mins * 60L + secs) * 1_000L;
        String tsIso = Instant.ofEpochMilli(now - agoMs).toString();

        entries.add(new Entry(actor, sellerName, verb, qty, item, coins, tsIso));
    }

    private void finish(MinecraftClient client, String reason) {
        running = false;

        ExportTargets targets = resolveTargets(client);
        int newCount = appendToFile(targets, entries, runTimestampIso);
        writeMonthlyReport(targets, entries);

        String msg = String.format(
                "[CSN] Export %s — %d new entries written. File saved to .minecraft/sales/",
                reason, newCount);
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), false);
            sendCommand(client, "csn clear");
        }

        if (!discordWebhook.isEmpty()) {
            Path monthlyFile = targets.monthlyFile();
            String webhook   = discordWebhook;
            String filename  = monthlyFile.getFileName().toString();
            Thread.ofVirtual().name("csn-push").start(() ->
                postToWebhook(webhook, monthlyFile, filename, client));
        }
    }

    private ExportTargets resolveTargets(MinecraftClient client) {
        LocalDate today = LocalDate.now();
        int dom = today.getDayOfMonth();

        LocalDate periodStart;
        if (dom >= PERIOD_START_DAY) {
            periodStart = today.withDayOfMonth(PERIOD_START_DAY);
        } else {
            LocalDate prev = today.minusMonths(1);
            periodStart    = prev.withDayOfMonth(Math.min(PERIOD_START_DAY, prev.lengthOfMonth()));
        }

        if (configDir == null) configDir = client.runDirectory.toPath().resolve("sales");
        try { Files.createDirectories(configDir); } catch (IOException ignored) {}

        String period = periodStart.format(DateTimeFormatter.ISO_LOCAL_DATE);
        String month  = today.getYear() + "-" + String.format("%02d", today.getMonthValue());

        return new ExportTargets(
                configDir.resolve("csn_export_" + period + ".csv"),
                configDir.resolve("csn_export_" + period + ".seen"),
                configDir.resolve("csn_monthly_" + month + ".csv")
        );
    }

    private int appendToFile(ExportTargets targets, List<Entry> newEntries, String runIso) {
        Set<String> seen = loadSeen(targets.seenFile());

        boolean newFile = !Files.exists(targets.dataFile(), LinkOption.NOFOLLOW_LINKS);
        StringBuilder sb = new StringBuilder();

        if (newFile) {
            sb.append("# PERIOD,").append(targets.dataFile().getFileName()).append('\n');
            sb.append("actor,seller,verb,quantity,item,amount_coins,timestamp_iso\n");
        }

        double profit = 0, loss = 0;
        int    written = 0;
        Set<String> newHashes = new HashSet<>();

        for (Entry e : newEntries) {
            String hash = stableEntryHash(e);
            if (seen.contains(hash) || newHashes.contains(hash)) continue;
            newHashes.add(hash);

            String displayItem = resolveAlias(e.item());

            sb.append(csvField(e.actor())).append(',')
              .append(csvField(e.seller())).append(',')
              .append(e.verb()).append(',')
              .append(e.quantity()).append(',')
              .append(csvField(displayItem)).append(',')
              .append(fmt(e.amountCoins())).append(',')
              .append(e.timestampIso()).append('\n');

            if (e.amountCoins() >= 0) profit += e.amountCoins();
            else                      loss   += e.amountCoins();
            written++;
        }

        sb.append("# RUN,").append(runIso)
          .append(",parsed=").append(written)
          .append(",pages=").append(totalPages).append('\n');
        sb.append("# RUN_SUMMARY,profit=").append(fmt(profit))
          .append(",loss=").append(fmt(loss))
          .append(",net=").append(fmt(profit + loss)).append('\n');

        try {
            Files.writeString(targets.dataFile(), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }

        seen.addAll(newHashes);
        saveSeen(targets.seenFile(), seen);

        return written;
    }

    private void writeMonthlyReport(ExportTargets targets, List<Entry> newEntries) {
        if (newEntries.isEmpty()) return;

        Map<String, double[]> stats = new LinkedHashMap<>();

        for (Entry e : newEntries) {
            String displayItem = resolveAlias(e.item());
            double[] s = stats.computeIfAbsent(displayItem, k -> new double[6]);
            // "bought" = customer bought FROM you = your sale/income → total_sold_qty
            // "sold"   = you bought FROM someone = your expense      → total_bought_qty
            if ("bought".equals(e.verb())) {
                s[0] += e.quantity();    // total_sold_qty
                s[2] += e.amountCoins(); // income
                s[4]++;                  // times_sold
            } else {
                s[1] += e.quantity();    // total_bought_qty
                s[3] += e.amountCoins(); // expense
                s[5]++;                  // times_bought
            }
        }

        boolean newFile = !Files.exists(targets.monthlyFile(), LinkOption.NOFOLLOW_LINKS);
        StringBuilder sb = new StringBuilder();

        if (newFile) {
            sb.append("# MONTHLY_REPORT,").append(targets.monthlyFile().getFileName()).append('\n');
            sb.append("item,total_sold_qty,total_bought_qty,net_coins,times_sold,times_bought\n");
        }

        sb.append("# RUN,").append(runTimestampIso).append('\n');

        for (Map.Entry<String, double[]> kv : stats.entrySet()) {
            double[] s = kv.getValue();
            sb.append(csvField(kv.getKey())).append(',')
              .append((long) s[0]).append(',')
              .append((long) s[1]).append(',')
              .append(fmt(s[2] + s[3])).append(',')
              .append((long) s[4]).append(',')
              .append((long) s[5]).append('\n');
        }

        try {
            Files.writeString(targets.monthlyFile(), sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void postToWebhook(String webhookUrl, Path file, String filename, MinecraftClient client) {
        try {
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                notify(client, "[CSN] Monthly file missing, skipping post.");
                return;
            }
            byte[] csvBytes = Files.readAllBytes(file);
            String boundary = "CsnBoundary" + System.currentTimeMillis();

            byte[] bodyPart1 = ("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"files[0]\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] bodyPart2 = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

            byte[] body = new byte[bodyPart1.length + csvBytes.length + bodyPart2.length];
            System.arraycopy(bodyPart1, 0, body, 0, bodyPart1.length);
            System.arraycopy(csvBytes,  0, body, bodyPart1.length, csvBytes.length);
            System.arraycopy(bodyPart2, 0, body, bodyPart1.length + csvBytes.length, bodyPart2.length);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(webhookUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .build()
                    .send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                notify(client, "[CSN] ✅ Report posted to Discord!");
            } else {
                notify(client, "[CSN] Webhook failed (" + resp.statusCode() + ")");
            }
        } catch (Exception e) {
            notify(client, "[CSN] Could not post to Discord: " + e.getMessage());
        }
    }

    private static void notify(MinecraftClient client, String msg) {
        client.execute(() -> {
            if (client.player != null)
                client.player.sendMessage(Text.literal(msg), false);
        });
    }

    private static Set<String> loadSeen(Path seenFile) {
        if (!Files.exists(seenFile, LinkOption.NOFOLLOW_LINKS)) return new HashSet<>();
        try {
            return new HashSet<>(Files.readAllLines(seenFile, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return new HashSet<>();
        }
    }

    private static void saveSeen(Path seenFile, Set<String> hashes) {
        List<String> sorted = new ArrayList<>(hashes);
        Collections.sort(sorted);
        try {
            Files.write(seenFile, sorted, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String stableEntryHash(Entry e) {
        String raw = e.actor() + "|" + e.verb() + "|" + e.quantity()
                   + "|" + e.item() + "|" + e.amountCoins();
        return sha256(raw);
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest    = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return value;
        }
    }

    private static String fmt(double v) {
        return new BigDecimal(v)
                .setScale(2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private static String csvField(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static int parseOrZero(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return Integer.parseInt(s.trim()); }
        catch (NumberFormatException e) { return 0; }
    }

    private static double parseCoins(String s) {
        try { return Double.parseDouble(s.replace(",", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static void sendCommand(MinecraftClient client, String cmdNoSlash) {
        if (client.player != null)
            client.player.networkHandler.sendCommand(cmdNoSlash);
    }
}

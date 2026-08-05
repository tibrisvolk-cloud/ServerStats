package com.yourname.serverstats;

import io.javalin.Javalin;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

public class ServerStats extends JavaPlugin {

    private DatabaseManager db;
    private String indepDataPath;
    private YamlConfiguration linkedConfig;
    private YamlConfiguration levelsConfig;
    private YamlConfiguration questsProgressConfig;
    private YamlConfiguration indepConfig;

    private Map<Integer, List<TrophyInfo>> trophyMap = new HashMap<>();
    private Map<String, Integer> lastKnownLevels = new HashMap<>();
    private Map<String, Integer> lastKnownTotalXp = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        int port = getConfig().getInt("port", 8800);
        indepDataPath = getConfig().getString("indep_data_folder", "../IndepProfileBot");

        db = new DatabaseManager(getDataFolder());
        loadConfigs();
        loadTrophyInfo();

        // Таймер записи онлайна и XP/уровней каждые 5 минут
        new BukkitRunnable() {
            @Override
            public void run() {
                // онлайн
                int online = Bukkit.getOnlinePlayers().size();
                long now = System.currentTimeMillis() / 1000;
                db.insertOnlineCount(now, online);
                // XP и уровни
                scanLevelsAndLogHistory();
            }
        }.runTaskTimerAsynchronously(this, 0L, 5 * 60 * 20L);

        // ---------- Javalin REST API ----------
        Javalin app = Javalin.create().start(port);

        app.get("/api/online/now", ctx -> {
            List<String> players = Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
            ctx.json(Map.of("count", players.size(), "players", players));
        });

        app.get("/api/online", ctx -> {
            int hours = Integer.parseInt(ctx.queryParam("hours"));
            long since = System.currentTimeMillis() / 1000 - hours * 3600L;
            ResultSet rs = db.getOnlineHistory(since);
            List<Map<String, Object>> data = new ArrayList<>();
            try {
                while (rs.next()) {
                    data.add(Map.of("time", rs.getLong("timestamp"), "count", rs.getInt("count")));
                }
                rs.getStatement().getConnection().close();
            } catch (SQLException e) { e.printStackTrace(); }
            ctx.json(data);
        });

        app.get("/api/player/{discordId}/pawpass", ctx -> {
            String discordId = ctx.pathParam("discordId");
            UUID uuid = getUuidByDiscord(discordId);
            if (uuid == null) { ctx.status(404).json(Map.of("error", "Player not linked")); return; }
            String uuidStr = uuid.toString();
            int level = levelsConfig.getInt(uuidStr + ".level", 1);
            int xp = levelsConfig.getInt(uuidStr + ".xp", 0);
            int totalXp = levelsConfig.getInt(uuidStr + ".totalXp", 0);
            int nextXp = 100 * (level + 1) * (level + 1);
            String nextReward = indepConfig.getString("levels.reward-previews." + (level + 1), "Новые ресурсы и бонусы");
            ctx.json(Map.of("level", level, "xp", xp, "totalXp", totalXp, "nextXp", nextXp, "nextReward", nextReward));
        });

        app.get("/api/player/{discordId}/quests", ctx -> {
            String discordId = ctx.pathParam("discordId");
            String date = questsProgressConfig.getString("data." + discordId + ".date", "");
            List<Map<String, Object>> slots = new ArrayList<>();
            if (!date.isEmpty()) {
                ConfigurationSection slotSec = questsProgressConfig.getConfigurationSection("data." + discordId + ".slots");
                if (slotSec != null) {
                    for (String key : slotSec.getKeys(false)) {
                        slots.add(Map.of("questId", slotSec.getString(key + ".questId"),
                                         "progress", slotSec.getInt(key + ".progress"),
                                         "target", slotSec.getInt(key + ".target", 1),
                                         "completed", slotSec.getBoolean(key + ".completed")));
                    }
                }
            }
            ctx.json(Map.of("date", date, "slots", slots));
        });

        app.get("/api/player/{discordId}/xp-history", ctx -> {
            String discordId = ctx.pathParam("discordId");
            UUID uuid = getUuidByDiscord(discordId);
            if (uuid == null) { ctx.status(404).json(Map.of("error", "Not linked")); return; }
            int days = Integer.parseInt(ctx.queryParam("days"));
            long since = System.currentTimeMillis() / 1000 - days * 86400L;
            ResultSet rs = db.getXpHistory(uuid.toString(), since);
            Map<LocalDate, Integer> daily = new LinkedHashMap<>();
            try {
                Integer prev = null;
                while (rs.next()) {
                    long ts = rs.getLong("timestamp");
                    int total = rs.getInt("total_xp");
                    LocalDate date = Instant.ofEpochSecond(ts).atZone(ZoneId.of("Europe/Moscow")).toLocalDate();
                    if (prev != null) {
                        int gained = total - prev;
                        if (gained > 0) daily.merge(date, gained, Integer::sum);
                    }
                    prev = total;
                }
                rs.getStatement().getConnection().close();
            } catch (SQLException e) { e.printStackTrace(); }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map.Entry<LocalDate, Integer> e : daily.entrySet())
                out.add(Map.of("date", e.getKey().toString(), "xpGained", e.getValue()));
            ctx.json(out);
        });

        app.get("/api/player/{discordId}/level-history", ctx -> {
            String discordId = ctx.pathParam("discordId");
            UUID uuid = getUuidByDiscord(discordId);
            if (uuid == null) { ctx.status(404).json(Map.of("error", "Not linked")); return; }
            ResultSet rs = db.getLevelUpHistory(uuid.toString());
            List<Map<String, Object>> hist = new ArrayList<>();
            try {
                while (rs.next()) hist.add(Map.of("timestamp", rs.getLong("timestamp"), "level", rs.getInt("level")));
                rs.getStatement().getConnection().close();
            } catch (SQLException e) { e.printStackTrace(); }
            ctx.json(hist);
        });

        app.get("/api/player/{discordId}/trophies", ctx -> {
            String discordId = ctx.pathParam("discordId");
            UUID uuid = getUuidByDiscord(discordId);
            if (uuid == null) { ctx.status(404).json(Map.of("error", "Not linked")); return; }
            int level = levelsConfig.getInt(uuid.toString() + ".level", 1);
            List<TrophyInfo> trophies = new ArrayList<>();
            for (int lvl = 2; lvl <= level; lvl++)
                trophies.addAll(trophyMap.getOrDefault(lvl, Collections.emptyList()));
            ctx.json(trophies.stream().map(t -> Map.of("name", t.name, "headId", t.headId, "lore", t.lore)).collect(Collectors.toList()));
        });

        getLogger().info("ServerStats запущен на порту " + port);
    }

    private void loadConfigs() {
        File indepFolder = new File(getDataFolder().getParentFile(), indepDataPath);
        linkedConfig = YmlReader.load(new File(indepFolder, "linked.yml").getPath());
        levelsConfig = YmlReader.load(new File(indepFolder, "levels.yml").getPath());
        questsProgressConfig = YmlReader.load(new File(indepFolder, "quests_progress.yml").getPath());
        indepConfig = YmlReader.load(new File(indepFolder, "config.yml").getPath());
    }

    private void loadTrophyInfo() {
        ConfigurationSection rewards = indepConfig.getConfigurationSection("levels.level-rewards");
        if (rewards == null) return;
        for (String lvlStr : rewards.getKeys(false)) {
            int lvl = Integer.parseInt(lvlStr);
            List<String> cmds = rewards.getStringList(lvlStr);
            List<TrophyInfo> heads = new ArrayList<>();
            for (String cmd : cmds) {
                if (cmd.startsWith("give ") && cmd.contains("player_head")) {
                    String name = extract(cmd, "custom_name={text:\"");
                    String id = extract(cmd, "Head ID:");
                    heads.add(new TrophyInfo(name != null ? name : "Голова", id));
                }
            }
            trophyMap.put(lvl, heads);
        }
    }

    private String extract(String cmd, String key) {
        int i = cmd.indexOf(key);
        if (i == -1) return null;
        i += key.length();
        if (key.contains("text:\"")) i += 0; // уже на начале текста
        int j = cmd.indexOf('"', i + (key.contains("ID:") ? 0 : 0));
        if (j == -1) return cmd.substring(i).trim();
        return cmd.substring(i, j).trim();
    }

    private void scanLevelsAndLogHistory() {
        long now = System.currentTimeMillis() / 1000;
        for (String uuidStr : levelsConfig.getKeys(false)) {
            int level = levelsConfig.getInt(uuidStr + ".level", 1);
            int totalXp = levelsConfig.getInt(uuidStr + ".totalXp", 0);
            Integer prevTotal = lastKnownTotalXp.get(uuidStr);
            if (prevTotal == null || prevTotal != totalXp) {
                db.insertXpHistory(uuidStr, now, totalXp);
                lastKnownTotalXp.put(uuidStr, totalXp);
            }
            Integer prevLevel = lastKnownLevels.get(uuidStr);
            if (prevLevel != null && level > prevLevel) {
                db.insertLevelUp(uuidStr, now, level);
            }
            lastKnownLevels.put(uuidStr, level);
        }
    }

    private UUID getUuidByDiscord(String discordId) {
        for (String uuidStr : linkedConfig.getKeys(false))
            if (linkedConfig.getString(uuidStr, "").equals(discordId))
                return UUID.fromString(uuidStr);
        return null;
    }

    private static class TrophyInfo {
        String name, headId, lore;
        TrophyInfo(String n, String id) { name = n; headId = id; }
    }
}

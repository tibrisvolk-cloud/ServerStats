package com.yourname.serverstats;

import java.io.File;
import java.sql.*;

public class DatabaseManager {
    private final String url;

    public DatabaseManager(File dataFolder) {
        this.url = "jdbc:sqlite:" + new File(dataFolder, "online_stats.db").getAbsolutePath();
        init();
    }

    private void init() {
        try (Connection conn = DriverManager.getConnection(url);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS online_history (" +
                    "timestamp INTEGER PRIMARY KEY," +
                    "count INTEGER NOT NULL)");

            stmt.execute("CREATE TABLE IF NOT EXISTS xp_history (" +
                    "uuid TEXT," +
                    "timestamp INTEGER," +
                    "total_xp INTEGER," +
                    "PRIMARY KEY (uuid, timestamp))");

            stmt.execute("CREATE TABLE IF NOT EXISTS level_up_history (" +
                    "uuid TEXT," +
                    "timestamp INTEGER," +
                    "level INTEGER," +
                    "PRIMARY KEY (uuid, level))");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void insertOnlineCount(long timestamp, int count) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO online_history (timestamp, count) VALUES (?, ?)")) {
            stmt.setLong(1, timestamp);
            stmt.setInt(2, count);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ResultSet getOnlineHistory(long since) {
        try {
            Connection conn = DriverManager.getConnection(url);
            PreparedStatement stmt = conn.prepareStatement("SELECT timestamp, count FROM online_history WHERE timestamp >= ? ORDER BY timestamp");
            stmt.setLong(1, since);
            return stmt.executeQuery();
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void insertXpHistory(String uuid, long timestamp, int totalXp) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("INSERT OR REPLACE INTO xp_history (uuid, timestamp, total_xp) VALUES (?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setLong(2, timestamp);
            stmt.setInt(3, totalXp);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ResultSet getXpHistory(String uuid, long since) {
        try {
            Connection conn = DriverManager.getConnection(url);
            PreparedStatement stmt = conn.prepareStatement("SELECT timestamp, total_xp FROM xp_history WHERE uuid = ? AND timestamp >= ? ORDER BY timestamp");
            stmt.setString(1, uuid);
            stmt.setLong(2, since);
            return stmt.executeQuery();
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    public void insertLevelUp(String uuid, long timestamp, int level) {
        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement("INSERT OR IGNORE INTO level_up_history (uuid, timestamp, level) VALUES (?, ?, ?)")) {
            stmt.setString(1, uuid);
            stmt.setLong(2, timestamp);
            stmt.setInt(3, level);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public ResultSet getLevelUpHistory(String uuid) {
        try {
            Connection conn = DriverManager.getConnection(url);
            PreparedStatement stmt = conn.prepareStatement("SELECT timestamp, level FROM level_up_history WHERE uuid = ? ORDER BY level");
            stmt.setString(1, uuid);
            return stmt.executeQuery();
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }
}

package com.frames;

import net.minecraft.util.math.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public final class SignArchiveDb {
    private static volatile Connection conn;
    private static volatile Path currentPath;

    private SignArchiveDb() {}

    public static synchronized void init(Path dbPath) {
        try {
            if (dbPath == null) throw new IllegalArgumentException("dbPath is null");

            // If already open to same file, do nothing
            if (conn != null && currentPath != null && currentPath.equals(dbPath)) return;

            close();

            Files.createDirectories(dbPath.getParent());
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
            currentPath = dbPath;
            conn.setAutoCommit(true);

            try (Statement st = conn.createStatement()) {
                // Reduce locking issues with external readers (your GUI)
                st.execute("PRAGMA journal_mode=WAL;");
                st.execute("PRAGMA synchronous=NORMAL;");
                st.execute("PRAGMA temp_store=MEMORY;");
                st.execute("PRAGMA busy_timeout=5000;");

                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS signs (
                      sign_key     TEXT PRIMARY KEY,     -- unique key for dedupe
                      first_seen   INTEGER NOT NULL,
                      last_seen    INTEGER NOT NULL,
                      dimension    TEXT,
                      server       TEXT,
                      x            INTEGER NOT NULL,
                      y            INTEGER NOT NULL,
                      z            INTEGER NOT NULL,
                      front        TEXT NOT NULL,
                      back         TEXT NOT NULL,
                      content_key  TEXT NOT NULL
                    );
                """);

                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signs_last_seen ON signs(last_seen);");
                st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_signs_server ON signs(server);");
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("SignArchiveDb init failed: " + e.getMessage(), e);
        }
    }

    public static void insertSign(
        long firstSeenMs,
        String dimension,
        String server,
        BlockPos pos,
        String front,
        String back,
        String contentKey
    ) throws SQLException {
        if (conn == null) throw new SQLException("SignArchiveDb not initialized");
        if (pos == null) throw new SQLException("pos is null");

        long now = System.currentTimeMillis();
        String signKey = buildSignKey(dimension, server, pos, front, back);

        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO signs(
              sign_key, first_seen, last_seen, dimension, server, x, y, z, front, back, content_key
            ) VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(sign_key) DO UPDATE SET
              last_seen   = excluded.last_seen,
              dimension   = excluded.dimension,
              server      = excluded.server,
              x           = excluded.x,
              y           = excluded.y,
              z           = excluded.z,
              front       = excluded.front,
              back        = excluded.back,
              content_key = excluded.content_key
        """)) {
            ps.setString(1, signKey);
            ps.setLong(2, firstSeenMs > 0 ? firstSeenMs : now);
            ps.setLong(3, now);
            ps.setString(4, dimension);
            ps.setString(5, server);
            ps.setInt(6, pos.getX());
            ps.setInt(7, pos.getY());
            ps.setInt(8, pos.getZ());
            ps.setString(9, front == null ? "" : front);
            ps.setString(10, back == null ? "" : back);
            ps.setString(11, contentKey == null ? "" : contentKey);
            ps.executeUpdate();
        }
    }

    private static String buildSignKey(String dimension, String server, BlockPos pos, String front, String back) {
        // Stable dedupe key: where + what
        String dim = (dimension == null) ? "unknown" : dimension;
        String srv = (server == null) ? "unknown" : server;
        String f = (front == null) ? "" : front;
        String b = (back == null) ? "" : back;
        return srv + "|" + dim + "|" + pos.getX() + "|" + pos.getY() + "|" + pos.getZ() + "|" + f + "|" + b;
    }

    public static synchronized void close() {
        try {
            if (conn != null) conn.close();
        } catch (Exception ignored) {
        } finally {
            conn = null;
            currentPath = null;
        }
    }
}

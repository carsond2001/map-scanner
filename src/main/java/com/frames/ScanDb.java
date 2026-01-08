package com.frames;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;

public final class ScanDb {
    private final Connection conn;

    public ScanDb(Path dbPath) throws Exception {
        Files.createDirectories(dbPath.getParent());
        conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath());
        conn.setAutoCommit(true);
        initPragmas();
        init();
    }

    private void initPragmas() throws SQLException {
        try (Statement st = conn.createStatement()) {
            // Helps a lot with concurrent reads while Minecraft writes
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("PRAGMA temp_store=MEMORY;");
            st.execute("PRAGMA busy_timeout=5000;"); // wait up to 5s if locked
        }
    }

    private void init() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("""
                CREATE TABLE IF NOT EXISTS maps (
                  map_id      TEXT PRIMARY KEY,
                  dimension   TEXT NOT NULL,
                  x           INTEGER NOT NULL,
                  y           INTEGER NOT NULL,
                  z           INTEGER NOT NULL,
                  first_seen  INTEGER NOT NULL,
                  last_seen   INTEGER NOT NULL,
                  png         BLOB NOT NULL,
                  colors      BLOB NOT NULL
                );
            """);
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_maps_last_seen ON maps(last_seen);");

            // Optional: if you later want to store signs in same DB too,
            // you can add a signs table here as well.
        }
    }

    public void upsertMap(String mapId, String dimension, int x, int y, int z, byte[] png, byte[] colors) throws SQLException {
        long now = System.currentTimeMillis();
        try (PreparedStatement ps = conn.prepareStatement("""
            INSERT INTO maps(map_id, dimension, x, y, z, first_seen, last_seen, png, colors)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(map_id) DO UPDATE SET
              dimension = excluded.dimension,
              x = excluded.x,
              y = excluded.y,
              z = excluded.z,
              last_seen = excluded.last_seen,
              png = excluded.png,
              colors = excluded.colors
        """)) {
            ps.setString(1, mapId);
            ps.setString(2, dimension);
            ps.setInt(3, x);
            ps.setInt(4, y);
            ps.setInt(5, z);
            ps.setLong(6, now);
            ps.setLong(7, now);
            ps.setBytes(8, png);
            ps.setBytes(9, colors);
            ps.executeUpdate();
        }
    }

    public void close() {
        try { conn.close(); } catch (Exception ignored) {}
    }
}

package com.englishpods.listening;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ListeningRepository {
    private final JdbcTemplate jdbc;

    public ListeningRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void merge(List<ListeningPeriod> periods) {
        for (var period : periods) {
            // Take SQLite's write lock before reading neighbors, so concurrent uploads cannot count twice.
            jdbc.update("""
                INSERT INTO listening_periods (started_at_ms, ended_at_ms) VALUES (?, ?)
                ON CONFLICT (started_at_ms) DO UPDATE SET ended_at_ms=max(ended_at_ms, excluded.ended_at_ms)
                """, period.start(), period.end());
            var merged = jdbc.queryForObject("""
                SELECT min(started_at_ms), max(ended_at_ms) FROM listening_periods
                WHERE started_at_ms <= ? AND ended_at_ms >= ?
                """, (row, index) -> new ListeningPeriod(row.getLong(1), row.getLong(2)), period.end(), period.start());
            jdbc.update("""
                DELETE FROM listening_periods WHERE started_at_ms <= ? AND ended_at_ms >= ?
                """, period.end(), period.start());
            jdbc.update("INSERT INTO listening_periods VALUES (?, ?)", merged.start(), merged.end());
        }
    }

    public List<ListeningPeriod> forSummary(long weekStart, long weekEnd, long todayStart, long todayEnd) {
        return jdbc.query("""
            SELECT started_at_ms, ended_at_ms FROM listening_periods
            WHERE (ended_at_ms > ? AND started_at_ms < ?) OR (ended_at_ms > ? AND started_at_ms < ?)
            """, (row, index) -> new ListeningPeriod(row.getLong(1), row.getLong(2)),
                weekStart, weekEnd, todayStart, todayEnd);
    }
}

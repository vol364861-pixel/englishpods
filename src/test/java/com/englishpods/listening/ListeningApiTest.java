package com.englishpods.listening;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "story.worker.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext
class ListeningApiTest {
    @TempDir static Path directory;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("listening.db"));
    }

    @Autowired MockMvc mvc;
    @Autowired ListeningRepository repository;
    @Autowired ListeningService service;
    @Autowired JdbcTemplate jdbc;

    @AfterAll
    static void close(@Autowired HikariDataSource dataSource) { dataSource.close(); }

    @BeforeEach
    void clear() { jdbc.update("DELETE FROM listening_periods"); }

    @Test
    void recordsWallTimeOnceAcrossRetriesAndOverlappingTabs() throws Exception {
        Instant now = Instant.now();
        var beijingNow = now.atZone(ZoneId.of("Asia/Shanghai"));
        // Use the middle of a completed Beijing day so this test also works around midnight.
        long start = beijingNow.toLocalDate().minusDays(1).atTime(12, 0).atZone(beijingNow.getZone())
                .toInstant().toEpochMilli();
        var first = new ListeningPeriod(start, start + 2000);
        String body = "[{\"start\":%d,\"end\":%d}]".formatted(first.start(), first.end());
        for (int index = 0; index < 2; index++) {
            mvc.perform(post("/api/listening").contentType("application/json").content(body))
                    .andExpect(status().isNoContent());
        }
        mvc.perform(post("/api/listening").contentType("application/json")
                .content("[{\"start\":%d,\"end\":%d}]".formatted(start + 500, start + 3000)))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listening_periods", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ended_at_ms-started_at_ms FROM listening_periods", Long.class))
                .isEqualTo(3000);
        mvc.perform(get("/api/listening")).andExpect(status().isOk())
                .andExpect(jsonPath("$.today.date").value(beijingNow.toLocalDate().toString()))
                .andExpect(jsonPath("$.days.length()").value(7));
    }

    @Test
    void splitsListeningAtBeijingMidnightAndShowsEveryDayOfEachWeek() {
        long monday = Instant.parse("2026-11-22T16:00:00Z").toEpochMilli();
        repository.merge(List.of(new ListeningPeriod(monday - 1500, monday + 2500)));
        Instant now = Instant.ofEpochMilli(monday + 3000);
        var first = service.summary(1, now);
        var second = service.summary(null, now);
        assertThat(first.days()).hasSize(7);
        assertThat(first.days().getFirst()).isEqualTo(new ListeningService.Day("2026-11-16", 0));
        assertThat(first.days().getLast()).isEqualTo(new ListeningService.Day("2026-11-22", 1500));
        assertThat(first.today()).isEqualTo(new ListeningService.Day("2026-11-23", 2500));
        assertThat(second.week()).isEqualTo(2);
        assertThat(second.days().getFirst()).isEqualTo(first.today());
        assertThat(second.days().getLast()).isEqualTo(new ListeningService.Day("2026-11-29", 0));
    }

    @Test
    void rejectsInvalidIntervalsAndWeeks() throws Exception {
        mvc.perform(post("/api/listening").contentType("application/json").content("[{\"start\":5,\"end\":4}]"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/listening").param("week", "0")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM listening_periods", Integer.class)).isZero();
    }
}

package com.englishpods.task;

import com.englishpods.task.repository.TaskSchemaMigration;
import com.englishpods.task.repository.TaskRepository;
import com.englishpods.task.service.TaskService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.assertj.core.api.Assertions.assertThat;

class TaskSchemaMigrationTest {
    @TempDir Path directory;

    @Test
    void migratesOriginalDatabaseWithoutLosingTextAndCanReopenRepeatedly() {
        Path file = directory.resolve("legacy.db");
        String id = UUID.randomUUID().toString();
        String text = "  Sam's “book”\r\n😀  ";
        try (var source = open(file)) {
            var jdbc = new JdbcTemplate(source);
            jdbc.execute("""
                CREATE TABLE story_tasks(id TEXT PRIMARY KEY NOT NULL,title TEXT NOT NULL,
                source_text TEXT NOT NULL,status TEXT NOT NULL CHECK(status='CREATED'),
                created_at TEXT NOT NULL,updated_at TEXT NOT NULL)
                """);
            jdbc.update("INSERT INTO story_tasks VALUES(?,?,?,'CREATED',?,?)", id, " Title ", text,
                    "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z");
        }
        for (int restart = 0; restart < 3; restart++) {
            try (var source = open(file)) {
                // Boot's schema.sql runs before the explicit migration.
                new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(source);
                var jdbc = new JdbcTemplate(source);
                TaskSchemaMigration.migrate(jdbc);
                var repository = new TaskRepository(jdbc);
                var task = new TaskService(repository).get(id);
                assertThat(task.text()).isEqualTo(text);
                assertThat(task.title()).isEqualTo(" Title ");
                assertThat(task.attempt()).isEqualTo(restart == 0 ? 0 : 1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM story_tasks", Integer.class)).isEqualTo(1);
                if (restart == 0) assertThat(repository.enqueue(task.id())).isTrue();
            }
        }
    }

    private HikariDataSource open(Path file) {
        var config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + file);
        config.setMaximumPoolSize(1);
        return new HikariDataSource(config);
    }
}

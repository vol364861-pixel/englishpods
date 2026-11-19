package com.englishpods.task;

import com.englishpods.task.dto.CreateTaskRequest;
import com.englishpods.task.model.StoryTask;
import com.englishpods.task.repository.TaskRepository;
import com.englishpods.task.service.TaskService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import static org.assertj.core.api.Assertions.assertThat;

class TaskPersistenceTest {
    @TempDir Path directory;

    @Test
    void persistsTaskAfterClosingAndReopeningFileDatabase() throws Exception {
        Path database = directory.resolve("persisted-story.db");
        StoryTask created;
        HikariDataSource first = open(database);
        try (first) {
            initializeSchema(first);
            TaskService service = new TaskService(new TaskRepository(new JdbcTemplate(first)));
            created = service.create(new CreateTaskRequest("  Sam's book  ", "  “That's good.”\r\n😀  "));
        }
        assertThat(first.isClosed()).isTrue();
        assertThat(Files.size(database)).isPositive();

        try (HikariDataSource reopened = open(database)) {
            // Startup runs the same schema again; it must preserve existing rows.
            initializeSchema(reopened);
            JdbcTemplate jdbc = new JdbcTemplate(reopened);
            TaskService service = new TaskService(new TaskRepository(jdbc));
            assertThat(service.get(created.id().toString())).isEqualTo(created);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM story_tasks", Long.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("PRAGMA busy_timeout", Integer.class)).isEqualTo(5000);
        }
    }

    private HikariDataSource open(Path path) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl("jdbc:sqlite:" + path);
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(1);
        config.setConnectionInitSql("PRAGMA busy_timeout=5000");
        return new HikariDataSource(config);
    }

    private void initializeSchema(HikariDataSource dataSource) {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(dataSource);
    }
}

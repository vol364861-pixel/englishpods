package com.englishpods.task.repository;

import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.HashSet;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds the original CREATED-only table atomically; preserves all original rows. */
@Component("taskSchemaMigration")
@DependsOnDatabaseInitialization
public class TaskSchemaMigration {
    public TaskSchemaMigration(JdbcTemplate jdbc) {
        migrate(jdbc);
    }

    public static void migrate(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var columns = new HashSet<String>();
            try (Statement statement = connection.createStatement();
                 var result = statement.executeQuery("PRAGMA table_info(story_tasks)")) {
                while (result.next()) columns.add(result.getString("name"));
            }
            if (columns.contains("attempt")) {
                addCheckpoints(connection, columns);
                return null;
            }
            String schema;
            try (var stream = new ClassPathResource("schema.sql").getInputStream()) {
                schema = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (java.io.IOException exception) {
                throw new java.sql.SQLException("Cannot read task schema", exception);
            }
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                if (!columns.isEmpty()) statement.executeUpdate("ALTER TABLE story_tasks RENAME TO story_tasks_legacy_v1");
                statement.executeUpdate(schema);
                if (!columns.isEmpty()) {
                    statement.executeUpdate("""
                        INSERT INTO story_tasks(id,title,source_text,status,created_at,updated_at)
                        SELECT id,title,source_text,status,created_at,updated_at FROM story_tasks_legacy_v1
                        """);
                    statement.executeUpdate("DROP TABLE story_tasks_legacy_v1");
                }
                connection.commit();
            } catch (java.sql.SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
    }
    private static void addCheckpoints(java.sql.Connection connection, java.util.Set<String> columns) throws java.sql.SQLException {
        // Additive migration also runs for databases that already have the MVP attempt column.
        try (Statement statement = connection.createStatement()) {
            for (String column : java.util.List.of("checkpoint_source_hash", "checkpoint_config_hash",
                    "checkpoint_metadata", "initial_segments_json", "reviewed_segments_json",
                    "completed_stage", "checkpoint_origin", "reused_stage")) {
                if (!columns.contains(column)) statement.executeUpdate("ALTER TABLE story_tasks ADD COLUMN " + column + " TEXT");
            }
        }
    }
}

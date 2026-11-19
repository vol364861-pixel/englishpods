package com.englishpods.generation;

import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

/** Durable validated LLM output; no credentials, raw HTTP responses or audio URLs. */
@Repository
@DependsOn("taskSchemaMigration")
public class SegmentationCheckpointRepository {
    public record Saved(String sourceHash, String configHash, String initial, String reviewed, String origin) {}
    private final JdbcTemplate jdbc;
    public SegmentationCheckpointRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Optional<Saved> find(UUID id, String text) {
        return jdbc.query("SELECT * FROM story_tasks WHERE id=? AND source_text=?",
                (r, n) -> new Saved(r.getString("checkpoint_source_hash"), r.getString("checkpoint_config_hash"),
                        r.getString("initial_segments_json"), r.getString("reviewed_segments_json"), r.getString("checkpoint_origin")),
                id.toString(), text).stream().findFirst();
    }

    void reset(UUID id, String sourceHash, String configHash, String metadata) {
        jdbc.update("""
            UPDATE story_tasks SET checkpoint_source_hash=?,checkpoint_config_hash=?,checkpoint_metadata=?,
                initial_segments_json=NULL,reviewed_segments_json=NULL,completed_stage=NULL,checkpoint_origin=NULL,reused_stage=NULL
            WHERE id=?
            """, sourceHash, configHash, metadata, id.toString());
    }

    void save(UUID id, String sourceHash, String configHash, String initial, String reviewed, String origin) {
        // Each statement commits before any following provider call; a crash cannot publish a partial JSON value.
        jdbc.update("""
            UPDATE story_tasks SET initial_segments_json=?,reviewed_segments_json=?,completed_stage=?,checkpoint_origin=?
            WHERE id=? AND checkpoint_source_hash=? AND checkpoint_config_hash=?
            """, initial, reviewed, reviewed == null ? "SEGMENTING" : "REVIEWING", origin,
                id.toString(), sourceHash, configHash);
    }

    void reused(UUID id, String stage) {
        jdbc.update("UPDATE story_tasks SET reused_stage=? WHERE id=?", stage, id.toString());
    }
}

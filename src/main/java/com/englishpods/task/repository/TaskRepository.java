package com.englishpods.task.repository;

import com.englishpods.task.model.StoryTask;
import com.englishpods.task.model.TaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@DependsOn("taskSchemaMigration")
public class TaskRepository {
    private final JdbcTemplate jdbc;
    private static final RowMapper<StoryTask> MAPPER = (r, row) -> new StoryTask(
            UUID.fromString(r.getString("id")), r.getString("title"), r.getString("source_text"),
            TaskStatus.valueOf(r.getString("status")), Instant.parse(r.getString("created_at")),
            Instant.parse(r.getString("updated_at")), r.getString("stage"), r.getString("error"), r.getInt("attempt"), r.getString("completed_stage"), r.getString("checkpoint_origin"), r.getString("reused_stage"));

    public TaskRepository(JdbcTemplate jdbcTemplate) { this.jdbc = jdbcTemplate; }

    public void save(StoryTask task) {
        jdbc.update("""
            INSERT INTO story_tasks (id,title,source_text,status,created_at,updated_at)
            VALUES (?,?,?,?,?,?)
            """, task.id().toString(), task.title(), task.text(), task.status().name(),
                task.createdAt().toString(), task.updatedAt().toString());
    }

    public Optional<StoryTask> findById(UUID id) {
        return jdbc.query("SELECT * FROM story_tasks WHERE id=?", MAPPER, id.toString()).stream().findFirst();
    }

    public List<StoryTask> latest() {
        return jdbc.query("SELECT * FROM story_tasks ORDER BY created_at DESC,id DESC LIMIT 30", MAPPER);
    }

    public List<StoryTask> search(String keyword) {
        return jdbc.query("""
            SELECT * FROM story_tasks
            WHERE instr(lower(title), lower(?)) > 0 OR instr(lower(source_text), lower(?)) > 0
            ORDER BY created_at DESC,id DESC LIMIT 30
            """, MAPPER, keyword, keyword);
    }

    @Transactional
    public boolean removeAndQueueAudioCleanup(UUID id) {
        String taskId = id.toString();
        int removed = jdbc.update("""
            DELETE FROM story_tasks WHERE id=? AND status IN ('CREATED','QUEUED','COMPLETED','FAILED')
            """, taskId);
        if (removed == 0) return false;
        jdbc.update("INSERT INTO story_audio_cleanup (task_id) VALUES (?)", taskId);
        return true;
    }

    public List<UUID> pendingAudioCleanup() {
        return jdbc.query("SELECT task_id FROM story_audio_cleanup", (row, index) -> UUID.fromString(row.getString(1)));
    }

    public void finishAudioCleanup(UUID id) {
        jdbc.update("DELETE FROM story_audio_cleanup WHERE task_id=?", id.toString());
    }

    public boolean enqueue(UUID id) {
        return jdbc.update("""
            UPDATE story_tasks SET status='QUEUED',stage=NULL,error=NULL,attempt=attempt+1,
                result_json=NULL,audio_path=NULL,reused_stage=NULL,updated_at=? WHERE id=? AND status IN ('CREATED','FAILED')
            """, now(), id.toString()) == 1;
    }

    public Optional<StoryTask> claimNext() {
        var queued = jdbc.query("SELECT * FROM story_tasks WHERE status='QUEUED' ORDER BY updated_at,id LIMIT 1", MAPPER);
        if (queued.isEmpty()) return Optional.empty();
        StoryTask task = queued.getFirst();
        int count = jdbc.update("""
            UPDATE story_tasks SET status='SEGMENTING',stage='SEGMENTING',updated_at=?
            WHERE id=? AND status='QUEUED' AND attempt=?
            """, now(), task.id().toString(), task.attempt());
        return count == 1 ? findById(task.id()) : Optional.empty();
    }

    public void stage(StoryTask task, String stage) {
        jdbc.update("UPDATE story_tasks SET status=?,stage=?,updated_at=? WHERE id=? AND attempt=?",
                stage, stage, now(), task.id().toString(), task.attempt());
    }

    public void complete(StoryTask task, String json, String audioPath) {
        jdbc.update("""
            UPDATE story_tasks SET status='COMPLETED',error=NULL,result_json=?,audio_path=?,updated_at=?
            WHERE id=? AND attempt=?
            """, json, audioPath, now(), task.id().toString(), task.attempt());
    }

    public void fail(StoryTask task, String message) {
        jdbc.update("UPDATE story_tasks SET status='FAILED',error=?,updated_at=? WHERE id=? AND attempt=?",
                message, now(), task.id().toString(), task.attempt());
    }

    public void recoverInterrupted() {
        jdbc.update("""
            UPDATE story_tasks SET status='FAILED',error='Generation was interrupted by an application restart. Please retry.',updated_at=?
            WHERE status IN ('SEGMENTING','REVIEWING','SYNTHESIZING','ASSEMBLING')
            """, now());
    }

    public Optional<String> result(UUID id) {
        return jdbc.query("SELECT result_json FROM story_tasks WHERE id=? AND result_json IS NOT NULL",
                (r, row) -> r.getString(1), id.toString()).stream().findFirst();
    }

    public Optional<String> audio(UUID id) {
        return jdbc.query("SELECT audio_path FROM story_tasks WHERE id=? AND audio_path IS NOT NULL",
                (r, row) -> r.getString(1), id.toString()).stream().findFirst();
    }

    private String now() { return Instant.now().toString(); }
}

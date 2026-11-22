CREATE TABLE IF NOT EXISTS story_tasks (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    source_text TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('CREATED','QUEUED','SEGMENTING','REVIEWING','SYNTHESIZING','ASSEMBLING','COMPLETED','FAILED')),
    created_at TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    stage TEXT,
    error TEXT,
    attempt INTEGER NOT NULL DEFAULT 0,
    result_json TEXT,
    audio_path TEXT,
    checkpoint_source_hash TEXT,
    checkpoint_config_hash TEXT,
    checkpoint_metadata TEXT,
    initial_segments_json TEXT,
    reviewed_segments_json TEXT,
    completed_stage TEXT,
    checkpoint_origin TEXT,
    reused_stage TEXT
);

CREATE TABLE IF NOT EXISTS story_audio_cleanup (
    task_id TEXT PRIMARY KEY NOT NULL
);

CREATE TABLE IF NOT EXISTS listening_periods (
    started_at_ms INTEGER PRIMARY KEY NOT NULL,
    ended_at_ms INTEGER NOT NULL CHECK (ended_at_ms > started_at_ms)
);

CREATE INDEX IF NOT EXISTS listening_periods_end ON listening_periods (ended_at_ms);

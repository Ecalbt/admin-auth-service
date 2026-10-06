-- =============================================================================
-- V3: Transactional Outbox Schema for Event Publishing
-- =============================================================================

CREATE TABLE outbox_events (
    id VARCHAR(36) PRIMARY KEY,
    topic VARCHAR(100) NOT NULL,
    partition_key VARCHAR(100) NOT NULL,
    event_type VARCHAR(50) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at, next_attempt_at) WHERE status = 'PENDING';

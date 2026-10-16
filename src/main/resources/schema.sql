CREATE TABLE IF NOT EXISTS cp_account (
    username VARCHAR(100) PRIMARY KEY,
    password_hash VARCHAR(100) NOT NULL,
    roles_json TEXT NOT NULL,
    org_tags_json TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS cp_document (
    id VARCHAR(36) PRIMARY KEY,
    title VARCHAR(512) NOT NULL,
    category VARCHAR(100) NOT NULL,
    org_tag VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL,
    file_name VARCHAR(512) NOT NULL,
    created_at BIGINT NOT NULL,
    chunk_count INT NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    ingest_owner VARCHAR(36),
    ingest_lease_until BIGINT,
    INDEX idx_document_scope (org_tag, category, status, group_name)
);

CREATE TABLE IF NOT EXISTS cp_chunk (
    id VARCHAR(100) PRIMARY KEY,
    document_id VARCHAR(36) NOT NULL,
    title VARCHAR(512) NOT NULL,
    content LONGTEXT NOT NULL,
    category VARCHAR(100) NOT NULL,
    org_tag VARCHAR(100) NOT NULL,
    chunk_no INT NOT NULL,
    INDEX idx_chunk_document (document_id, chunk_no)
);

CREATE TABLE IF NOT EXISTS cp_document_outbox (
    document_id VARCHAR(36) PRIMARY KEY,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    next_attempt_at BIGINT NOT NULL,
    lease_until BIGINT,
    claim_token VARCHAR(36),
    last_error VARCHAR(1000),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    sent_at BIGINT,
    INDEX idx_document_outbox_due (status, next_attempt_at),
    INDEX idx_document_outbox_lease (status, lease_until)
);

CREATE TABLE IF NOT EXISTS cp_practice_session (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    state_json LONGTEXT NOT NULL,
    INDEX idx_practice_username (username)
);

CREATE TABLE IF NOT EXISTS cp_agent_run (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    result_json LONGTEXT NOT NULL,
    INDEX idx_agent_run_username (username)
);

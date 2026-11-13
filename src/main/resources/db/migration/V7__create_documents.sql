-- Maintenance manual library for retrieval (RAG). PostgreSQL adds a full-text column in db/vendor/postgresql.
CREATE TABLE documents (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    title           VARCHAR(255) NOT NULL,
    filename        VARCHAR(255) NOT NULL,
    content_type    VARCHAR(120),
    machine_type    VARCHAR(30) REFERENCES device_types (code),
    sha256          VARCHAR(64) NOT NULL UNIQUE,
    uploaded_by     VARCHAR(80) NOT NULL,
    uploaded_at     TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE document_chunks (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    document_id     BIGINT NOT NULL REFERENCES documents (id) ON DELETE CASCADE,
    chunk_index     INTEGER NOT NULL,
    section         VARCHAR(255),
    content         TEXT NOT NULL,
    CONSTRAINT uq_chunk_position UNIQUE (document_id, chunk_index)
);

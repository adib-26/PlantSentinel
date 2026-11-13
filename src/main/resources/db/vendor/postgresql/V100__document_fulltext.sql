-- PostgreSQL only: full-text search for manual retrieval (RAG). H2 demos use the in-memory BM25 index instead.
-- Weights: section heading (A) > document title (B) > body (D). Maintained by ManualRepository on insert.
ALTER TABLE document_chunks ADD COLUMN search_vector tsvector;

UPDATE document_chunks c
SET search_vector = setweight(to_tsvector('english', coalesce(c.section, '')), 'A')
                 || setweight(to_tsvector('english', d.title), 'B')
                 || to_tsvector('english', c.content)
FROM documents d
WHERE d.id = c.document_id;

CREATE INDEX idx_document_chunks_fts ON document_chunks USING GIN (search_vector);

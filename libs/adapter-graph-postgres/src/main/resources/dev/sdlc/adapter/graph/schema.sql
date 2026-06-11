CREATE TABLE IF NOT EXISTS nodes (
    id          varchar(16) PRIMARY KEY,
    type        varchar(32)  NOT NULL,
    title       text,
    repo_path   text         NOT NULL,
    blob_sha    varchar(64)  NOT NULL,
    status      varchar(32)  NOT NULL,
    version     int          NOT NULL,
    provenance  jsonb        NOT NULL,
    created_at  timestamptz  NOT NULL,
    updated_at  timestamptz  NOT NULL
);
CREATE TABLE IF NOT EXISTS edges (
    id                          varchar(64) PRIMARY KEY,
    type                        varchar(32) NOT NULL,
    from_id                     varchar(16) NOT NULL,
    to_id                       varchar(16) NOT NULL,
    upstream_blob_sha_at_link   varchar(64) NOT NULL,
    link_status                 varchar(16) NOT NULL,
    established_by              text        NOT NULL,
    validated_at                timestamptz,
    validated_by                text
);
CREATE INDEX IF NOT EXISTS idx_edges_to   ON edges (to_id, link_status);
CREATE INDEX IF NOT EXISTS idx_edges_from ON edges (from_id);
CREATE INDEX IF NOT EXISTS idx_nodes_status ON nodes (status);

CREATE TABLE short_url (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code         VARCHAR(16)   NOT NULL,
    original_url VARCHAR(2048) NOT NULL,
    click_count  BIGINT        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_short_url_code UNIQUE (code)
);

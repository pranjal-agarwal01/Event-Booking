-- Week 2: accounts and refresh tokens.

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    full_name     VARCHAR(150) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'USER',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_user_role CHECK (role IN ('USER', 'ADMIN'))
);

CREATE TABLE refresh_tokens (
    id         BIGSERIAL PRIMARY KEY,
    token      UUID        NOT NULL UNIQUE,
    user_id    BIGINT      NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked    BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id);

-- Demo accounts. Both passwords are "password123" - fine for a demo database,
-- never for anything real. Hashes are BCrypt, cost 10.
INSERT INTO users (email, password_hash, full_name, role) VALUES
    ('admin@booking.dev', '$2a$10$cc7OcuImGdNP2rG25HOYuOBw1/eVVU0XhIxTA1J69t2umrtVOWtTS', 'Demo Admin', 'ADMIN'),
    ('user@booking.dev',  '$2a$10$5Pw4pBxafWcHsULXIzSscOCZrIwFRjLwz3ze5r3ao/klsdUBzLam6',  'Demo User',  'USER');

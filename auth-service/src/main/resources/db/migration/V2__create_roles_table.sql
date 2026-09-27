-- Create roles table for role-based access control

CREATE TABLE IF NOT EXISTS roles (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description VARCHAR(255),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Index on UNIQUE column 'name' is created automatically by Postgres.
-- Explicit index added below only if non-unique lookup behavior is desired:
-- CREATE INDEX IF NOT EXISTS idx_roles_name ON roles (name);

-- Insert default roles
INSERT INTO roles (name, description) VALUES
     ('ROLE_ADMIN', 'Administrator role with full access'),
     ('ROLE_USER', 'Regular user role'),
     ('ROLE_MODERATOR', 'Moderator role')
    ON CONFLICT (name) DO NOTHING;
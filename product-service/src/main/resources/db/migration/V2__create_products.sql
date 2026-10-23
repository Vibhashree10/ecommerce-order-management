-- Create products table
CREATE TABLE IF NOT EXISTS products (
    id BIGSERIAL PRIMARY KEY,
    sku VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    price NUMERIC(10, 2) NOT NULL,
    quantity INT NOT NULL DEFAULT 0,
    category_id BIGINT NOT NULL,
    manufacturer VARCHAR(255),
    image_url VARCHAR(500),
    active BOOLEAN NOT NULL DEFAULT true,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (category_id) REFERENCES categories(id) ON DELETE RESTRICT
    );

-- Create indexes separately
CREATE INDEX IF NOT EXISTS idx_sku ON products (sku);
CREATE INDEX IF NOT EXISTS idx_category_id ON products (category_id);
CREATE INDEX IF NOT EXISTS idx_active ON products (active);
CREATE INDEX IF NOT EXISTS idx_name ON products (name);
CREATE INDEX IF NOT EXISTS idx_quantity ON products (quantity);
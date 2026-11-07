-- Card terminal registration
CREATE TABLE card_terminal (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INTEGER NOT NULL DEFAULT 20007,
    password VARCHAR(20) NOT NULL DEFAULT '000000',
    terminal_id VARCHAR(50),
    software_version VARCHAR(50),
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    is_connected BOOLEAN NOT NULL DEFAULT FALSE,
    is_busy BOOLEAN NOT NULL DEFAULT FALSE,
    shop_info_id BIGINT NOT NULL REFERENCES shop_info(shop_id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Card transaction results (linked to payment after bill creation)
CREATE TABLE card_transaction (
    id BIGSERIAL PRIMARY KEY,
    payment_id BIGINT REFERENCES payment(id),
    card_terminal_id BIGINT NOT NULL REFERENCES card_terminal(id),
    trace_number VARCHAR(50) NOT NULL,
    receipt_number VARCHAR(50),
    amount_cents BIGINT NOT NULL,
    currency VARCHAR(3) NOT NULL DEFAULT 'EUR',
    card_type INTEGER,
    card_type_name VARCHAR(100),
    pan_last4 VARCHAR(4),
    expiry_date VARCHAR(4),
    aid VARCHAR(50),
    payment_type VARCHAR(20) NOT NULL,
    result_code INTEGER NOT NULL DEFAULT 0,
    receipt_text_merchant TEXT,
    receipt_text_customer TEXT,
    transaction_time TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Daily closing settlement per terminal
CREATE TABLE card_settlement (
    id BIGSERIAL PRIMARY KEY,
    card_terminal_id BIGINT NOT NULL REFERENCES card_terminal(id),
    zbon_id BIGINT REFERENCES zbon(zbon_id),
    settlement_time TIMESTAMP NOT NULL,
    success BOOLEAN NOT NULL,
    result_code INTEGER NOT NULL DEFAULT 0,
    receipt_text TEXT,
    total_amount_cents BIGINT NOT NULL DEFAULT 0,
    total_transaction_count INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Per-card-type breakdown within a settlement
CREATE TABLE card_settlement_detail (
    id BIGSERIAL PRIMARY KEY,
    card_settlement_id BIGINT NOT NULL REFERENCES card_settlement(id) ON DELETE CASCADE,
    card_type INTEGER NOT NULL,
    card_type_name VARCHAR(100) NOT NULL,
    transaction_count INTEGER NOT NULL DEFAULT 0,
    total_amount_cents BIGINT NOT NULL DEFAULT 0
);

-- ZVT feature toggle is managed via shop_configuration table (ConfigKey.ZVT_CONFIG)
-- No schema change needed — default value is provided by ConfigKey enum

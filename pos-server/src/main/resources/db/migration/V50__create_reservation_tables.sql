-- Reservation management tables
CREATE TABLE IF NOT EXISTS reservations (
    id BIGSERIAL PRIMARY KEY,
    customer_id UUID REFERENCES customers(customer_id) ON DELETE SET NULL,
    guest_name VARCHAR(100) NOT NULL,
    guest_phone VARCHAR(20),
    party_size INTEGER NOT NULL CHECK (party_size >= 1),
    reservation_date DATE NOT NULL,
    reservation_time TIME NOT NULL,
    duration_minutes INTEGER,
    status VARCHAR(20) NOT NULL DEFAULT 'CONFIRMED',
    notes VARCHAR(500),
    created_by UUID NOT NULL REFERENCES employees(employee_id),
    updated_by UUID REFERENCES employees(employee_id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reservations_date ON reservations(reservation_date);
CREATE INDEX idx_reservations_status ON reservations(status);
CREATE INDEX idx_reservations_customer ON reservations(customer_id);

CREATE TABLE IF NOT EXISTS reservation_rules (
    id BIGSERIAL PRIMARY KEY,
    rule_date DATE NOT NULL UNIQUE,
    rule_type VARCHAR(20) NOT NULL,
    duration_minutes INTEGER,
    time_slots TEXT,
    start_time TIME,
    end_time TIME,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_reservation_rules_date ON reservation_rules(rule_date);

-- Opening Hours: Weekly schedule
CREATE TABLE opening_hours (
    id BIGSERIAL PRIMARY KEY,
    day_of_week INTEGER NOT NULL,
    open_time TIME NOT NULL,
    close_time TIME NOT NULL,
    is_closed BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_opening_hours_day UNIQUE (day_of_week),
    CONSTRAINT chk_day_of_week CHECK (day_of_week BETWEEN 0 AND 6)
);

-- Opening Hours: Date-specific overrides
CREATE TABLE opening_hours_override (
    id BIGSERIAL PRIMARY KEY,
    override_date DATE NOT NULL UNIQUE,
    open_time TIME,
    close_time TIME,
    is_closed BOOLEAN NOT NULL DEFAULT false,
    reason VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Combinable Tables: Join table with composite PK
CREATE TABLE combinable_tables (
    table_a_id BIGINT NOT NULL REFERENCES r_table(table_id),
    table_b_id BIGINT NOT NULL REFERENCES r_table(table_id),
    PRIMARY KEY (table_a_id, table_b_id),
    CONSTRAINT chk_ordered_pair CHECK (table_a_id < table_b_id)
);

-- Reservation Table Assignments
CREATE TABLE reservation_table_assignments (
    id BIGSERIAL PRIMARY KEY,
    reservation_id BIGINT NOT NULL REFERENCES reservations(id),
    table_id BIGINT NOT NULL REFERENCES r_table(table_id),
    group_number INTEGER NOT NULL,
    assigned_by UUID NOT NULL REFERENCES employees(employee_id),
    assigned_at TIMESTAMP NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_reservation_table UNIQUE (reservation_id, table_id)
);

CREATE INDEX idx_rta_reservation ON reservation_table_assignments(reservation_id);
CREATE INDEX idx_rta_table ON reservation_table_assignments(table_id);

-- Reservation: New columns for table assignment
ALTER TABLE reservations
    ADD COLUMN table_groups INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN max_tables_per_group INTEGER,
    ADD COLUMN location_preference VARCHAR(20);

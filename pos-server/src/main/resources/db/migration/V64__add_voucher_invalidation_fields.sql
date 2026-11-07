-- Add voucher invalidation fields
ALTER TABLE voucher ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE voucher ADD COLUMN invalidation_reason VARCHAR(1000) NULL;
ALTER TABLE voucher ADD COLUMN invalidated_at TIMESTAMP NULL;
ALTER TABLE voucher ADD COLUMN invalidation_source VARCHAR(30) NULL;

-- Add duplicated-from-bill tracking on order items
ALTER TABLE t_order_item ADD COLUMN duplicated_from_bill_id BIGINT NULL;
ALTER TABLE t_order_item ADD CONSTRAINT fk_order_item_duplicated_from_bill
    FOREIGN KEY (duplicated_from_bill_id) REFERENCES bill(id);

-- Index for efficient status queries
CREATE INDEX idx_voucher_status ON voucher(status);

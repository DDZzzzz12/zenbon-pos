-- V73__item_transfer_batch.sql
-- Switch item_transfer from one-row-per-item to one-row-per-transfer-event.
-- Legacy rows keep their order_item_id FK; new rows use order_item_ids JSONB.

ALTER TABLE item_transfer
    ALTER COLUMN order_item_id DROP NOT NULL,
    ADD COLUMN order_item_ids JSONB NULL,
    ADD CONSTRAINT item_transfer_shape_chk
        CHECK ((order_item_id IS NULL) <> (order_item_ids IS NULL));

CREATE INDEX item_transfer_item_ids_gin
    ON item_transfer USING gin (order_item_ids);

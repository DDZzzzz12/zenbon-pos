-- The till whose TSE client signs a closing's cash movements (Kassendifferenz, tip payouts,
-- top-up, daily Geldtransit), whichever device triggers the closing. Meaningful only on the fiscal
-- book. NULL = none designated: the calling till signs, as before this column existed.
-- Writes no data: a shop designates its till in the admin panel.
ALTER TABLE cash_book ADD COLUMN signing_terminal_id BIGINT NULL;

ALTER TABLE cash_book
    ADD CONSTRAINT fk_cash_book_signing_terminal
        FOREIGN KEY (signing_terminal_id) REFERENCES terminals (id) ON DELETE SET NULL;

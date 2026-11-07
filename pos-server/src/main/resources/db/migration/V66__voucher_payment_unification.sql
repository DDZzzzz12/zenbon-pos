-- Step 1: Add is_voucher to payment_type
ALTER TABLE payment_type ADD COLUMN is_voucher BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE payment_type SET is_voucher = TRUE WHERE UPPER(name) IN ('GUTSCHEIN', 'VOUCHER');

-- Step 2: Add payment_id to voucher_redeem
ALTER TABLE voucher_redeem ADD COLUMN payment_id BIGINT;

-- Step 3: Populate payment_id from matching payment records
UPDATE voucher_redeem vr
SET payment_id = (
    SELECT p.id FROM payment p
    JOIN payment_type pt ON p.payment_type_id = pt.id
    WHERE p.bill_id = vr.bill_id
    AND pt.is_voucher = TRUE
    LIMIT 1
);

-- Step 4: For any voucher_redeem without a matching payment (edge case:
-- counter-redemptions from bill deletion have negative amounts and may not
-- have a matching voucher payment), delete these orphaned records
DELETE FROM voucher_redeem WHERE payment_id IS NULL;

-- Step 5: Make payment_id required + add FK
ALTER TABLE voucher_redeem ALTER COLUMN payment_id SET NOT NULL;
ALTER TABLE voucher_redeem ADD CONSTRAINT fk_voucher_redeem_payment
    FOREIGN KEY (payment_id) REFERENCES payment(id);

-- Step 6: Drop old bill_id column (FK constraint dropped automatically)
ALTER TABLE voucher_redeem DROP COLUMN bill_id;

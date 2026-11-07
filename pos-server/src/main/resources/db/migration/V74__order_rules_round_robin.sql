-- Add per-employee rotation pool flag for the round-robin assignment rule.
ALTER TABLE employees
    ADD COLUMN in_rotation_pool BOOLEAN NOT NULL DEFAULT FALSE;

-- Note: the round-robin cursor (lastAssignedEmployeeId) is stored in the
-- existing key/value shop_configuration store under key 'order_rotation_cursor'
-- and does not require a schema change.

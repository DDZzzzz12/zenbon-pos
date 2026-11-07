-- Add assignment priority to tables (lower number = assigned first)
-- Default 0 means no preference; tables with same priority use best-fit by seats
ALTER TABLE r_table ADD COLUMN assignment_priority INTEGER NOT NULL DEFAULT 0;

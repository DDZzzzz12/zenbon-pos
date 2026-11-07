-- Move contracted hours from employees to employee_info with flexible period

-- Add new columns to employee_info
ALTER TABLE employee_info ADD COLUMN IF NOT EXISTS contracted_hours DECIMAL(5, 2);
ALTER TABLE employee_info ADD COLUMN IF NOT EXISTS contracted_hours_period VARCHAR(10) DEFAULT 'WEEKLY';

-- Migrate existing data (all existing values were weekly)
UPDATE employee_info ei
SET contracted_hours = e.contracted_weekly_hours,
    contracted_hours_period = 'WEEKLY'
FROM employees e
WHERE ei.employee_id = e.employee_id
  AND e.contracted_weekly_hours IS NOT NULL;

-- Remove old column from employees table
ALTER TABLE employees DROP COLUMN IF EXISTS contracted_weekly_hours;

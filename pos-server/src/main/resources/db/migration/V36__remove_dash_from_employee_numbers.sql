-- Remove dash from employee numbers: K-001 -> K001
UPDATE employees
SET employee_number = REPLACE(employee_number, '-', '')
WHERE employee_number LIKE '%-%';

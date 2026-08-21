INSERT INTO shifts (shift_type, capacity, active)
SELECT 'MORNING', 2, TRUE WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_type = 'MORNING');
INSERT INTO shifts (shift_type, capacity, active)
SELECT 'GENERAL', 2, TRUE WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_type = 'GENERAL');
INSERT INTO shifts (shift_type, capacity, active)
SELECT 'EVENING', 1, TRUE WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_type = 'EVENING');
INSERT INTO shifts (shift_type, capacity, active)
SELECT 'NIGHT', 1, TRUE WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_type = 'NIGHT');
INSERT INTO shifts (shift_type, capacity, active)
SELECT 'OFF', 0, TRUE WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE shift_type = 'OFF');

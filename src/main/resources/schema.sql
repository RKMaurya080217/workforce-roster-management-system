CREATE TABLE IF NOT EXISTS users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    username VARCHAR(80) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS employees (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_code VARCHAR(40) NOT NULL UNIQUE,
    first_name VARCHAR(80) NOT NULL,
    last_name VARCHAR(80) NOT NULL,
    email VARCHAR(160) NOT NULL UNIQUE,
    gender VARCHAR(20) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    user_id BIGINT UNIQUE,
    CONSTRAINT fk_employees_users FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS shifts (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    shift_type VARCHAR(30) NOT NULL UNIQUE,
    capacity INT NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS roster_cycles (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    generated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_roster_cycles_dates UNIQUE (start_date, end_date)
);

CREATE TABLE IF NOT EXISTS roster_assignments (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    cycle_id BIGINT NOT NULL,
    employee_id BIGINT NOT NULL,
    shift_id BIGINT NOT NULL,
    roster_date DATE NOT NULL,
    weekly_off BOOLEAN NOT NULL DEFAULT FALSE,
    on_leave BOOLEAN NOT NULL DEFAULT FALSE,
    overridden BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_assignments_cycle FOREIGN KEY (cycle_id) REFERENCES roster_cycles(id),
    CONSTRAINT fk_assignments_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
    CONSTRAINT fk_assignments_shift FOREIGN KEY (shift_id) REFERENCES shifts(id),
    CONSTRAINT uk_assignment_employee_date UNIQUE (employee_id, roster_date)
);

CREATE TABLE IF NOT EXISTS roster_overrides (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    assignment_id BIGINT NOT NULL,
    previous_shift_type VARCHAR(30) NOT NULL,
    new_shift_type VARCHAR(30) NOT NULL,
    weekly_off BOOLEAN NOT NULL DEFAULT FALSE,
    reason VARCHAR(500),
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_overrides_assignment FOREIGN KEY (assignment_id) REFERENCES roster_assignments(id)
);

CREATE TABLE IF NOT EXISTS leave_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(30) NOT NULL,
    admin_remarks VARCHAR(500),
    requested_at DATETIME(6) NOT NULL,
    reviewed_at DATETIME(6),
    CONSTRAINT fk_leaves_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
);

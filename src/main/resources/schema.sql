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
    assignment_reason VARCHAR(255),
    previous_shift_type VARCHAR(30),
    override_reason VARCHAR(500),
    override_created_at DATETIME(6),
    CONSTRAINT fk_assignments_cycle FOREIGN KEY (cycle_id) REFERENCES roster_cycles(id),
    CONSTRAINT fk_assignments_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
    CONSTRAINT fk_assignments_shift FOREIGN KEY (shift_id) REFERENCES shifts(id),
    CONSTRAINT uk_assignment_employee_date UNIQUE (employee_id, roster_date)
);

CREATE TABLE IF NOT EXISTS master_reference_data (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    item_type VARCHAR(30) NOT NULL,
    holiday_name VARCHAR(150),
    holiday_date DATE,
    holiday_description VARCHAR(500),
    holiday_active BOOLEAN,
    holiday_created_at DATETIME(6),
    holiday_updated_at DATETIME(6),
    skill_name VARCHAR(100),
    skill_category VARCHAR(100),
    skill_description VARCHAR(500),
    skill_active BOOLEAN,
    skill_created_at DATETIME(6)
);

CREATE TABLE IF NOT EXISTS employee_skills (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    employee_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    proficiency_level VARCHAR(30) NOT NULL,
    certification_name VARCHAR(200),
    certification_expiry_date DATE,
    certified BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    CONSTRAINT fk_emp_skills_emp FOREIGN KEY (employee_id) REFERENCES employees(id),
    CONSTRAINT fk_emp_skills_ref FOREIGN KEY (skill_id) REFERENCES master_reference_data(id)
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

CREATE TABLE IF NOT EXISTS shift_handovers (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    handover_date DATE NOT NULL,
    shift_id BIGINT NOT NULL,
    from_employee_id BIGINT NOT NULL,
    to_employee_id BIGINT,
    summary VARCHAR(300) NOT NULL,
    priority VARCHAR(30) NOT NULL,
    status VARCHAR(30) NOT NULL,
    pending_tasks VARCHAR(2000),
    completed_tasks VARCHAR(2000),
    important_notes VARCHAR(2000),
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6),
    CONSTRAINT fk_handovers_shift FOREIGN KEY (shift_id) REFERENCES shifts(id),
    CONSTRAINT fk_handovers_from_emp FOREIGN KEY (from_employee_id) REFERENCES employees(id),
    CONSTRAINT fk_handovers_to_emp FOREIGN KEY (to_employee_id) REFERENCES employees(id)
);

CREATE TABLE IF NOT EXISTS notifications (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    recipient_username VARCHAR(100) NOT NULL,
    recipient_employee_id BIGINT,
    title VARCHAR(200) NOT NULL,
    message VARCHAR(1000) NOT NULL,
    type VARCHAR(50) NOT NULL,
    read_status BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    link_page VARCHAR(50),
    link_id BIGINT
);

CREATE TABLE IF NOT EXISTS employee_requests (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    request_type VARCHAR(30) NOT NULL,
    employee_id BIGINT,
    cycle_id BIGINT,
    assignment_id BIGINT,
    field_name VARCHAR(60),
    current_value VARCHAR(255),
    requested_value VARCHAR(255),
    profile_status VARCHAR(30),
    requested_at DATETIME(6),
    profile_reviewed_at DATETIME(6),
    admin_remarks VARCHAR(500),
    roster_date DATE,
    current_shift_type VARCHAR(30),
    current_weekly_off BOOLEAN,
    requested_shift_type VARCHAR(30),
    requested_weekly_off BOOLEAN,
    request_reason VARCHAR(1000),
    roster_change_status VARCHAR(30),
    roster_admin_remarks VARCHAR(1000),
    roster_created_at DATETIME(6),
    decided_at DATETIME(6),
    decided_by VARCHAR(100),
    preferred_shift_types VARCHAR(150),
    preferred_off_days VARCHAR(150),
    preferred_working_days VARCHAR(150),
    avoid_shift_types VARCHAR(150),
    temporary_restrictions VARCHAR(1000),
    pref_remarks VARCHAR(1000),
    preference_status VARCHAR(30),
    pref_admin_remarks VARCHAR(1000),
    effective_from DATE,
    effective_to DATE,
    pref_created_at DATETIME(6),
    pref_reviewed_at DATETIME(6),
    pref_reviewed_by VARCHAR(100),
    version BIGINT,
    CONSTRAINT fk_er_employee FOREIGN KEY (employee_id) REFERENCES employees(id),
    CONSTRAINT fk_er_assignment FOREIGN KEY (assignment_id) REFERENCES roster_assignments(id),
    CONSTRAINT fk_er_cycle FOREIGN KEY (cycle_id) REFERENCES roster_cycles(id)
);

CREATE TABLE IF NOT EXISTS system_audit_logs (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    log_type VARCHAR(30) NOT NULL,
    audit_action VARCHAR(50),
    actor VARCHAR(100),
    cycle_id BIGINT,
    employee_id BIGINT,
    employee_name VARCHAR(150),
    entity_id BIGINT,
    entity_type VARCHAR(50),
    new_value VARCHAR(1000),
    old_value VARCHAR(1000),
    reason VARCHAR(1000),
    source VARCHAR(30),
    audit_timestamp DATETIME(6),
    activity_action VARCHAR(80),
    activity_category VARCHAR(30),
    activity_description VARCHAR(500),
    activity_employee_id BIGINT,
    activity_source VARCHAR(100),
    activity_status VARCHAR(30),
    username VARCHAR(100),
    recipient_email VARCHAR(160),
    sent_at DATETIME(6),
    delivery_status VARCHAR(30),
    error_message VARCHAR(500),
    generation_mode VARCHAR(30),
    email_type VARCHAR(50),
    reviewed_at DATETIME(6),
    version_number INT,
    version_action VARCHAR(50),
    action_reason VARCHAR(500),
    created_timestamp DATETIME(6),
    created_by VARCHAR(100),
    version_mode VARCHAR(30),
    version_status VARCHAR(30),
    affected_assignments_count INT,
    snapshot_data LONGTEXT,
    health_score INT,
    impact_summary VARCHAR(500),
    assignment_id BIGINT,
    previous_shift_type VARCHAR(30),
    new_shift_type VARCHAR(30),
    weekly_off BOOLEAN,
    created_at DATETIME(6)
);

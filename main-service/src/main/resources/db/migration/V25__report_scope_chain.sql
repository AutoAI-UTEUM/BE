ALTER TABLE student_reports
    ADD COLUMN scope_key VARCHAR(16) NULL AFTER student_id;

UPDATE student_reports report
JOIN report_generations generation ON generation.id = report.generation_id
SET report.scope_key = CASE
    WHEN generation.scope_type = 'FULL' AND generation.week_number IS NULL
        THEN 'FULL'
    WHEN generation.scope_type = 'WEEK' AND generation.week_number >= 1
        THEN CONCAT('WEEK:', generation.week_number)
    ELSE NULL
END;

ALTER TABLE student_reports
    MODIFY COLUMN scope_key VARCHAR(16) NOT NULL,
    DROP INDEX uk_student_reports_classroom_student_version,
    ADD CONSTRAINT uk_student_reports_classroom_student_scope_version
        UNIQUE (classroom_id, student_id, scope_key, version),
    ADD CONSTRAINT chk_student_reports_scope_key
        CHECK (scope_key = 'FULL' OR scope_key REGEXP '^WEEK:[1-9][0-9]*$');

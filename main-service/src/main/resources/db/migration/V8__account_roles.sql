ALTER TABLE users DROP CHECK chk_users_role;

UPDATE users
SET role = 'LEARNER'
WHERE role = 'USER';

ALTER TABLE users
    ALTER COLUMN role SET DEFAULT 'LEARNER',
    ADD CONSTRAINT chk_users_role
        CHECK (role IN ('LEARNER', 'INSTRUCTOR', 'ADMIN'));

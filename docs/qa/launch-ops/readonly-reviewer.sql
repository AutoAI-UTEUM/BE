-- PRIVATE OPERATOR USE ONLY. Prepared-statement template; :selected_email is a bound parameter.
-- It is not mysql-client substitution syntax. Do not interpolate it into a shell command.
-- Bind the already selected GUARDIAN_REVIEW_ACCOUNT privately with an existing read-only client.
-- Do not print the parameter, full query history, email, DOB, password, tokens or the full user row.
-- The result may contain at most two rows; only exactly one ACTIVE ADMIN is acceptable.
SELECT id AS reviewer_user_id, role, status
FROM users
WHERE email = :selected_email
ORDER BY id
LIMIT 2;

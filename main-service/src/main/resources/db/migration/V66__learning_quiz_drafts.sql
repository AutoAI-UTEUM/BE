-- Drafts belong to the quiz owner; quiz deletion removes draft data automatically.
ALTER TABLE quizzes ADD COLUMN (
    draft_answers JSON NULL,
    draft_version INT NOT NULL DEFAULT 0,
    draft_saved_at DATETIME(6) NULL
);

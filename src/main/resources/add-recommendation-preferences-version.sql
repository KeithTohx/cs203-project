-- Run ONCE on a database that already has rows in recommendation.
--
-- preferences_version is the version of the traveller's preferences (UserPreference.updatedAt)
-- that a recommendation's advice was written for. It is compared with the current version to
-- decide whether the advice is out of date.
--
-- The column is nullable on purpose. Existing rows have no version, and the service treats a row
-- with no version by falling back to when it was made, so nothing has to be filled in and no row
-- is regenerated just because of this migration.
--
-- It cannot be run twice (SQLite has no ADD COLUMN IF NOT EXISTS). If hibernate has already added
-- the column for you (ddl-auto=update), skip this file.

ALTER TABLE recommendation ADD COLUMN preferences_version TIMESTAMP;

-- Run ONCE on a database that already has rows in user_preference.
--
-- SQLite will not add a NOT NULL column unless it has a default, because it has to give every
-- existing row a value. The default of '' is that value: it means "no preferred categories",
-- which is how ActivityCategorySetConverter reads an empty set.
--
-- It cannot be run twice (SQLite has no ADD COLUMN IF NOT EXISTS), so skip it on a database that
-- was created after the column was added to the entity.
--
-- If the column was already added as nullable, do not run the ALTER. Run this instead, which the
-- converter would also cope with by reading NULL as an empty set:
--   UPDATE user_preference SET preferred_categories = '' WHERE preferred_categories IS NULL;

ALTER TABLE user_preference ADD COLUMN preferred_categories VARCHAR(255) NOT NULL DEFAULT '';

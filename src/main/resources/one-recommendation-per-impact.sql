-- Makes the database allow only one recommendation per impact.
--
-- The first statement removes any duplicates a past refresh may have left, keeping the oldest row
-- for each impact. It has to come first, because the unique index cannot be created while
-- duplicates exist.
--
-- Safe to run more than once. The index has the same name as the one declared on the
-- Recommendation entity, so hibernate does not try to create a second one.

DELETE FROM recommendation
WHERE id NOT IN (SELECT MIN(id) FROM recommendation GROUP BY impact_id);

CREATE UNIQUE INDEX IF NOT EXISTS uk_recommendation_impact ON recommendation (impact_id);

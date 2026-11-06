-- Recommendation regeneration retains multiple attempts per impact.
-- Remove the former uniqueness constraint without deleting recommendation history.
-- Run once on databases where the earlier version of this script was applied.
DROP INDEX IF EXISTS uk_recommendation_impact;

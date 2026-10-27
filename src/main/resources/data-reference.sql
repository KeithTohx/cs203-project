-- Seed data for local testing. INSERT OR IGNORE with fixed ids makes it safe to re-run on every restart. 
-- Parents are inserted before children because of the foreign keys.

INSERT OR IGNORE INTO country (id, name, fips_code) VALUES
    (1, 'Japan', 'JA'),
    (2, 'Singapore', 'SN'),
    (3, 'Thailand', 'TH'),
    (4, 'South Korea', 'KS'),
    (5, 'Indonesia', 'ID'),
    (6, 'Vietnam', 'VM');

UPDATE country
SET fips_code = CASE id WHEN 1 THEN 'JA' WHEN 2 THEN 'SN' WHEN 3 THEN 'TH'
                        WHEN 4 THEN 'KS' WHEN 5 THEN 'ID' WHEN 6 THEN 'VM' END
WHERE fips_code IS NULL AND id IN (1, 2, 3, 4, 5, 6);


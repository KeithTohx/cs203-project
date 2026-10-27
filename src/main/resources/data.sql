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

-- Passwords are BCrypt hashes (login compares against the hash, so plaintext would never match).
-- Plain-text values for local testing: john/alice/bob/carol/dave = password123, admin = admin123.
INSERT OR IGNORE INTO user (id, username, email, password, role) VALUES
    (1, 'john',  'john@example.com',  '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (2, 'alice', 'alice@example.com', '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (3, 'admin', 'admin@example.com', '$2a$10$Uk.hzssxOu6YLesmUIlmZOdebzEd3D4XmOSGiWUIJqJ8ihGE92Kjm', 'ADMIN'),
    (4, 'bob',   'bob@example.com',   '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (5, 'carol', 'carol@example.com', '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (6, 'dave',  'dave@example.com',  '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER');

INSERT OR IGNORE INTO itinerary (id, user_id, country_id, name, date_start, date_end) VALUES
    (1, 1, 1, 'Japan Ski Trip',          '2026-12-15 00:00:00', '2026-12-22 00:00:00'),
    (2, 2, 3, 'Thailand Beach Holiday',  '2026-11-05 00:00:00', '2026-11-12 00:00:00'),
    (3, 1, 2, 'Singapore Weekend',       '2027-01-08 00:00:00', '2027-01-10 00:00:00'),
    (4, 4, 4, 'Seoul Food Tour',         '2026-10-20 00:00:00', '2026-10-26 00:00:00'),
    (5, 5, 5, 'Bali Retreat',            '2027-02-14 00:00:00', '2027-02-21 00:00:00'),
    (6, 2, 1, 'Tokyo Cherry Blossoms',   '2027-03-25 00:00:00', '2027-04-02 00:00:00'),
    (7, 4, 6, 'Vietnam Backpacking',     '2026-12-01 00:00:00', '2026-12-14 00:00:00'),
    (8, 5, 3, 'Chiang Mai Getaway',      '2027-01-20 00:00:00', '2027-01-25 00:00:00');

INSERT OR IGNORE INTO activity (id, itinerary_id, name, address, date_start, date_end) VALUES
    (1,  1, 'Ski at Niseko',              'Niseko, Hokkaido, Japan',            '2026-12-16 09:00:00', '2026-12-16 16:00:00'),
    (2,  1, 'Onsen visit',                'Jozankei Onsen, Sapporo, Japan',     '2026-12-17 18:00:00', '2026-12-17 20:00:00'),
    (3,  1, 'Sapporo Beer Museum tour',   'Higashi-ku, Sapporo, Japan',         '2026-12-19 14:00:00', '2026-12-19 16:00:00'),
    (4,  2, 'Grand Palace visit',         'Na Phra Lan Rd, Bangkok, Thailand',  '2026-11-06 09:00:00', '2026-11-06 12:00:00'),
    (5,  2, 'Phi Phi Island boat tour',   'Krabi Pier, Krabi, Thailand',        '2026-11-08 08:00:00', '2026-11-08 17:00:00'),
    (6,  2, 'Chatuchak Weekend Market',   'Chatuchak, Bangkok, Thailand',       '2026-11-10 10:00:00', '2026-11-10 15:00:00'),
    (7,  3, 'Gardens by the Bay',         '18 Marina Gardens Dr, Singapore',    '2027-01-08 17:00:00', '2027-01-08 20:00:00'),
    (8,  3, 'Universal Studios Singapore','8 Sentosa Gateway, Singapore',       '2027-01-09 10:00:00', '2027-01-09 19:00:00'),
    (9,  4, 'Gwangjang Market food crawl','88 Changgyeonggung-ro, Seoul',       '2026-10-21 12:00:00', '2026-10-21 15:00:00'),
    (10, 4, 'Gyeongbokgung Palace',       '161 Sajik-ro, Seoul, South Korea',   '2026-10-22 10:00:00', '2026-10-22 13:00:00'),
    (11, 4, 'Nami Island day trip',       'Chuncheon, Gangwon, South Korea',    '2026-10-24 09:00:00', '2026-10-24 17:00:00'),
    (12, 5, 'Ubud Monkey Forest',         'Ubud, Bali, Indonesia',              '2027-02-15 09:00:00', '2027-02-15 11:00:00'),
    (13, 5, 'Mount Batur sunrise trek',   'Kintamani, Bali, Indonesia',         '2027-02-17 03:00:00', '2027-02-17 09:00:00'),
    (14, 6, 'Shinjuku Gyoen picnic',      '11 Naitomachi, Shinjuku, Tokyo',     '2027-03-27 10:00:00', '2027-03-27 15:00:00'),
    (15, 7, 'Ha Long Bay overnight cruise','Bai Chay, Quang Ninh, Vietnam',     '2026-12-05 08:00:00', '2026-12-06 11:00:00'),
    (16, 8, 'Doi Suthep temple',          'Doi Suthep, Chiang Mai, Thailand',   '2027-01-21 08:00:00', '2027-01-21 11:00:00');

INSERT OR IGNORE INTO news (id, title, description, source, url, published_at) VALUES
    (1, 'Magnitude 6.8 earthquake strikes near Hokkaido',
        'A strong earthquake hit southern Hokkaido, with aftershocks expected and some ski resorts suspending lifts.',
        'Japan Meteorological Agency', 'https://www.jma.go.jp/jma/indexe.html', '2026-09-20 08:30:00'),
    (2, 'Flooding closes Bangkok tourist areas',
        'Heavy rain has flooded several districts in central Bangkok, closing some attractions.',
        'Bangkok Post', 'https://www.bangkokpost.com/', '2026-09-21 14:00:00'),
    (3, 'Typhoon warning issued for northern Vietnam coast',
        'Authorities warn of strong winds and rough seas, with boat services likely to be suspended.',
        'VnExpress', 'https://e.vnexpress.net/', '2026-09-22 06:45:00'),
    (4, 'Mount Batur volcanic activity raised to alert level',
        'Increased activity has led officials to restrict access to hiking routes around the crater.',
        'Jakarta Post', 'https://www.thejakartapost.com/', '2026-09-23 10:15:00'),
    (5, 'Grand Palace closed for royal ceremony',
        'The palace will be closed to visitors for one day next week for a ceremony.',
        'Thai PBS', 'https://world.thaipbs.or.th/', '2026-09-24 09:00:00'),
    (6, 'Rail strike disrupts Seoul metro services',
        'A partial strike is reducing train frequency on several lines during peak hours.',
        'Korea Herald', 'https://www.koreaherald.com/', '2026-09-25 07:20:00'),
    (7, 'Haze levels rise across Southeast Asia',
        'Air quality has worsened in northern Thailand and parts of the region due to seasonal burning.',
        'Channel NewsAsia', 'https://www.channelnewsasia.com/', '2026-09-26 12:00:00'),
    (8, 'Universal Studios Singapore to close for maintenance',
        'The park will close for several days for scheduled works, with dates to be confirmed.',
        'The Straits Times', 'https://www.straitstimes.com/', '2026-09-27 16:30:00');

INSERT OR IGNORE INTO news_country (news_id, country_id) VALUES
    (1, 1),   -- Hokkaido quake: Japan
    (2, 3),   -- Bangkok flooding: Thailand
    (3, 6),   -- Vietnam typhoon: Vietnam
    (4, 5),   -- Mount Batur: Indonesia
    (5, 3),   -- Grand Palace closure: Thailand
    (6, 4),   -- Seoul rail strike: South Korea
    (7, 3),   -- SEA haze (northern Thailand): Thailand
    (8, 2);   -- USS maintenance: Singapore

INSERT OR IGNORE INTO activity (id, itinerary_id, country_id, name, address, date_start, date_end) VALUES
    (1,  1, 1, 'Ski at Niseko',              'Niseko, Hokkaido, Japan',            '2026-12-16 09:00:00', '2026-12-16 16:00:00'),
    (2,  1, 1, 'Onsen visit',                'Jozankei Onsen, Sapporo, Japan',     '2026-12-17 18:00:00', '2026-12-17 20:00:00'),
    (3,  1, 1, 'Sapporo Beer Museum tour',   'Higashi-ku, Sapporo, Japan',         '2026-12-19 14:00:00', '2026-12-19 16:00:00'),
    (4,  2, 3, 'Grand Palace visit',         'Na Phra Lan Rd, Bangkok, Thailand',  '2026-11-06 09:00:00', '2026-11-06 12:00:00'),
    (5,  2, 3, 'Phi Phi Island boat tour',   'Krabi Pier, Krabi, Thailand',        '2026-11-08 08:00:00', '2026-11-08 17:00:00'),
    (6,  2, 3, 'Chatuchak Weekend Market',   'Chatuchak, Bangkok, Thailand',       '2026-11-10 10:00:00', '2026-11-10 15:00:00'),
    (7,  3, 2, 'Gardens by the Bay',         '18 Marina Gardens Dr, Singapore',    '2027-01-08 17:00:00', '2027-01-08 20:00:00'),
    (8,  3, 2, 'Universal Studios Singapore','8 Sentosa Gateway, Singapore',       '2027-01-09 10:00:00', '2027-01-09 19:00:00'),
    (9,  4, 4, 'Gwangjang Market food crawl','88 Changgyeonggung-ro, Seoul',       '2026-10-21 12:00:00', '2026-10-21 15:00:00'),
    (10, 4, 4, 'Gyeongbokgung Palace',       '161 Sajik-ro, Seoul, South Korea',   '2026-10-22 10:00:00', '2026-10-22 13:00:00'),
    (11, 4, 4, 'Nami Island day trip',       'Chuncheon, Gangwon, South Korea',    '2026-10-24 09:00:00', '2026-10-24 17:00:00'),
    (12, 5, 5, 'Ubud Monkey Forest',         'Ubud, Bali, Indonesia',              '2027-02-15 09:00:00', '2027-02-15 11:00:00'),
    (13, 5, 5, 'Mount Batur sunrise trek',   'Kintamani, Bali, Indonesia',         '2027-02-17 03:00:00', '2027-02-17 09:00:00'),
    (14, 6, 1, 'Shinjuku Gyoen picnic',      '11 Naitomachi, Shinjuku, Tokyo',     '2027-03-27 10:00:00', '2027-03-27 15:00:00'),
    (15, 7, 6, 'Ha Long Bay overnight cruise','Bai Chay, Quang Ninh, Vietnam',     '2026-12-05 08:00:00', '2026-12-06 11:00:00'),
    (16, 8, 3, 'Doi Suthep temple',          'Doi Suthep, Chiang Mai, Thailand',   '2027-01-21 08:00:00', '2027-01-21 11:00:00');

-- Rows that already exist (created before country_id existed) are skipped by INSERT OR IGNORE,
-- so fill them in. Safe to re-run: it only touches rows where country_id is still null.
UPDATE activity
SET country_id = (SELECT country_id FROM itinerary WHERE itinerary.id = activity.itinerary_id)
WHERE country_id IS NULL;
-- Seed data for local testing. INSERT OR IGNORE with fixed ids makes it safe to re-run on every restart. 
-- Parents are inserted before children because of the foreign keys.

INSERT OR IGNORE INTO country (id, name) VALUES
    (1, 'Japan'),
    (2, 'Singapore'),
    (3, 'Thailand'),
    (4, 'South Korea'),
    (5, 'Indonesia'),
    (6, 'Vietnam');

-- Passwords are BCrypt hashes (login compares against the hash, so plaintext would never match).
-- Plain-text values for local testing: karwei/alice/bob/carol/dave = password123, admin = admin123.
INSERT OR IGNORE INTO user (id, username, email, password, role) VALUES
    (1, 'karwei', 'kw@email.com',      '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (2, 'alice',  'alice@example.com', '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (3, 'admin',  'admin@example.com', '$2a$10$Uk.hzssxOu6YLesmUIlmZOdebzEd3D4XmOSGiWUIJqJ8ihGE92Kjm', 'ADMIN'),
    (4, 'bob',    'bob@example.com',   '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (5, 'carol',  'carol@example.com', '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER'),
    (6, 'dave',   'dave@example.com',  '$2a$10$xENb5lsk8eHErfInbr4fKufkjwD8wZ.ua1emb/IuDbj9..08xomHC', 'USER');

INSERT OR IGNORE INTO itinerary (id, user_id, country_id, name, date_start, date_end) VALUES
    (1, 1, 1, 'Japan Ski Trip',          '2026-12-15 00:00:00', '2026-12-22 00:00:00'),
    (2, 2, 3, 'Thailand Beach Holiday',  '2026-11-05 00:00:00', '2026-11-12 00:00:00'),
    (3, 1, 2, 'Singapore Weekend',       '2027-01-08 00:00:00', '2027-01-10 00:00:00'),
    (4, 4, 4, 'Seoul Food Tour',         '2026-10-20 00:00:00', '2026-10-26 00:00:00'),
    (5, 5, 5, 'Bali Retreat',            '2027-02-14 00:00:00', '2027-02-21 00:00:00'),
    (6, 2, 1, 'Tokyo Cherry Blossoms',   '2027-03-25 00:00:00', '2027-04-02 00:00:00'),
    (7, 4, 6, 'Vietnam Backpacking',     '2026-12-01 00:00:00', '2026-12-14 00:00:00'),
    (8, 5, 3, 'Chiang Mai Getaway',      '2027-01-20 00:00:00', '2027-01-25 00:00:00');

INSERT OR IGNORE INTO activity (id, itinerary_id, name, address, date_start, date_end, category) VALUES
    (1,  1, 'Ski at Niseko',              'Niseko, Hokkaido, Japan',            '2026-12-16 09:00:00', '2026-12-16 16:00:00', 'SKI_SNOW'),
    (2,  1, 'Onsen visit',                'Jozankei Onsen, Sapporo, Japan',     '2026-12-17 18:00:00', '2026-12-17 20:00:00', 'WELLNESS_SPA'),
    (3,  1, 'Sapporo Beer Museum tour',   'Higashi-ku, Sapporo, Japan',         '2026-12-19 14:00:00', '2026-12-19 16:00:00', 'MUSEUM_GALLERY'),
    (4,  2, 'Grand Palace visit',         'Na Phra Lan Rd, Bangkok, Thailand',  '2026-11-06 09:00:00', '2026-11-06 12:00:00', 'SIGHTSEEING'),
    (5,  2, 'Phi Phi Island boat tour',   'Krabi Pier, Krabi, Thailand',        '2026-11-08 08:00:00', '2026-11-08 17:00:00', 'BEACH_WATER'),
    (6,  2, 'Chatuchak Weekend Market',   'Chatuchak, Bangkok, Thailand',       '2026-11-10 10:00:00', '2026-11-10 15:00:00', 'SHOPPING'),
    (7,  3, 'Gardens by the Bay',         '18 Marina Gardens Dr, Singapore',    '2027-01-08 17:00:00', '2027-01-08 20:00:00', 'OUTDOOR_NATURE'),
    (8,  3, 'Universal Studios Singapore','8 Sentosa Gateway, Singapore',       '2027-01-09 10:00:00', '2027-01-09 19:00:00', 'SIGHTSEEING'),
    (9,  4, 'Gwangjang Market food crawl','88 Changgyeonggung-ro, Seoul',       '2026-10-21 12:00:00', '2026-10-21 15:00:00', 'FOOD_DINING'),
    (10, 4, 'Gyeongbokgung Palace',       '161 Sajik-ro, Seoul, South Korea',   '2026-10-22 10:00:00', '2026-10-22 13:00:00', 'SIGHTSEEING'),
    (11, 4, 'Nami Island day trip',       'Chuncheon, Gangwon, South Korea',    '2026-10-24 09:00:00', '2026-10-24 17:00:00', 'OUTDOOR_NATURE'),
    (12, 5, 'Ubud Monkey Forest',         'Ubud, Bali, Indonesia',              '2027-02-15 09:00:00', '2027-02-15 11:00:00', 'OUTDOOR_NATURE'),
    (13, 5, 'Mount Batur sunrise trek',   'Kintamani, Bali, Indonesia',         '2027-02-17 03:00:00', '2027-02-17 09:00:00', 'OUTDOOR_NATURE'),
    (14, 6, 'Shinjuku Gyoen picnic',      '11 Naitomachi, Shinjuku, Tokyo',     '2027-03-27 10:00:00', '2027-03-27 15:00:00', 'OUTDOOR_NATURE'),
    (15, 7, 'Ha Long Bay overnight cruise','Bai Chay, Quang Ninh, Vietnam',     '2026-12-05 08:00:00', '2026-12-06 11:00:00', 'BEACH_WATER'),
    (16, 8, 'Doi Suthep temple',          'Doi Suthep, Chiang Mai, Thailand',   '2027-01-21 08:00:00', '2027-01-21 11:00:00', 'SIGHTSEEING');

INSERT OR IGNORE INTO news (id, title, description, source, url, published_at) VALUES
    (1, 'Magnitude 6.8 earthquake strikes near Hokkaido',
        'A strong earthquake hit southern Hokkaido, with aftershocks expected and some ski resorts suspending lifts.',
        'Japan Meteorological Agency', 'https://example.com/news/hokkaido-quake', '2026-09-20 08:30:00'),
    (2, 'Flooding closes Bangkok tourist areas',
        'Heavy rain has flooded several districts in central Bangkok, closing some attractions.',
        'Bangkok Post', 'https://example.com/news/bangkok-flood', '2026-09-21 14:00:00'),
    (3, 'Typhoon warning issued for northern Vietnam coast',
        'Authorities warn of strong winds and rough seas, with boat services likely to be suspended.',
        'VnExpress', 'https://example.com/news/vietnam-typhoon', '2026-09-22 06:45:00'),
    (4, 'Mount Batur volcanic activity raised to alert level',
        'Increased activity has led officials to restrict access to hiking routes around the crater.',
        'Jakarta Post', 'https://example.com/news/batur-alert', '2026-09-23 10:15:00'),
    (5, 'Grand Palace closed for royal ceremony',
        'The palace will be closed to visitors for one day next week for a ceremony.',
        'Thai PBS', 'https://example.com/news/palace-closure', '2026-09-24 09:00:00'),
    (6, 'Rail strike disrupts Seoul metro services',
        'A partial strike is reducing train frequency on several lines during peak hours.',
        'Korea Herald', 'https://example.com/news/seoul-strike', '2026-09-25 07:20:00'),
    (7, 'Haze levels rise across Southeast Asia',
        'Air quality has worsened in northern Thailand and parts of the region due to seasonal burning.',
        'Channel NewsAsia', 'https://example.com/news/sea-haze', '2026-09-26 12:00:00'),
    (8, 'Universal Studios Singapore to close for maintenance',
        'The park will close for several days for scheduled works, with dates to be confirmed.',
        'The Straits Times', 'https://example.com/news/uss-maintenance', '2026-09-27 16:30:00');

-- reason is what the model would have written, so evaluate() serves these from the db instead of
-- calling ollama again. a null reason counts as not yet evaluated, see ImpactServiceImpl
INSERT OR IGNORE INTO impact (id, user_id, news_id, itinerary_id, activity_id, impacted, reason) VALUES
    (1,  1, 1, 1, 1,    1, 'Niseko is in Hokkaido and lifts may be suspended by aftershocks during the ski days.'),
    (2,  1, 1, 1, 2,    1, 'Jozankei Onsen is near Sapporo, so the visit could be closed for safety checks after the quake.'),
    (3,  1, 1, 1, NULL, 1, 'The whole trip is in southern Hokkaido where the quake struck, so travel and lodging may be disrupted.'),
    (4,  2, 2, 2, 4,    1, 'The Grand Palace is in central Bangkok, where flooding has closed attractions.'),
    (5,  2, 5, 2, 4,    0, 'The closure is for one day next week and does not fall on the planned palace visit.'),
    (6,  1, 8, 3, 8,    1, 'Universal Studios Singapore closes for maintenance, which may fall on the planned park day.'),
    (7,  4, 6, 4, NULL, 1, 'The trip relies on Seoul metro services, which the partial strike is reducing at peak hours.'),
    (8,  5, 4, 5, 13,   1, 'Access to hiking routes around the crater is restricted, which covers the sunrise trek.'),
    (9,  4, 3, 7, 15,   1, 'Boat services on the northern coast are likely suspended, which would stop the cruise.'),
    (10, 5, 7, 8, NULL, 0, 'The haze is seasonal across the region and nothing on the Chiang Mai trip is closed.'),
    -- not impacted, so karwei's japan trip has something for the filter to drop
    (11, 1, 1, 1, 3,    0, 'The Sapporo Beer Museum is indoors in Higashi-ku and was not reported damaged.');
-- recommendations for karwei's trips, so the demo has advice to show without calling ollama.
-- generate() keys "already done" off the row existing, so these are served straight back.
-- suggested_option_id points at activity_option, which comes from options.sql
INSERT OR IGNORE INTO recommendation (id, impact_id, severity, action, advice, suggested_option_id, created_at) VALUES
    (1, 1, 'HIGH',   'REPLACE',
        'Lifts may be shut on the 16th, so swap the ski day for the Nishimura Museum of Art in Niseko and keep the skis for later in the week.',
        123464807, '2026-10-24 12:00:00'),
    (2, 2, 'MEDIUM', 'REPLACE',
        'Jozankei may be closed for safety checks, so spend the evening at the Sapporo Clock Tower instead, a short ride from the city centre.',
        1140619,   '2026-10-24 12:00:00'),
    -- whole trip impact, no single activity to swap so no option either
    (3, 3, 'MEDIUM', 'MONITOR',
        'The quake affects the whole trip, so follow the Hokkaido aftershock updates and leave the 15th and 16th flexible.',
        NULL,      '2026-10-24 12:00:00'),
    -- low severity still says something, "no change needed" is the answer the story asks for
    (4, 6, 'LOW',    'MONITOR',
        'The maintenance dates are not confirmed, so no change is needed yet. Check the park calendar a week before the 9th.',
        NULL,      '2026-10-24 12:00:00');

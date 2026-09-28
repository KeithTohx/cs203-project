-- Seed data for local testing. INSERT OR IGNORE with fixed ids makes it safe to re-run on every restart. 
-- Parents are inserted before children because of the foreign keys.

INSERT OR IGNORE INTO country (id, name) VALUES
    (1, 'Japan'),
    (2, 'Singapore'),
    (3, 'Thailand'),
    (4, 'South Korea'),
    (5, 'Indonesia'),
    (6, 'Vietnam');

INSERT OR IGNORE INTO user (id, username, email, password, role) VALUES
    (1, 'john',  'john@example.com',  'password123', 'USER'),
    (2, 'alice', 'alice@example.com', 'password123', 'USER'),
    (3, 'admin', 'admin@example.com', 'admin123',    'ADMIN'),
    (4, 'bob',   'bob@example.com',   'password123', 'USER'),
    (5, 'carol', 'carol@example.com', 'password123', 'USER'),
    (6, 'dave',  'dave@example.com',  'password123', 'USER');

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

INSERT OR IGNORE INTO impact (id, user_id, news_id, itinerary_id, activity_id, impacted) VALUES
    (1,  1, 1, 1, 1,    1),  -- 1, john, 6.8 earthquake, japan ski trip, ski at niseko, impacted
    (2,  1, 1, 1, 2,    1),  -- 2, john, 6.8 earthquake, japan ski trip, onsen visit, impacted
    (3,  1, 1, 1, NULL, 1),  -- 3, john, 6.8 earthquake, japan ski trip, NULL, impacted
    (4,  2, 2, 2, 4,    1),  -- 4, alice, flooding, thailand beach, grand palace, impacted
    (5,  2, 5, 2, 4,    0),  -- 5, alice, grand palace closure, thailand beach, grand palce, not impacted 
    (6,  1, 8, 3, 8,    1),  -- 6, john, uss closed, singapore weekend, uss, impacted
    (7,  4, 6, 4, NULL, 1),  -- 7, bob, rail strikes, seoul tour, NULL, impacted
    (8,  5, 4, 5, 13,   1),  -- 8, carol, mount batur alert, bali retreat, mount batur trek, impacted
    (9,  4, 3, 7, 15,   1),  -- 9, bob, vietname typhoon, vietname backpacking, ha long bay, impacted
    (10, 5, 7, 8, NULL, 0);  -- 10, carol, sea haze, chiang mai, NULL, not impacted
"""
Builds src/main/resources/options.sql: real places a traveller could go instead when an
activity is disrupted, worldwide.

Dev machine only, the running app never calls these apis.

    python tools/build-options.py

Two passes.

The global pass asks Wikidata for every notable place of each type, anywhere, and reads the
area off the place itself (P131, the thing it is located in). That is roughly three seconds
per type for the whole planet, where asking per city took thirty to fifty and timed out on
the dense ones. No list of cities to keep up to date either, the areas fall out of the data.

The local pass walks tools/areas.txt and does a radius search around each one. It exists
because somewhere like Niseko has nothing globally notable in it, and our demo seed points
at a Niseko museum by id.

Wikidata rather than OpenStreetMap on purpose: english labels, sitelink counts to rank by,
and it answers in seconds where the free Overpass endpoints took minutes and often failed.

Data from Wikidata, CC0.
"""

import json
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
AREAS = Path(__file__).resolve().parent / "areas.txt"
CACHE = Path(__file__).resolve().parent / ".cache"
RESOURCES = ROOT / "src" / "main" / "resources"
OUT = RESOURCES / "options.sql"

UA = "TripSense-CS203-SMU/1.0 (student project; https://github.com/KeithTohx/cs203-project)"
NOMINATIM = "https://nominatim.openstreetmap.org/search"
WIKIDATA = "https://query.wikidata.org/sparql"

# keep at most this many per area and category, so one city of museums cannot crowd out
# everywhere else. the whole file is a knob, turn this down if it gets heavy
PER_AREA_CATEGORY = 3

# sqlite turns a multi row VALUES into a compound select and caps that at 500 terms, so the
# rows go out in batches. one statement for all of them fails to parse
CHUNK = 200

# local pass only. widen when an area turns up nothing: "Krabi, Thailand" geocodes to the
# province centroid, which is inland and has nothing within 20km of it
RADII_KM = [20, 50]
LOCAL_PER_CATEGORY = 5

# wikidata's P17 label is not always the label the country table got from the iso query
ALIASES = {"Netherlands": "Kingdom of the Netherlands"}

# wikidata type -> our category, and the minimum sitelink count to accept. the threshold is
# per type because coverage is wildly uneven: wikidata knows thousands of notable museums
# and barely a hundred notable restaurants, so a single cutoff would give museums only
TYPES = {
    "Q33506":   ("MUSEUM_GALLERY", 8),    # museum
    "Q207694":  ("MUSEUM_GALLERY", 8),    # art museum
    "Q1007870": ("MUSEUM_GALLERY", 4),    # art gallery
    "Q2087181": ("MUSEUM_GALLERY", 5),    # historic house museum
    "Q22698":   ("OUTDOOR_NATURE", 8),    # park
    "Q46169":   ("OUTDOOR_NATURE", 8),    # monument
    "Q570116":  ("SIGHTSEEING", 3),       # tourist attraction
    "Q23413":   ("SIGHTSEEING", 12),      # castle
    "Q44539":   ("SIGHTSEEING", 5),       # temple
    "Q16970":   ("SIGHTSEEING", 30),      # church building, there are a great many
    "Q174782":  ("SIGHTSEEING", 8),       # town square
    "Q11315":   ("SHOPPING", 3),          # shopping centre
    "Q11707":   ("FOOD_DINING", 2),       # restaurant
    "Q30022":   ("FOOD_DINING", 2),       # cafe
    "Q177380":  ("WELLNESS_SPA", 2),      # spa town
    "Q1341387": ("WELLNESS_SPA", 2),      # onsen
    "Q40080":   ("BEACH_WATER", 5),       # beach
    "Q130003":  ("SKI_SNOW", 3),          # ski resort
}

# P131 is often a district rather than the city, and a place can have both. we keep whichever
# of them is the more notable, which is almost always the city, then tidy the name because it
# has to read naturally and show up inside a street address
GLOBAL_QUERY = """SELECT ?item ?label ?point ?links ?area ?areaLinks ?country WHERE {
  ?item wdt:P31 wd:%(type)s ; wdt:P625 ?point ; wikibase:sitelinks ?links .
  FILTER(?links >= %(min)d)
  ?item rdfs:label ?label . FILTER(LANG(?label) = "en")
  ?item wdt:P17 ?c . ?c rdfs:label ?country . FILTER(LANG(?country) = "en")
  ?item wdt:P131 ?a . ?a rdfs:label ?area . FILTER(LANG(?area) = "en")
  ?a wikibase:sitelinks ?areaLinks .
}
LIMIT 8000"""

AROUND_QUERY = """SELECT ?item ?itemLabel ?type ?sitelinks ?coord WHERE {
  SERVICE wikibase:around {
    ?item wdt:P625 ?coord .
    bd:serviceParam wikibase:center "Point(%(lon)s %(lat)s)"^^geo:wktLiteral .
    bd:serviceParam wikibase:radius "%(radius)s" .
  }
  ?item wdt:P31 ?type .
  VALUES ?type { %(types)s }
  ?item wikibase:sitelinks ?sitelinks .
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en" . }
}
ORDER BY DESC(?sitelinks)
LIMIT 300"""

# administrative noise that would never appear in a street address
SUFFIXES = re.compile(
    r"\s+(Municipality|Metropolitan Municipality|District|Subdistrict|Prefecture|"
    r"Province|County|Regency|Governorate|Oblast|Canton|Department|Arrondissement)$")


def fetch(url, params=None, post=None, accept="application/json"):
    if params:
        url = f"{url}?{urllib.parse.urlencode(params)}"
    body = urllib.parse.urlencode(post).encode() if post else None
    req = urllib.request.Request(url, data=body,
                                 headers={"User-Agent": UA, "Accept": accept})
    with urllib.request.urlopen(req, timeout=240) as r:
        return json.loads(r.read().decode("utf-8"))


def sparql(query):
    return fetch(WIKIDATA, post={"query": query},
                 accept="application/sparql-results+json")["results"]["bindings"]


def point(wkt):
    """Wikidata hands coordinates back as the string Point(lon lat)."""
    lon, lat = wkt.removeprefix("Point(").removesuffix(")").split()
    return float(lat), float(lon)


def tidy(area):
    cleaned = SUFFIXES.sub("", area).strip()
    return cleaned or area


def readable(name):
    """Drops names that are mostly non latin, they are no use to an english speaking user."""
    letters = [c for c in name if c.isalpha()]
    return bool(letters) and sum(c.isascii() for c in letters) / len(letters) >= 0.8


def cached(name, build):
    """Reads a pass from .cache, or runs it and stores the result. Never caches an empty one."""
    path = CACHE / f"{name}.json"
    if path.exists():
        found = json.loads(path.read_text(encoding="utf-8"))
        print(f"  cached, {len(found)} kept")
        return found

    found = build()
    if not found:
        # krabi got cached as empty once and silently had no options for days
        print("  !! nothing found, not caching so a rerun retries", file=sys.stderr)
        return []
    path.write_text(json.dumps(found, ensure_ascii=False), encoding="utf-8")
    return found


def global_pass(qid, category, minlinks):
    rows = sparql(GLOBAL_QUERY % {"type": qid, "min": minlinks})

    # one row per area the place belongs to, so keep the most notable area per place
    best = {}
    for row in rows:
        wid = row["item"]["value"].rsplit("/", 1)[1]
        links = int(row["areaLinks"]["value"])
        if wid not in best or links > best[wid][0]:
            best[wid] = (links, row)

    found = []
    for links, row in best.values():
        name = row["label"]["value"]
        area = tidy(row["area"]["value"])
        if not readable(name) or not readable(area):
            continue
        latitude, longitude = point(row["point"]["value"])
        found.append({
            "id": int(wid_of(row)), "country": row["country"]["value"], "area": area,
            "category": category, "name": name, "address": f"{area}, {row['country']['value']}",
            "latitude": round(latitude, 5), "longitude": round(longitude, 5),
            "sitelinks": int(row["links"]["value"]),
        })
    print(f"  {len(rows)} rows -> {len(found)} places")
    return found


def wid_of(row):
    return row["item"]["value"].rsplit("/", 1)[1][1:]


def centre_of(query):
    hits = fetch(NOMINATIM, params={"q": query, "format": "json", "limit": 1,
                                    "accept-language": "en"})
    return (float(hits[0]["lat"]), float(hits[0]["lon"])) if hits else None


def local_pass(area, query, country):
    centre = centre_of(query)
    if not centre:
        print(f"  !! could not geocode {query}", file=sys.stderr)
        return []
    time.sleep(1.1)                       # nominatim asks for one request a second

    rows = []
    for radius in RADII_KM:
        rows = sparql(AROUND_QUERY % {
            "lat": centre[0], "lon": centre[1], "radius": radius,
            "types": " ".join(f"wd:{q}" for q in TYPES)})
        if rows:
            break
        print(f"  nothing within {radius}km, widening")

    found, seen = defaultdict(list), set()
    for row in rows:
        wid = row["item"]["value"].rsplit("/", 1)[1]
        name = row["itemLabel"]["value"]
        category = TYPES.get(row["type"]["value"].rsplit("/", 1)[1], (None,))[0]
        if not category or name == wid or not readable(name) or name.lower() in seen:
            continue
        seen.add(name.lower())
        latitude, longitude = point(row["coord"]["value"])
        found[category].append({
            "id": int(wid[1:]), "country": country, "area": area, "category": category,
            "name": name, "address": f"{area}, {country}",
            "latitude": round(latitude, 5), "longitude": round(longitude, 5),
            "sitelinks": int(row["sitelinks"]["value"]),
        })

    kept = []
    for items in found.values():
        items.sort(key=lambda r: (-r["sitelinks"], r["id"]))
        kept.extend(items[:LOCAL_PER_CATEGORY])
    print(f"  {len(rows)} rows -> {len(kept)} places")
    return kept


def known_countries():
    """The country names an itinerary can actually have, read from the two seed files."""
    names = set()
    for path in (RESOURCES / "data.sql", RESOURCES / "countries.sql"):
        if not path.exists():
            continue
        block = re.search(r"INSERT OR IGNORE INTO country[^;]*;",
                          path.read_text(encoding="utf-8"), re.I)
        if block:
            names.update(value.replace("''", "'") for value in
                         re.findall(r"\(\s*\d+\s*,\s*'((?:[^']|'')*)'\s*\)", block.group()))
    return names


def esc(value):
    return value.replace("'", "''")


def main():
    CACHE.mkdir(exist_ok=True)
    rows = []

    print("global pass, every notable place of each type worldwide")
    for qid, (category, minlinks) in TYPES.items():
        print(f"{qid} {category} sitelinks>={minlinks}")
        rows.extend(cached(f"global-{qid}",
                           lambda q=qid, c=category, m=minlinks: global_pass(q, c, m)))

    print("\nlocal pass, the destinations in areas.txt that the global pass would miss")
    for line in AREAS.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        area, query, country = line.split("|")
        print(f"{area} ({country})")
        rows.extend(cached(area.replace(" ", "-").lower(),
                           lambda a=area, q=query, c=country: local_pass(a, q, c)))

    # the local pass names areas the way our seed data does, eg "Niseko" not some ward, so
    # it wins a clash. it runs second, hence the reversed walk
    byId = {}
    for row in reversed(rows):
        byId[row["id"]] = row

    # P17 on an old building can point at a state that no longer exists, and a few labels
    # just differ. a row whose country is not in the country table can never be reached by an
    # itinerary, so it is dead weight in the file
    known = known_countries()
    usable, dropped = [], defaultdict(int)
    for row in byId.values():
        country = ALIASES.get(row["country"], row["country"])
        if country not in known:
            dropped[row["country"]] += 1
            continue
        row["country"] = country
        row["address"] = f"{row['area']}, {country}"
        usable.append(row)
    if dropped:
        print(f"\ndropped {sum(dropped.values())} options with no matching country:")
        for country, count in sorted(dropped.items(), key=lambda kv: -kv[1])[:8]:
            print(f"  {country:<44} {count}")

    # cap per area and category, most wikipedia articles first, id breaks ties so a rerun
    # produces the same file
    buckets = defaultdict(list)
    for row in usable:
        buckets[(row["country"], row["area"], row["category"])].append(row)
    kept = []
    for items in buckets.values():
        items.sort(key=lambda r: (-r["sitelinks"], r["id"]))
        kept.extend(items[:PER_AREA_CATEGORY])

    kept.sort(key=lambda r: (r["country"], r["area"], r["category"], r["id"]))

    lines = [
        "-- Generated by tools/build-options.py, do not edit by hand.",
        "-- Alternatives offered when an activity is disrupted, see RecommendationRules.",
        "-- Place data from Wikidata, CC0. Ids are wikidata q numbers.",
        f"-- {len(kept)} rows in batches of {CHUNK}, see the comment on CHUNK.",
        "",
    ]
    header = ("INSERT OR IGNORE INTO activity_option"
              " (id, country, area, category, name, address, latitude, longitude) VALUES")
    for start in range(0, len(kept), CHUNK):
        lines.append(header)
        lines.append(",\n".join(
            "    ({}, '{}', '{}', '{}', '{}', '{}', {}, {})".format(
                r["id"], esc(r["country"]), esc(r["area"]), r["category"],
                esc(r["name"]), esc(r["address"]), r["latitude"], r["longitude"])
            for r in kept[start:start + CHUNK]) + ";")
        lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8", newline="\r\n")

    indoor = {"MUSEUM_GALLERY", "FOOD_DINING", "SHOPPING", "WELLNESS_SPA"}
    areas = {(r["country"], r["area"]) for r in kept}
    print(f"\nwrote {len(kept)} options to {OUT}")
    print(f"  {len(areas)} areas in {len({r['country'] for r in kept})} countries")
    # only indoor ones are ever suggested, the rest are there to place an activity on the map
    print(f"  {sum(r['category'] in indoor for r in kept)} suggestable, "
          f"{sum(r['category'] not in indoor for r in kept)} for locating only")
    counts = defaultdict(int)
    for r in kept:
        counts[r["category"]] += 1
    for category, count in sorted(counts.items(), key=lambda kv: -kv[1]):
        print(f"    {category:<16} {count}")


if __name__ == "__main__":
    main()

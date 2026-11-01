"""
Builds src/main/resources/countries.sql so a traveller can pick any country, not just the
six we seeded by hand.

Dev machine only, the running app never calls wikidata.

    python tools/build-countries.py

The six countries already in data.sql keep their ids, because existing itineraries point at
them. Everything else starts at id 7, sorted by iso code so a rerun gives the same file.

Each country also gets its GDELT / FIPS 10-4 code (wikidata P901). Ingest links news to a
country through that code, so a country without one can never show any news.

Data from Wikidata, CC0.
"""

import json
import sys
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "src" / "main" / "resources" / "countries.sql"

UA = "TripSense-CS203-SMU/1.0 (student project; https://github.com/KeithTohx/cs203-project)"
WIKIDATA = "https://query.wikidata.org/sparql"

FIRST_ID = 7

# sqlite caps a multi row VALUES at 500 terms, so the rows go out in batches
CHUNK = 200

# already in data.sql with ids 1-6. country.name has no unique constraint and INSERT OR IGNORE
# only dedupes on id, so re-inserting these under a new id would show them twice in the dropdown
SEEDED = {"Japan", "Singapore", "Thailand", "South Korea", "Indonesia", "Vietnam"}

# places people visit that are not sovereign states, so the query below leaves them out.
# without these, any option generated for them could never be matched to an itinerary. taiwan
# is the one that actually mattered, it had 72 stranded options
EXTRA = [
    "Taiwan", "Hong Kong", "Macau", "Puerto Rico", "Greenland", "Gibraltar",
    "Faroe Islands", "Isle of Man", "Jersey", "Guernsey", "Guam",
    "French Polynesia", "New Caledonia", "Aruba", "Curacao", "Bermuda",
    "Cayman Islands", "Reunion", "Martinique", "Guadeloupe",
]

# FIPS 10-4 codes for the EXTRA places, which the sovereign-state query does not return.
# written by hand, so check any you care about against GDELT's country list
EXTRA_FIPS = {
    "Taiwan": "TW", "Hong Kong": "HK", "Macau": "MC", "Puerto Rico": "RQ",
    "Greenland": "GL", "Gibraltar": "GI", "Faroe Islands": "FO", "Isle of Man": "IM",
    "Jersey": "JE", "Guernsey": "GK", "Guam": "GQ", "French Polynesia": "FP",
    "New Caledonia": "NC", "Aruba": "AA", "Curacao": "UC", "Bermuda": "BD",
    "Cayman Islands": "CJ", "Reunion": "RE", "Martinique": "MB", "Guadeloupe": "GP",
}

# sovereign states that have an iso 3166-1 alpha-2 code, which filters out historical ones.
# P901 is the FIPS 10-4 code, optional because a few states have none
QUERY = """SELECT ?country ?countryLabel ?iso ?fips WHERE {
  ?country wdt:P31 wd:Q6256 ; wdt:P297 ?iso .
  OPTIONAL { ?country wdt:P901 ?fips . }
  SERVICE wikibase:label { bd:serviceParam wikibase:language "en" . }
}
ORDER BY ?iso"""


def sparql(query):
    body = urllib.parse.urlencode({"query": query}).encode()
    req = urllib.request.Request(WIKIDATA, data=body, headers={
        "User-Agent": UA, "Accept": "application/sparql-results+json"})
    with urllib.request.urlopen(req, timeout=90) as r:
        return json.loads(r.read().decode("utf-8"))["results"]["bindings"]


def esc(value):
    return value.replace("'", "''")


def sql_str(value):
    return "NULL" if value is None else f"'{esc(value)}'"


def main():
    rows = sparql(QUERY)
    if not rows:
        sys.exit("wikidata returned nothing, try again later")

    seen, countries, fips_by_name = set(), [], {}
    for row in rows:
        name = row["countryLabel"]["value"]
        iso = row["iso"]["value"]
        # a country with no english label comes back as its own q number
        if name.startswith("Q") and name[1:].isdigit():
            continue
        # a state with two fips codes comes back as two rows, keep the first code
        if "fips" in row:
            fips_by_name.setdefault(name, row["fips"]["value"])
        # a few states have two iso codes, keep the first
        if name in seen:
            continue
        seen.add(name)
        countries.append((iso, name))

    missing = SEEDED - seen
    if missing:
        # our six names have to appear exactly, or options generated under the wikidata label
        # would never match itinerary.getCountry().getName(), which is a string equality
        sys.exit(f"wikidata labels these differently: {sorted(missing)}. "
                 "fix SEEDED and the country rows in data.sql before generating")

    names = [name for _, name in countries if name not in SEEDED]
    names += [name for name in EXTRA if name not in seen and name not in SEEDED]

    def fips_for(name):
        return fips_by_name.get(name) or EXTRA_FIPS.get(name)

    values, updates, no_fips = [], [], []
    next_id = FIRST_ID
    for name in names:
        fips = fips_for(name)
        values.append(f"    ({next_id}, '{esc(name)}', {sql_str(fips)})")
        if fips:
            # INSERT OR IGNORE skips rows that already exist, so existing databases get the code
            # from this. matched on name, not id, so it cannot hit the wrong row if ids ever shift
            updates.append(f"UPDATE country SET fips_code = '{esc(fips)}' "
                           f"WHERE name = '{esc(name)}' AND fips_code IS NULL;")
        else:
            no_fips.append(name)
        next_id += 1

    lines = [
        "-- Generated by tools/build-countries.py, do not edit by hand.",
        "-- Every sovereign state with an ISO 3166-1 code, plus the territories in EXTRA,",
        "-- so a trip can be anywhere.",
        "-- Ids start at 7 because data.sql already seeds six countries that itineraries point at.",
        "-- fips_code is GDELT's country code, it is how ingested news is linked to a country.",
        "-- Country data from Wikidata, CC0.",
        "",
    ]
    # sqlite caps a multi row VALUES at 500 terms, so stay well under it
    for start in range(0, len(values), CHUNK):
        lines.append("INSERT OR IGNORE INTO country (id, name, fips_code) VALUES")
        lines.append(",\n".join(values[start:start + CHUNK]) + ";")
        lines.append("")
    lines.append("-- rows that already existed without a code (safe to re-run)")
    lines.extend(updates)
    lines.append("")
    OUT.write_text("\n".join(lines), encoding="utf-8", newline="\r\n")

    print(f"wrote {len(values)} countries to {OUT}")
    print(f"  ids {FIRST_ID} to {next_id - 1}, plus the 6 in data.sql")
    print(f"  skipped (already seeded): {', '.join(sorted(SEEDED))}")
    print(f"  with a fips code: {len(values) - len(no_fips)}, without: {len(no_fips)}")
    if no_fips:
        print(f"  no fips code (news can never link to these): {', '.join(no_fips)}")


if __name__ == "__main__":
    main()
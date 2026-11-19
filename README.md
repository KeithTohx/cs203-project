# TripSense

CS203 G3T2

A traveller saves a trip itinerary. The system collects disruption events from the
outside world (earthquakes, strikes, closures) and uses an AI model to work out
whether any of them actually affect that particular trip, and explains why.
Affected trips get an alert, an optional email summary, and suggested alternatives
for the activities that are disrupted.

During the project the app ran on an Azure VM, redeployed on every merge to `main`.
That server has since been shut down, so the app now runs locally only. How it was set
up is kept for reference in [deploy/README.md](deploy/README.md) and
[scripts/README.md](scripts/README.md).

## Requirements

- Java 25
- Maven is not needed. The project includes the Maven wrapper (`mvnw`).
- A Groq API key, or Ollama running locally. Only needed for the AI checks. See below.
- No database server. The app uses SQLite, which is a single file under `data/`.

## Running the app

```bash
git clone https://github.com/KeithTohx/cs203-project.git
cd cs203-project
./mvnw spring-boot:run
```

| What | URL |
|---|---|
| API documentation (Swagger UI) | http://localhost:8080/swagger-ui.html |
| Web page | http://localhost:8080/ |

Stop the app with `Ctrl+C`. Logs also go to `logs/tripsense.log`.

### Logging in

Every endpoint apart from register, Swagger and the web page itself needs a session.
Log in through the web page first, and Swagger then uses the same session cookie.
Outside the browser, `POST /api/auth/login` takes form fields `email` and `password`
(see `RestClient.http`). The sample accounts in `data.sql` are `alice@example.com` and
`bob@example.com` (role `USER`) and `admin@example.com` (role `ADMIN`). Ask the team
for the passwords, they are only stored as hashes.

`/api/admin/**` is admin only. An admin account can also be created at
start-up from `ADMIN_EMAIL`, `ADMIN_USERNAME` and `ADMIN_PASSWORD_HASH` (or
`ADMIN_PASSWORD`), see `AdminBootstrap.java`.

The database is created at `data/tripsense.db` the first time you run the app. To
look inside it:

```bash
sqlite3 data/tripsense.db ".tables"
sqlite3 data/tripsense.db "SELECT * FROM itinerary;"
```

If you prefer a window to a terminal, [DB Browser for SQLite](https://sqlitebrowser.org)
opens the same file.

### Running the tests

```bash
./mvnw test
```

CI runs the same tests on every pull request into `main`, and a failure blocks the merge.

### Running the AI model

Two providers are wired in, and `CHAT_PROVIDER` picks one without a rebuild.

**Groq (the default).** A hosted model reached through the OpenAI-compatible API:

```bash
export GROQ_KEY=your-key
./mvnw spring-boot:run
```

**Ollama.** For working offline, or when the Groq quota runs out:

```bash
ollama pull qwen2.5:1.5b
ollama serve
CHAT_PROVIDER=ollama ./mvnw spring-boot:run
```

The app still starts without either. If the model is not reachable, a disruption check
is saved with the verdict `UNKNOWN` and the reason recorded, instead of failing the
request, so you can work on the rest of the app without a model.

Groq's free tier is limited by tokens per minute and per day, so the app paces itself
(`chat.rate-limit.per-minute`), waits out `429` replies, and caps how many AI calls the
news and impact jobs make per run. If you see checks stuck on `UNKNOWN`, the quota is
the first thing to look at.

## Project structure

```
cs203-project/
  pom.xml                          dependencies and build configuration
  mvnw, mvnw.cmd, .mvn/            Maven wrapper, so everyone builds the same way
  data/                            SQLite database file, created at runtime, not committed
  schema.sql                       the tables, written out for humans to read

  deploy/, scripts/                how the Azure VM was set up, kept for reference
  tools/                           python scripts that generate the seed data
  .github/workflows/               ci.yml tests pull requests, deploy.yml shipped main to the VM

  src/main/java/csd/tripsense/
    TripSenseApplication.java      starts the app
    OpenAPIConfig.java             title and description for the Swagger UI page
    <feature>/                     one package per feature, see below

  src/main/resources/
    application.properties         database, logging, AI model and job settings
    data.sql, countries.sql,
    options.sql                    seed data, see "Sample data"
    gdelt-country-names.txt        country names and aliases used to tag news
    static/                        the web page, served at http://localhost:8080/

  src/test/java/csd/tripsense/     tests, mirroring the main packages
```

`src/main/java` and `src/test/java` are fixed by Maven. `csd/tripsense` matches the
Java package name, so those folders have to line up with `package csd.tripsense;`.
Everything below that is ours to organise.

## How the code is organised

Each feature gets its own package under `csd/tripsense/`, built from the same six
classes. Taking `itinerary` as the example:

| Class | Job |
|---|---|
| `Itinerary.java` | the JPA entity. One class, one database table |
| `ItineraryRepository.java` | interface extending `JpaRepository`. Spring writes the implementation |
| `ItineraryService.java` | interface describing what the feature can do |
| `ItineraryServiceImpl.java` | the business logic |
| `ItineraryController.java` | the REST endpoints. Talks to the service, never to the repository |
| `ItineraryNotFoundException.java` | annotated `@ResponseStatus(HttpStatus.NOT_FOUND)` so Spring returns a 404 |

Every other feature package looks the same with its own name in place of
`Itinerary`. The packages are:

| Package | What it does |
|---|---|
| `user`, `auth` | accounts, preferences, login sessions and the security rules |
| `itinerary`, `activity`, `country` | the trips themselves |
| `news`, `gdelt` | stored news, and the job that pulls it in from GDELT |
| `impact` | the AI verdict on whether a story affects a trip or activity |
| `recommendation` | suggested alternatives for a disrupted activity |
| `alert` | in-app alerts, live updates and grouped alert emails |
| `admin`, `audit`, `notice` | the admin console, its audit log, and notices to users about admin edits |
| `chat` | the adapter to the AI model, plus the chatbot |

A package only has the classes it actually needs. `country` has no exception class
because nothing looks a country up by id and fails. `chat` is mostly an adapter: it
talks to the AI model, so most of it has no entity or repository.

Requests flow one way:

```
HTTP request -> Controller -> Service -> Repository -> database
```

Keeping the controller out of the repository is what lets us change how something
is stored without touching the API, and test the logic without starting a web server.

## How news becomes an alert

1. **Ingest.** GDELT publishes a new GKG file every 15 minutes. `GdeltIngestJob` checks
   for one every 10 minutes (`gdelt.ingest.interval-ms`), so no file is skipped, and
   ignores a file it has already processed.
2. **Tag.** `GkgParser` keeps English web articles and gives each exactly one country,
   the place the story is about. A country named in the headline wins; otherwise the
   one the article mentions clearly more than any other. Anything unclear is dropped.
3. **Filter.** `GkgRelevanceFilter` asks the AI, in batches, whether each story is a
   travel disruption at all. Only those are stored as `News`.
4. **Evaluate.** After each file, the impact job checks recent news against trips in the
   same country. New trips and activities are also checked against news already stored.
5. **Alert.** An affected trip gets an `Alert`, pushed to the page live. If email is
   enabled, alerts for the same itinerary are grouped into one summary email.

## Adding a new feature

Say we wanted to store hotel bookings. `Booking` is not part of this project - it
is only here to show the steps:

1. Create the package `src/main/java/csd/tripsense/booking/`.
2. Add `Booking.java`, annotated `@Entity`, with Lombok's `@Getter` and `@Setter`.
3. Add `BookingRepository extends JpaRepository<Booking, Long>`. For anything beyond
   the built-in methods, declare a query by naming it, such as `findByHotelName`.
4. Add `BookingService` and `BookingServiceImpl`, with `@Service` on the implementation.
5. Add `BookingController` with `@RestController`. Take the service through the
   constructor rather than using `@Autowired` on a field.
6. Add `BookingNotFoundException`.
7. Add the new endpoints to `src/main/resources/static/RestClient.http` so the rest
   of the team can try them without the UI.
8. Update `schema.sql` with the new table.
9. Restart the app and check the endpoints appear in the Swagger UI.

The database schema is generated from the entities, so adding a field to a class is
enough. There are no migration scripts to write.

## Changing the database schema

The `@Entity` classes are the only description of the schema. There are no
migration scripts, and nothing runs `schema.sql`.

1. To add a table, add an entity class. To add a column, add a field to one.
2. Restart the app. Hibernate creates anything that is missing.
3. Update `schema.sql` in the same commit, so it still matches the code.

`ddl-auto=update` only ever *adds*. It will not rename a column, change its type,
or drop anything, and SQLite's own `ALTER TABLE` support is limited on top of that.

**So when a change does not appear, delete the database and let it rebuild:**

```bash
rm data/tripsense.db
./mvnw spring-boot:run
```

This is a normal step while developing, not a sign something is broken. Sample data
is re-inserted on the next start. Do not do it on anything you wanted to keep.

## Sample data

Three files in `src/main/resources/` are loaded on every start, in this order:

| File | Written by | What it holds |
|---|---|---|
| `data.sql` | by hand | sample accounts, trips, activities, news, impacts, recommendations |
| `countries.sql` | `tools/build-countries.py` | every sovereign state, so a trip can be anywhere |
| `options.sql` | `tools/build-options.py` | places to go instead when an activity is disrupted |

All three use `INSERT OR IGNORE` with fixed ids, so they are safe to re-run and **a change to
one of them does nothing to a database that already has those rows**. Delete
`data/tripsense.db` and restart to pick up an edit.

The two generated files are committed, so nobody else has to run the scripts or wait on an
API. To regenerate them yourself:

```bash
pip install -r tools/requirements.txt
python tools/build-countries.py        # a few seconds
python tools/build-options.py          # a few minutes, cached in tools/.cache/
python tools/export-seed-xlsx.py       # rebuilds data/seed-data.xlsx
```

`data/seed-data.xlsx` is the same data as a workbook, one sheet per table, for reading
without going through `INSERT` statements. It is generated from the database *after* it has
been seeded, so it shows what actually landed rather than what we think the SQL says.

**The app itself never calls an external place API.** The scripts are development tools; at
runtime everything is read from these tables. That is deliberate — it keeps requests fast and
the demo working offline, and it is why the AI model can only ever pick a venue that exists.

Place and country data from [Wikidata](https://www.wikidata.org), released under
[CC0](https://creativecommons.org/publicdomain/zero/1.0/). Area coordinates are looked up
through [Nominatim](https://nominatim.openstreetmap.org), © OpenStreetMap contributors.

## Configuration

Everything configurable is in `src/main/resources/application.properties`, and most
of it can be overridden with an environment variable, without a code change.

- **Database.** SQLite, one file at `data/tripsense.db` (`DB_PATH`). Rows survive a restart.
- **Logging.** The framework is set to `WARN` so our own `DEBUG` lines are readable.
- **AI model.** `CHAT_PROVIDER` (`openai` for Groq, or `ollama`), `GROQ_KEY`, `CHAT_MODEL`,
  `CHAT_MODELS` (fallback models to try in order) and `CHAT_RATE_LIMIT`. Temperature is
  low because we want a consistent yes/no answer rather than a creative one.
- **News and impact jobs.** `GDELT_AI_MAX`, `IMPACT_EVAL_ENABLED` and `IMPACT_EVAL_MAX`
  control how many AI calls the jobs may spend. The 10 minute GDELT interval is set
  directly in `gdelt.ingest.interval-ms`. On Groq's free tier the daily quota can run out
  before the end of the day; checks after that are saved as `UNKNOWN` until it resets.
- **Email alerts.** Off by default. Set `alerts.email.enabled=true`, `alerts.email.from`
  and Spring's `spring.mail.*` settings (host, port, username, password). The app refuses
  to start if email is enabled without them.
- **Port.** `PORT`, 8080 by default.

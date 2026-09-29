# TripSense

CS203 G3T2

A traveller saves a trip itinerary. The system collects disruption events from the
outside world (earthquakes, strikes, closures) and uses an AI model to work out
whether any of them actually affect that particular trip, and explains why.

## Requirements

- Java 25
- Maven is not needed. The project includes the Maven wrapper (`mvnw`).
- Ollama, optional. Only needed for the AI disruption checks. See below.
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

Stop the app with `Ctrl+C`.

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

### Running the AI model

The disruption checks call a local model through Ollama:

```bash
ollama pull phi4-mini
ollama serve
```

The app runs without Ollama. If the model is not reachable, a disruption check is
saved with the verdict `UNKNOWN` and the reason recorded, instead of failing the
request. This means you can work on the rest of the app without installing a model.

## Project structure

```
cs203-project/
  pom.xml                          dependencies and build configuration
  mvnw, mvnw.cmd, .mvn/            Maven wrapper, so everyone builds the same way
  data/                            SQLite database file, created at runtime, not committed
  schema.sql                       the tables, written out for humans to read

  src/main/java/csd/tripsense/
    TripSenseApplication.java      starts the app
    OpenAPIConfig.java             title and description for the Swagger UI page

  src/main/resources/
    application.properties         database, logging and AI model settings
    static/                        the web page, served at http://localhost:8080/

  src/test/java/csd/tripsense/
    TripSenseApplicationTests.java
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
`Itinerary`. The feature packages are `activity`, `country`, `impact`, `itinerary`,
`news` and `user`.

A package only has the classes it actually needs. `country` has no exception class
because nothing looks a country up by id and fails. `chat` is not a feature at all:
it is an adapter holding `ChatService` and `ChatServiceImpl`, with no entity,
repository or controller, because it only talks to the AI model and stores nothing.

Requests flow one way:

```
HTTP request -> Controller -> Service -> Repository -> database
```

Keeping the controller out of the repository is what lets us change how something
is stored without touching the API, and test the logic without starting a web server.

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

## Configuration

Everything configurable is in `src/main/resources/application.properties`:

- **Database.** SQLite, one file at `data/tripsense.db`. Rows survive a restart.
- **Logging.** The framework is set to `WARN` so our own `DEBUG` lines are readable.
- **AI model.** Points at Ollama on `localhost:11434`. Temperature is low because we
  want a consistent yes/no answer rather than a creative one.

package no.vebb.f1.database;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

import no.vebb.f1.user.PublicUser;
import no.vebb.f1.util.collection.userTables.Summary;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import no.vebb.f1.util.*;
import no.vebb.f1.util.collection.*;
import no.vebb.f1.util.domainPrimitive.*;
import no.vebb.f1.util.exception.NoAvailableRaceException;
import no.vebb.f1.user.User;
import no.vebb.f1.user.UserMail;

@Service
@SuppressWarnings("DataFlowIssue")
public class Database {

    private final JdbcTemplate jdbcTemplate;

    public Database(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Gets the cutoff for guessing on categories that happens before the season
     * starts.
     *
     * @param year of season
     * @return time as Instant
     * @throws EmptyResultDataAccessException if year cutoff does not exist
     */
    public Instant getCutoffYear(Year year) throws EmptyResultDataAccessException {
        final String getCutoff = "SELECT cutoff FROM YearCutoff WHERE year = ?;";
        return Instant.parse(jdbcTemplate.queryForObject(getCutoff, String.class, year));
    }

    /**
     * Gets the cutoff for guessing on race specific categories.
     *
     * @param raceId of race
     * @return time as Instant
     * @throws NoAvailableRaceException if race does not have a cutoff set
     */
    public Instant getCutoffRace(RaceId raceId) throws NoAvailableRaceException {
        try {
            final String getCutoff = "SELECT cutoff FROM RaceCutoff WHERE race_number = ?;";
            return Instant.parse(jdbcTemplate.queryForObject(getCutoff, String.class, raceId));
        } catch (EmptyResultDataAccessException e) {
            throw new NoAvailableRaceException("There is no cutoff for the given raceId '" + raceId + "'");
        }
    }

    /**
     * Checks if the user is admin based on given id.
     *
     * @param userId to check
     * @return true if user is admin
     */
    public boolean isUserAdmin(UUID userId) {
        final String sql = "SELECT COUNT(*) FROM Admin WHERE user_id = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, userId) > 0;
    }

    /**
     * Gets the User object for the given id.
     *
     * @param userId for user to get
     * @return User
     * @throws EmptyResultDataAccessException if user not found in database
     */
    public User getUserFromId(UUID userId) throws EmptyResultDataAccessException {
        final String sql = "SELECT username, google_id FROM User WHERE id = ?;";
        Map<String, Object> sqlRes = jdbcTemplate.queryForMap(sql, userId);
        String username = (String) sqlRes.get("username");
        String googleId = (String) sqlRes.get("google_id");
        return new User(googleId, userId, username);
    }

    /**
     * Gets the User object for the given googleId.
     *
     * @param googleId as String
     * @return User
     * @throws EmptyResultDataAccessException if user not found in database
     */
    public User getUserFromGoogleId(String googleId) throws EmptyResultDataAccessException {
        final String sql = "SELECT username, id FROM User WHERE google_id = ?;";
        Map<String, Object> sqlRes = jdbcTemplate.queryForMap(sql, googleId);
        String username = (String) sqlRes.get("username");
        UUID id = UUID.fromString((String) sqlRes.get("id"));
        return new User(googleId, id, username);
    }

    /**
     * Gets the latest race number that has had a race.
     *
     * @param year of season
     * @return race number of race
     */
    public RaceId getLatestRaceId(Year year) throws EmptyResultDataAccessException {
        final String getRaceIdSql = """
                SELECT ro.id
                FROM RaceOrder ro
                JOIN RaceResult rr ON ro.id = rr.race_number
                WHERE ro.year = ?
                ORDER BY ro.position DESC
                LIMIT 1;
                """;

        return new RaceId(jdbcTemplate.queryForObject(getRaceIdSql, Integer.class, year));
    }

    /**
     * Gets the position of a race within the season it is in.
     *
     * @param raceId of race
     * @return position of race
     */
    public int getPositionOfRace(RaceId raceId) {
        final String getRacePosition = "SELECT position FROM RaceOrder WHERE id = ?;";
        return jdbcTemplate.queryForObject(getRacePosition, Integer.class, raceId);
    }

    /**
     * Gets the data for a users guesses on flags up until the given race position
     * of the given year. If race position is 0, actual amount will also be 0.
     *
     * @param racePos position within a season
     * @param year    of season
     * @param userId  of guesser/user
     * @return "table" of guesses
     */
    public List<Map<String, Object>> getDataForFlagTable(int racePos, Year year, UUID userId) {
        if (racePos == 0) {
            final String sqlNoRace = """
                    SELECT f.name AS type, fg.amount AS guessed, 0 AS actual
                    FROM Flag f
                    JOIN FlagGuess fg ON f.name = fg.flag
                    JOIN RaceOrder ro ON fg.year = ro.year
                    WHERE ro.year = ? AND fg.guesser = ?
                    GROUP BY f.name;
                    """;
            return jdbcTemplate.queryForList(sqlNoRace, year, userId);
        } else {
            final String sql = """
                    SELECT f.name AS type, fg.amount AS guessed, COALESCE(COUNT(fs.flag), 0) AS actual
                    FROM Flag f
                    JOIN FlagGuess fg ON f.name = fg.flag
                    JOIN RaceOrder ro ON fg.year = ro.year
                    LEFT JOIN FlagStats fs ON fs.flag = f.name AND fs.race_number = ro.id
                    WHERE ro.year = ? AND fg.guesser = ? AND ro.position <= ?
                    GROUP BY f.name;
                    """;
            return jdbcTemplate.queryForList(sql, year, userId, racePos);
        }
    }

    /**
     * Gets the data for a users guesses on the given race of the given year.
     * Columns: race_position, race_name, driver, start, finish
     *
     * @param category to get table for
     * @param userId   of guesser/user
     * @param year     of season
     * @param racePos  position within a season
     * @return "table" of guesses
     */
    public List<Map<String, Object>> getDataForPlaceGuessTable(Category category, UUID userId, Year year, int racePos) {
        final String sql = """
                SELECT ro.position as race_position, r.name AS race_name, dpg.driver AS driver, sg.position AS start, rr.finishing_position AS finish
                FROM DriverPlaceGuess dpg
                JOIN Race r ON r.id = dpg.race_number
                JOIN RaceOrder ro ON r.id = ro.id
                JOIN StartingGrid sg ON sg.race_number = r.id AND dpg.driver = sg.driver
                JOIN RaceResult rr ON rr.race_number = r.id AND dpg.driver = rr.driver
                WHERE dpg.category = ? AND dpg.guesser = ? AND ro.year = ? AND ro.position <= ?
                ORDER BY ro.position;
                """;
        return jdbcTemplate.queryForList(sql, category, userId, year, racePos);
    }

    /**
     * Gets a list of drivers guessed by the given user in the given year.
     * Ordered by position of guesses in ascending order.
     *
     * @param year   of season
     * @param userId of user
     * @return drivers ascendingly
     */
    public List<Driver> getGuessedYearDriver(Year year, UUID userId) {
        final String guessedSql = "SELECT driver FROM DriverGuess WHERE year = ?  AND guesser = ? ORDER BY position";
        return jdbcTemplate.queryForList(guessedSql, String.class, year, userId).stream()
                .map(Driver::new)
                .toList();
    }

    /**
     * Gets a list of constructors guessed by the given user in the given year.
     * Ordered by position of guesses in ascending order.
     *
     * @param year   of season
     * @param userId of user
     * @return constructors ascendingly
     */
    public List<Constructor> getGuessedYearConstructor(Year year, UUID userId) {
        final String guessedSql = """
                SELECT constructor
                FROM ConstructorGuess
                WHERE year = ? AND guesser = ?
                ORDER BY position;
                """;
        return jdbcTemplate.queryForList(guessedSql, String.class, year, userId).stream()
                .map(Constructor::new)
                .toList();
    }

    /**
     * Gets the driver standings for a given race and year.
     * If the race id is set to -1, the position set in the DriverYear will be used as
     * default order.
     * Ordered by position of standings in ascending order.
     *
     * @param raceId of race
     * @param year   of season
     * @return drivers ascendingly
     */
    public List<Driver> getDriverStandings(RaceId raceId, Year year) {
        final String driverYearSql = "SELECT driver FROM DriverYear WHERE year = ? ORDER BY position;";
        final String driverStandingsSql = "SELECT driver FROM DriverStandings WHERE race_number = ? ORDER BY position;";
        List<String> result = getCompetitors(raceId, year, driverYearSql, driverStandingsSql);
        return result.stream().map(Driver::new).toList();
    }

    private List<String> getCompetitors(RaceId raceId, Year year, final String competitorYearSql, final String competitorStandingsSql) {
        List<String> result;
        if (raceId == null) {
            result = jdbcTemplate.queryForList(competitorYearSql, String.class, year);
        } else {
            result = jdbcTemplate.queryForList(competitorStandingsSql, String.class, raceId);
        }
        return result;
    }

    /**
     * Gets the constructor standings for a given race and year.
     * If the race number is set to -1, the position set in the ConstructorYear will be used as
     * default order.
     * Ordered by position of standings in ascending order.
     *
     * @param raceId of race
     * @param year   of season
     * @return constructors ascendingly
     */
    public List<Constructor> getConstructorStandings(RaceId raceId, Year year) {
        final String constructorYearSql = "SELECT constructor FROM ConstructorYear WHERE year = ? ORDER BY position;";
        final String constructorStandingsSql = """
                SELECT constructor
                FROM ConstructorStandings
                WHERE race_number = ?
                ORDER BY position;
                """;
        List<String> result = getCompetitors(raceId, year, constructorYearSql, constructorStandingsSql);
        return result.stream().map(Constructor::new).toList();
    }

    /**
     * Checks if the username is already in use by a user.
     * NOTE: Username should be in uppercase.
     *
     * @param usernameUpper the username in uppercase
     * @return true if username is in use
     */
    public boolean isUsernameInUse(String usernameUpper) {
        final String sqlCheckUsername = "SELECT COUNT(*) FROM User WHERE username_upper = ?;";
        return jdbcTemplate.queryForObject(sqlCheckUsername, Integer.class, usernameUpper) > 0;
    }

    /**
     * Updates the username of the given user to the given username.
     *
     * @param username to set as new username
     * @param userId   of user
     */
    public void updateUsername(Username username, UUID userId) {
        final String updateUsername = """
                UPDATE User
                SET username = ?, username_upper = ?
                WHERE id = ?;
                """;
        jdbcTemplate.update(updateUsername, username.username, username.usernameUpper, userId);
    }

    /**
     * Deletes the account of the given user.
     * Sets the username to 'Anonym' and google_id to id.
     *
     * @param userId of user
     */
    public void deleteUser(UUID userId) {
        final String deleteUser = """
                UPDATE User
                SET username = 'Anonym', username_upper = 'ANONYM', google_id = ?
                WHERE id = ?;
                """;
        clearUserFromMailing(userId);
        removeBingomaster(userId);
        jdbcTemplate.update(deleteUser, userId, userId);
    }

    /**
     * Adds a user with the given username and google ID to the database.
     * Sets a random UUID as the users ID.
     *
     * @param username of the user
     * @param googleId the ID provided by OAUTH
     */
    public void addUser(Username username, String googleId) {
        final String sqlInsertUsername = "INSERT INTO User (google_id, id,username, username_upper) VALUES (?, ?, ?, ?);";
        jdbcTemplate.update(sqlInsertUsername, googleId, UUID.randomUUID(), username.username, username.usernameUpper);
    }

    /**
     * Gets the guesses of all users for the given race in the given category.
     *
     * @param raceId   of race
     * @param category for guesses
     * @return list of guesses
     */
    public List<UserRaceGuess> getUserGuessesDriverPlace(RaceId raceId, Category category) {
        final String getGuessSql = """
                SELECT u.username AS username, dpg.driver AS driver, sg.position AS position
                FROM DriverPlaceGuess dpg
                JOIN User u ON u.id = dpg.guesser
                JOIN StartingGrid sg ON sg.race_number = dpg.race_number AND sg.driver = dpg.driver
                WHERE dpg.race_number = ? AND dpg.category = ?
                ORDER BY u.username;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getGuessSql, raceId, category);
        return sqlRes.stream()
                .map(row -> new UserRaceGuess(
                        (String) row.get("username"),
                        (String) row.get("driver"),
                        (int) row.get("position")))
                .toList();
    }

    /**
     * Gets the latest race which has a starting grid available in the given year.
     *
     * @param year of season
     * @return race
     * @throws EmptyResultDataAccessException if there is no race within the criteria
     */
    public CutoffRace getLatestRaceForPlaceGuess(Year year) throws EmptyResultDataAccessException {
        final String getRaceIdSql = """
                SELECT ro.id AS id, ro.position AS position, r.name AS name
                FROM RaceOrder ro
                JOIN StartingGrid sg ON ro.id = sg.race_number
                JOIN Race r ON r.id = ro.id
                WHERE ro.year = ?
                ORDER BY ro.position DESC
                LIMIT 1;
                """;
        Map<String, Object> res = jdbcTemplate.queryForMap(getRaceIdSql, year);
        RaceId raceId = new RaceId((int) res.get("id"));
        return new CutoffRace((int) res.get("position"), (String) res.get("name"), raceId);
    }

    /**
     * Gets the current race to guess on. Only returns a race if there is a race
     * that has a starting grid and not race result.
     *
     * @return race id
     * @throws EmptyResultDataAccessException if there is no race within the criteria
     */
    public RaceId getCurrentRaceIdToGuess() throws EmptyResultDataAccessException {
        final String getRaceId = """
                SELECT DISTINCT race_number
                FROM StartingGrid sg
                WHERE sg.race_number NOT IN (
                	SELECT rr.race_number
                	FROM RaceResult rr
                );
                """;
        return new RaceId(jdbcTemplate.queryForObject(getRaceId, Integer.class));
    }

    /**
     * Adds the guesses of flags of a user into the given year.
     * Overwrites pre-existing guesses.
     *
     * @param userId of user
     * @param year   of season
     * @param flags  the user guessed
     */
    public void addFlagGuesses(UUID userId, Year year, Flags flags) {
        final String sql = "REPLACE INTO FlagGuess (guesser, flag, year, amount) values (?, ?, ?, ?);";
        jdbcTemplate.update(sql, userId, "Yellow Flag", year, flags.yellow);
        jdbcTemplate.update(sql, userId, "Red Flag", year, flags.red);
        jdbcTemplate.update(sql, userId, "Safety Car", year, flags.safetyCar);
    }

    /**
     * Gets the flag guesses of the given user in the given year.
     *
     * @param userId of user
     * @param year   of season
     * @return flag guesses
     */
    public Flags getFlagGuesses(UUID userId, Year year) {
        final String sql = "SELECT flag, amount FROM FlagGuess WHERE guesser = ? AND year = ?;";
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(sql, userId, year);
        Flags flags = new Flags();
        for (Map<String, Object> row : sqlRes) {
            String flag = (String) row.get("flag");
            int amount = (int) row.get("amount");
            switch (flag) {
                case "Yellow Flag":
                    flags.yellow = amount;
                    break;
                case "Red Flag":
                    flags.red = amount;
                    break;
                case "Safety Car":
                    flags.safetyCar = amount;
                    break;
            }
        }
        return flags;
    }

    /**
     * Gets number of seconds remaining to guess in the given race.
     *
     * @param raceId of race
     * @return time left in seconds
     */
    public long getTimeLeftToGuessRace(RaceId raceId) {
        Instant now = Instant.now();
        final String getCutoff = "SELECT cutoff FROM RaceCutoff WHERE race_number = ?;";
        Instant cutoff = Instant.parse(jdbcTemplate.queryForObject(getCutoff, String.class, raceId));
        return Duration.between(now, cutoff).toSeconds();
    }

    public int getTimeLeftToGuessRaceHours(RaceId raceId) {
        return (int) (getTimeLeftToGuessRace(raceId) / 3600L);
    }

    /**
     * Gets number of seconds remaining to guess in the year.
     *
     * @return time left in seconds
     */
    public long getTimeLeftToGuessYear() {
        Instant now = Instant.now();
        final String getCutoff = "SELECT cutoff FROM YearCutoff WHERE year = ?;";
        Instant cutoffYear = Instant
                .parse(jdbcTemplate.queryForObject(getCutoff, String.class, TimeUtil.getCurrentYear()));
        return Duration.between(now, cutoffYear).toSeconds();
    }

    /**
     * Gets a list of starting grid from the given race.
     * Drivers are ordered from first to last ascendingly.
     *
     * @param raceId of race
     * @return drivers ascendingly
     */
    public List<Driver> getDriversFromStartingGrid(RaceId raceId) {
        final String getDriversFromGrid = "SELECT driver FROM StartingGrid WHERE race_number = ? ORDER BY position;";
        return jdbcTemplate.queryForList(getDriversFromGrid, String.class, raceId).stream()
                .map(Driver::new)
                .toList();
    }

    public List<ColoredCompetitor<Driver>> getDriversFromStartingGridWithColors(RaceId raceId) {
        final String getDriversFromGrid = """
                SELECT sg.driver as driver, cc.color as color
                FROM StartingGrid sg
                LEFT JOIN DriverTeam dt ON dt.driver = sg.driver
                LEFT JOIN ConstructorColor cc ON cc.constructor = dt.team
                WHERE race_number = ?
                ORDER BY position;
                """;
        return jdbcTemplate.queryForList(getDriversFromGrid, raceId).stream()
                .map(row ->
                        new ColoredCompetitor<>(
                                new Driver((String) row.get("driver")),
                                new Color((String) row.get("color")))
                )
                .toList();
    }

    /**
     * Gets the previous guess of a user on driver place guess.
     *
     * @param raceId   of race
     * @param category guessed on
     * @param userId   of the user
     * @return name of driver guessed
     */
    public Driver getGuessedDriverPlace(RaceId raceId, Category category, UUID userId) {
        final String getPreviousGuessSql = """
                SELECT driver
                FROM DriverPlaceGuess
                WHERE race_number = ? AND category = ? AND guesser = ?;
                """;
        return new Driver(jdbcTemplate.queryForObject(getPreviousGuessSql, String.class, raceId, category, userId));
    }

    /**
     * Adds driver place guess to the database.
     *
     * @param userId   of the guesser
     * @param raceId   of race
     * @param driver   name guessed
     * @param category which the user guessed on
     */
    public void addDriverPlaceGuess(UUID userId, RaceId raceId, Driver driver, Category category) {
        final String insertGuessSql = "REPLACE INTO DriverPlaceGuess (guesser, race_number, driver, category) values (?, ?, ?, ?);";
        jdbcTemplate.update(insertGuessSql, userId, raceId, driver, category);
    }

    /**
     * Gets a list of a users guesses on a drivers in a given season.
     *
     * @param userId of user
     * @param year   of season
     * @return competitors ascendingly
     */
    public List<ColoredCompetitor<Driver>> getDriversGuess(UUID userId, Year year) {
        final String getGuessedSql = """
                SELECT dg.driver as driver, cc.color as color
                FROM DriverGuess dg
                LEFT JOIN DriverTeam dt ON dt.driver = dg.driver
                LEFT JOIN ConstructorColor cc ON cc.constructor = dt.team
                WHERE dg.guesser = ?
                ORDER BY position;
                """;
        final String getDriversSql = """
                SELECT dy.driver as driver, cc.color as color
                FROM DriverYear dy
                LEFT JOIN DriverTeam dt ON dt.driver = dy.driver
                LEFT JOIN ConstructorColor cc ON cc.constructor = dt.team
                WHERE dy.year = ?
                ORDER BY position;
                """;
        return getCompetitorGuess(userId, year, getGuessedSql, getDriversSql).stream()
                .map(row -> new ColoredCompetitor<>(
                        new Driver((String) row.get("driver")),
                        new Color((String) row.get("color"))))
                .toList();
    }

    /**
     * Gets a list of a users guesses on constructors in a given season.
     *
     * @param userId of user
     * @param year   of season
     * @return competitors ascendingly
     */
    public List<ColoredCompetitor<Constructor>> getConstructorsGuess(UUID userId, Year year) {
        final String getGuessedSql = """
                SELECT cg.constructor as constructor, cc.color as color
                FROM ConstructorGuess cg
                LEFT JOIN ConstructorColor cc ON cc.constructor = cg.constructor
                WHERE cg.guesser = ?
                ORDER BY position;
                """;
        final String getConstructorsSql = """
                SELECT cy.constructor as constructor, cc.color as color
                FROM ConstructorYear cy
                LEFT JOIN ConstructorColor cc ON cc.constructor = cy.constructor
                WHERE cy.year = ?
                ORDER BY position;
                """;
        return getCompetitorGuess(userId, year, getGuessedSql, getConstructorsSql).stream()
                .map(row -> new ColoredCompetitor<>(
                        new Constructor((String) row.get("constructor")),
                        new Color((String) row.get("color"))))
                .toList();
    }

    private List<Map<String, Object>> getCompetitorGuess(UUID userId, Year year, final String getGuessedSql,
                                                         final String getCompetitorsSql) {
        List<Map<String, Object>> competitors = jdbcTemplate.queryForList(getGuessedSql, userId);
        if (competitors.isEmpty()) {
            return jdbcTemplate.queryForList(getCompetitorsSql, year);
        }
        return competitors;
    }

    /**
     * Gets a list of a yearly drivers in a given season.
     *
     * @param year of season
     * @return drivers ascendingly
     */
    public List<Driver> getDriversYear(Year year) {
        final String getDriversSql = "SELECT driver FROM DriverYear WHERE year = ? ORDER BY position;";
        return jdbcTemplate.queryForList(getDriversSql, String.class, year).stream()
                .map(Driver::new)
                .toList();
    }

    /**
     * Gets a list of a yearly constructors in a given season.
     *
     * @param year of season
     * @return constructors ascendingly
     */
    public List<Constructor> getConstructorsYear(Year year) {
        final String getConstructorSql = "SELECT constructor FROM ConstructorYear WHERE year = ? ORDER BY position;";
        return jdbcTemplate.queryForList(getConstructorSql, String.class, year).stream()
                .map(Constructor::new)
                .toList();
    }

    /**
     * Adds a guess for a user on the ranking of a driver.
     *
     * @param userId   of user
     * @param driver   name
     * @param year     of season
     * @param position guessed
     */
    public void insertDriversYearGuess(UUID userId, Driver driver, Year year, int position) {
        final String addRowDriver = "REPLACE INTO DriverGuess (guesser, driver, year, position) values (?, ?, ?, ?);";
        jdbcTemplate.update(addRowDriver, userId, driver, year, position);
    }

    /**
     * Adds a guess for a user on the ranking of a constructor.
     *
     * @param userId      of user
     * @param constructor name
     * @param year        of season
     * @param position    guessed
     */
    public void insertConstructorsYearGuess(UUID userId, Constructor constructor, Year year, int position) {
        final String addRowConstructor = "REPLACE INTO ConstructorGuess (guesser, constructor, year, position) values (?, ?, ?, ?);";
        jdbcTemplate.update(addRowConstructor, userId, constructor, year, position);
    }

    /**
     * Gets a list of all guessing categories.
     *
     * @return categories
     */
    public List<Category> getCategories() {
        final String sql = "SELECT name FROM Category;";
        return jdbcTemplate.queryForList(sql, String.class).stream()
                .map(name -> new Category(name, this))
                .toList();
    }

    /**
     * Checks if the given category is a valid category
     *
     * @param category name
     * @return true if category is valid
     */
    public boolean isValidCategory(String category) {
        final String validateCategory = "SELECT COUNT(*) FROM Category WHERE name = ?;";
        return jdbcTemplate.queryForObject(validateCategory, Integer.class, category) > 0;
    }

    /**
     * Gets a mapping from the difference of a guess to the points
     * obtained by the difference in a given category.
     *
     * @param category name
     * @param year     of season
     * @return map from diff to points
     */
    public Map<Diff, Points> getDiffPointsMap(Year year, Category category) {
        final String sql = """
                SELECT points, diff
                FROM DiffPointsMap
                WHERE year = ? AND category = ?
                ORDER BY diff;
                """;

        List<Map<String, Object>> result = jdbcTemplate.queryForList(sql, year, category);
        Map<Diff, Points> map = new LinkedHashMap<>();
        for (Map<String, Object> entry : result) {
            Diff diff = new Diff((int) entry.get("diff"));
            Points points = new Points((int) entry.get("points"));
            map.put(diff, points);
        }
        return map;

    }

    /**
     * Gets max diff in mapping from diff to points in the given season and category.
     *
     * @param year     of season
     * @param category name
     * @return max diff
     */
    public Diff getMaxDiffInPointsMap(Year year, Category category) {
        final String getMaxDiff = "SELECT MAX(diff) FROM DiffPointsMap WHERE year = ? AND category = ?;";
        return new Diff(jdbcTemplate.queryForObject(getMaxDiff, Integer.class, year, category));
    }

    /**
     * Adds a new mapping from the given diff to 0 points in the given season and category.
     *
     * @param category name
     * @param diff     to add mapping for
     * @param year     of season
     */
    public void addDiffToPointsMap(Category category, Diff diff, Year year) {
        final String addDiff = "INSERT INTO DiffPointsMap (category, diff, points, year) VALUES (?, ?, ?, ?);";
        jdbcTemplate.update(addDiff, category, diff, 0, year);
    }

    /**
     * Removes the mapping from the given diff in the given season and category.
     *
     * @param category name
     * @param diff     to remove mapping for
     * @param year     of season
     */
    public void removeDiffToPointsMap(Category category, Diff diff, Year year) {
        final String deleteRowWithDiff = "DELETE FROM DiffPointsMap WHERE year = ? AND category = ? AND diff = ?;";
        jdbcTemplate.update(deleteRowWithDiff, year, category, diff);
    }

    /**
     * Sets a new mapping from the given diff to the given points in the given season and category.
     *
     * @param category name
     * @param diff     to set new mapping for
     * @param year     of season
     * @param points   for getting the diff
     */
    public void setNewDiffToPointsInPointsMap(Category category, Diff diff, Year year, Points points) {
        final String setNewPoints = """
                UPDATE DiffPointsMap
                SET points = ?
                WHERE diff = ? AND year = ? AND category = ?;
                """;
        jdbcTemplate.update(setNewPoints, points, diff, year, category);
    }

    /**
     * Checks if there is a diff set for the given season and category.
     *
     * @param category name
     * @param diff     to check
     * @param year     of season
     */
    public boolean isValidDiffInPointsMap(Category category, Diff diff, Year year) {
        final String validateDiff = "SELECT COUNT(*) FROM DiffPointsMap WHERE year = ? AND category = ? AND diff = ?;";
        return jdbcTemplate.queryForObject(validateDiff, Integer.class, year, category, diff) > 0;
    }

    /**
     * Gets a list of all all users sorted by username_upper.
     *
     * @return every user
     */
    public List<User> getAllUsers() {
        final String getAllUsersSql = """
                SELECT id, username, google_id
                FROM User
                ORDER BY username_upper;
                """;
        return jdbcTemplate.queryForList(getAllUsersSql).stream()
                .map(row ->
                        new User(
                                (String) row.get("google_id"),
                                UUID.fromString((String) row.get("id")),
                                (String) row.get("username"))
                ).toList();
    }

    /**
     * Gets a list of every person that has guessed in a season. To qualify they have to have guessed
     * on flags, drivers and constructors.
     * Ordered ascendingly by username
     *
     * @param year of season
     * @return id of guessers
     */
    public List<User> getSeasonGuessers(Year year) {
        final String getGussers = """
                SELECT DISTINCT u.id as id
                FROM User u
                JOIN FlagGuess fg ON fg.guesser = u.id
                JOIN DriverGuess dg ON dg.guesser = u.id
                JOIN ConstructorGuess cg ON cg.guesser = u.id
                WHERE fg.year = ? AND dg.year = ? AND cg.year = ?
                ORDER BY u.username;
                """;

        return jdbcTemplate.queryForList(getGussers, UUID.class, year, year, year).stream()
                .map(this::getUserFromId)
                .toList();
    }

    /**
     * Gets every race id from a year where there has been a a race.
     *
     * @param year of season
     * @return id of races
     */
    public List<RaceId> getRaceIdsFinished(Year year) {
        final String getRaceIds = """
                SELECT DISTINCT ro.id
                FROM RaceOrder ro
                JOIN RaceResult rr ON ro.id = rr.race_number
                WHERE ro.year = ?
                ORDER BY ro.position;
                """;
        return jdbcTemplate.queryForList(getRaceIds, Integer.class, year).stream()
                .map(RaceId::new)
                .toList();
    }

    /**
     * Gets the id, year and position of every race that does not have a
     * race result.
     * Ordered by year and then position, both ascendingly.
     *
     * @return list of rows with id, year and position
     */
    public List<CutoffRace> getActiveRaces() {
        final String sql = """
                SELECT id, year, position
                FROM RaceOrder
                WHERE id NOT IN (SELECT race_number FROM RaceResult)
                AND year NOT IN (SELECT year FROM YearFinished)
                ORDER BY year, position;
                """;

        return jdbcTemplate.queryForList(sql).stream()
                .map(row -> new CutoffRace(
                        (int) row.get("position"),
                        new RaceId((int) row.get("id")),
                        new Year((int) row.get("year"))
                ))
                .toList();
    }

    /**
     * Gets the id of the latest starting grid of a season.
     *
     * @param year of season
     * @return race id
     */
    public RaceId getLatestStartingGridRaceId(Year year) {
        final String getStartingGridId = """
                SELECT DISTINCT ro.id
                FROM StartingGrid sg
                JOIN RaceOrder ro on ro.id = sg.race_number
                WHERE ro.year = ?
                ORDER BY ro.position DESC
                LIMIT 1;
                """;
        return new RaceId(jdbcTemplate.queryForObject(getStartingGridId, Integer.class, year));
    }

    /**
     * Gets the id of the latest race result of a season.
     *
     * @param year of season
     * @return race id
     */
    public RaceId getLatestRaceResultId(Year year) {
        final String getRaceResultId = """
                SELECT ro.id
                FROM RaceResult rr
                JOIN RaceOrder ro on ro.id = rr.race_number
                WHERE ro.year = ?
                ORDER BY ro.position DESC
                LIMIT 1;
                """;
        return new RaceId(jdbcTemplate.queryForObject(getRaceResultId, Integer.class, year));
    }

    public RaceId getUpcomingRaceId(Year year) {
        final String sql = """
                SELECT id
                FROM RaceOrder
                WHERE id NOT IN (SELECT DISTINCT race_number FROM RaceResult)
                AND year = ?
                ORDER BY position
                LIMIT 1;
                """;
        return new RaceId(jdbcTemplate.queryForObject(sql, Integer.class, year));
    }

    /**
     * Gets the id of the latest race result of a season.
     *
     * @param year of season
     * @return race id
     */
    public RaceId getLatestStandingsId(Year year) {
        final String getRaceResultId = """
                SELECT ro.id
                FROM RaceOrder ro
                JOIN DriverStandings ds on ds.race_number = ro.id
                JOIN ConstructorStandings cs on cs.race_number = ro.id
                WHERE ro.year = ?
                ORDER BY ro.position DESC
                LIMIT 1;
                """;
        return new RaceId(jdbcTemplate.queryForObject(getRaceResultId, Integer.class, year));
    }

    /**
     * Checks if starting grid for race already exists.
     *
     * @param raceId to check
     * @return true if exists
     */
    public boolean isStartingGridAdded(RaceId raceId) {
        final String existCheck = "SELECT COUNT(*) FROM StartingGrid WHERE race_number = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, raceId) > 0;
    }

    /**
     * Checks if race result for race already exists.
     *
     * @param raceId to check
     * @return true if exists
     */
    public boolean isRaceResultAdded(RaceId raceId) {
        final String existCheck = "SELECT COUNT(*) FROM RaceResult WHERE race_number = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, raceId) > 0;
    }

    /**
     * Checks if race already exists.
     *
     * @param raceId to check
     * @return true if exists
     */
    public boolean isRaceAdded(int raceId) {
        final String existCheck = "SELECT COUNT(*) FROM Race WHERE id = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, raceId) > 0;
    }

    /**
     * Adds name of driver to the Driver table in database.
     *
     * @param driver name
     */
    public void addDriver(String driver) {
        final String insertDriver = "INSERT OR IGNORE INTO Driver (name) VALUES (?);";
        jdbcTemplate.update(insertDriver, driver);
    }

    /**
     * Appends the driver to DriverYear table in the given year.
     *
     * @param driver name
     * @param year   of season
     */
    public void addDriverYear(String driver, Year year) {
        addDriver(driver);
        int position = getMaxPosDriverYear(year) + 1;
        addDriverYear(new Driver(driver), year, position);
    }

    /**
     * Gets the max position of drivers in the DriverYear table.
     *
     * @param year of season
     * @return max position. 0 if empty.
     */
    public int getMaxPosDriverYear(Year year) {
        final String getMaxPos = "SELECT COALESCE(MAX(position), 0) FROM DriverYear WHERE year = ?;";
        return jdbcTemplate.queryForObject(getMaxPos, Integer.class, year);
    }

    /**
     * Adds driver to the DriverYear table in the given year and position.
     *
     * @param driver   name
     * @param year     of season
     * @param position of driver
     */
    public void addDriverYear(Driver driver, Year year, int position) {
        final String addDriverYear = "INSERT INTO DriverYear (driver, year, position) VALUES (?, ?, ?);";
        jdbcTemplate.update(addDriverYear, driver, year, position);
    }

    /**
     * Removes driver from DriverYear table.
     *
     * @param driver to delete
     * @param year   of season
     */
    public void deleteDriverYear(Driver driver, Year year) {
        final String deleteDriver = "DELETE FROM DriverYear WHERE year = ? AND driver = ?;";
        jdbcTemplate.update(deleteDriver, year, driver);
    }

    /**
     * Removes all drivers from DriverYear table in the given year.
     *
     * @param year of season
     */
    public void deleteAllDriverYear(Year year) {
        final String deleteAllDrivers = "DELETE FROM DriverYear WHERE year = ?;";
        jdbcTemplate.update(deleteAllDrivers, year);
    }

    /**
     * Adds name of constructor to the Constructor table in database.
     *
     * @param constructor name
     */
    public void addConstructor(String constructor) {
        final String insertConstructor = "INSERT OR IGNORE INTO Constructor (name) VALUES (?);";
        jdbcTemplate.update(insertConstructor, constructor);
    }

    /**
     * Appends the constructor to ConstructorYear table in the given year.
     *
     * @param constructor name
     * @param year        of season
     */
    public void addConstructorYear(String constructor, Year year) {
        addConstructor(constructor);
        int position = getMaxPosConstructorYear(year) + 1;
        addConstructorYear(new Constructor(constructor), year, position);
    }

    /**
     * Gets the max position of constructors in the ConstructorYear table.
     *
     * @param year of season
     * @return max position. 0 if empty.
     */
    public int getMaxPosConstructorYear(Year year) {
        final String getMaxPos = "SELECT COALESCE(MAX(position), 0) FROM ConstructorYear WHERE year = ?;";
        return jdbcTemplate.queryForObject(getMaxPos, Integer.class, year);
    }

    /**
     * Adds constructor to the ConstructorYear table in the given year and position.
     *
     * @param constructor name
     * @param year        of season
     * @param position    of constructor
     */
    public void addConstructorYear(Constructor constructor, Year year, int position) {
        final String addConstructorYear = "INSERT INTO ConstructorYear (constructor, year, position) VALUES (?, ?, ?);";
        jdbcTemplate.update(addConstructorYear, constructor, year, position);
    }

    /**
     * Removes constructor from ConstructorYear table.
     *
     * @param constructor to delete
     * @param year        of season
     */
    public void deleteConstructorYear(Constructor constructor, Year year) {
        final String deleteConstructor = "DELETE FROM ConstructorYear WHERE year = ? AND constructor = ?;";
        jdbcTemplate.update(deleteConstructor, year, constructor);
    }

    /**
     * Removes all constructor from ConstructorYear table in the given year.
     *
     * @param year of season
     */
    public void deleteAllConstructorYear(Year year) {
        final String deleteAllConstructors = "DELETE FROM ConstructorYear WHERE year = ?;";
        jdbcTemplate.update(deleteAllConstructors, year);
    }

    /**
     * Insert driving to starting grid in given race and position.
     *
     * @param raceId   to add data to
     * @param position of driver
     * @param driver   to add
     */
    public void insertDriverStartingGrid(RaceId raceId, int position, Driver driver) {
        final String insertStartingGrid = "INSERT OR REPLACE INTO StartingGrid (race_number, position, driver) VALUES (?, ?, ?);";
        jdbcTemplate.update(insertStartingGrid, raceId, position, driver);
    }

    /**
     * Inserts or replaces race result of driver into RaceResult table.
     *
     * @param raceId            of race
     * @param position          of driver
     * @param driver            name
     * @param points            the driver got
     * @param finishingPosition the position that driver finished race in
     */
    public void insertDriverRaceResult(RaceId raceId, String position, Driver driver, Points points, int finishingPosition) {
        final String insertRaceResult = """
                INSERT OR REPLACE INTO RaceResult
                (race_number, position, driver, points, finishing_position)
                VALUES (?, ?, ?, ?, ?);
                """;
        jdbcTemplate.update(insertRaceResult, raceId, position, driver, points, finishingPosition);
    }

    /**
     * Inserts or replaces position in standings of driver into DriverStandings table.
     *
     * @param raceId   of race
     * @param driver   name
     * @param position of driver
     * @param points   of driver
     */
    public void insertDriverIntoStandings(RaceId raceId, Driver driver, int position, Points points) {
        final String insertDriverStandings = """
                INSERT OR REPLACE INTO DriverStandings
                (race_number, driver, position, points)
                VALUES (?, ?, ?, ?);
                """;
        jdbcTemplate.update(insertDriverStandings, raceId, driver, position, points);
    }

    /**
     * Inserts or replaces position in standings of constructor into ConstructorStandings table.
     *
     * @param raceId      of race
     * @param constructor name
     * @param position    of constructor
     * @param points      of constructor
     */
    public void insertConstructorIntoStandings(RaceId raceId, Constructor constructor, int position, Points points) {
        final String insertConstructorStandings = """
                INSERT OR REPLACE INTO ConstructorStandings
                (race_number, constructor, position, points)
                VALUES (?, ?, ?, ?);
                """;
        jdbcTemplate.update(insertConstructorStandings, raceId, constructor, position, points);
    }

    /**
     * Inserts race id and name into Race table.
     *
     * @param raceId   of race
     * @param raceName of race
     */
    public void insertRace(int raceId, String raceName) {
        final String insertRaceName = "INSERT OR IGNORE INTO Race (id, name) VALUES (?, ?);";
        jdbcTemplate.update(insertRaceName, raceId, raceName);
    }

    /**
     * Gets the max position of a race in RaceOrder of a given year.
     * Is equivalent to number of races in the season.
     *
     * @param year of season
     * @return max position
     */
    public int getMaxRaceOrderPosition(Year year) {
        final String sql = "SELECT MAX(position) FROM RaceOrder WHERE year = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, year);
    }

    /**
     * Inserts race into RaceOrder.
     *
     * @param raceId   of race
     * @param year     of season
     * @param position of race
     */
    public void insertRaceOrder(RaceId raceId, int year, int position) {
        final String insertRaceOrder = "INSERT OR IGNORE INTO RaceOrder (id, year, position) VALUES (?, ?, ?);";
        jdbcTemplate.update(insertRaceOrder, raceId, year, position);
    }

    /**
     * Deletes race from Race table.
     *
     * @param raceId to delete
     */
    public void deleteRace(RaceId raceId) {
        final String deleteRace = "DELETE FROM Race WHERE id = ?;";
        jdbcTemplate.update(deleteRace, raceId);
    }

    /**
     * Checks if a season is a valid season. To be valid, it needs to have atleast one
     * race in the RaceOrder table.
     *
     * @param year of season
     * @return true if season is valid
     */
    public boolean isValidSeason(int year) {
        final String validateSeason = "SELECT COUNT(*) FROM Year WHERE year = ?;";
        return jdbcTemplate.queryForObject(validateSeason, Integer.class, year) > 0;
    }

    /**
     * Checks if a race is a valid race within a season. To be valid, it needs to have a
     * table row in RaceOrder where both year and id are equal to input values.
     *
     * @param raceId of race
     * @param year   of season
     * @return true if race is valid
     */
    public boolean isRaceInSeason(RaceId raceId, Year year) {
        final String validateRaceId = "SELECT COUNT(*) FROM RaceOrder WHERE year = ? AND id = ?;";
        return jdbcTemplate.queryForObject(validateRaceId, Integer.class, year, raceId) > 0;
    }

    /**
     * Gets a list of all valid years. I.E. years that are in RaceOrder table.
     * Ordered descendingly.
     *
     * @return valid years
     */
    public List<Year> getAllValidYears() {
        final String sql = "SELECT DISTINCT year FROM Year ORDER BY year DESC;";
        return jdbcTemplate.queryForList(sql, Integer.class).stream()
                .map(Year::new)
                .toList();
    }

    /**
     * Gets a list of race ids from a season.
     * Ordered by their position in RaceOrder.
     *
     * @param year of season
     * @return id of races
     */
    public List<RaceId> getRacesFromSeason(Year year) {
        final String getRaceIds = "SELECT id FROM RaceOrder WHERE year = ? ORDER BY position;";
        return jdbcTemplate.queryForList(getRaceIds, Integer.class, year).stream()
                .map(RaceId::new)
                .toList();
    }

    /**
     * Removes all races from RaceOrder in the given season.
     *
     * @param year of season
     */
    public void removeRaceOrderFromSeason(Year year) {
        final String removeOldOrderSql = "DELETE FROM RaceOrder WHERE year = ?;";
        jdbcTemplate.update(removeOldOrderSql, year);
    }

    /**
     * Gets the races from a season with their cutoff.
     * Ordered ascendingly by race position.
     *
     * @param year of season
     * @return races with cutoff
     */
    public List<CutoffRace> getCutoffRaces(Year year) {
        List<CutoffRace> races = new ArrayList<>();
        final String getCutoffRaces = """
                SELECT r.id as id, r.name as name, rc.cutoff as cutoff, ro.year as year, ro.position as position
                FROM RaceCutoff rc
                JOIN RaceOrder ro ON ro.id = rc.race_number
                JOIN Race r ON ro.id = r.id
                WHERE ro.year = ?
                ORDER BY ro.position;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getCutoffRaces, year);
        for (Map<String, Object> row : sqlRes) {
            LocalDateTime cutoff = TimeUtil.instantToLocalTime(Instant.parse((String) row.get("cutoff")));
            String name = (String) row.get("name");
            int id = (int) row.get("id");
            int position = (int) row.get("position");
            RaceId raceId = new RaceId(id);
            CutoffRace race = new CutoffRace(position, name, raceId, cutoff, year);
            races.add(race);
        }
        return races;
    }

    public List<Race> getRacesYear(Year year) {
        final String getCutoffRaces = """
                SELECT r.id as id, r.name as name, ro.year as year, ro.position as position
                FROM RaceOrder ro
                JOIN Race r ON ro.id = r.id
                WHERE ro.year = ?
                ORDER BY ro.position;
                """;
        return jdbcTemplate.queryForList(getCutoffRaces, year).stream()
                .map(row -> new Race(
                        (int) row.get("position"),
                        (String) row.get("name"),
                        new RaceId((int) row.get("id")),
                        year))
                .toList();
    }

    public List<Race> getRacesYearFinished(Year year) {
        final String getCutoffRaces = """
                SELECT DISTINCT r.id as id, r.name as name, ro.year as year, ro.position as position
                FROM RaceOrder ro
                JOIN Race r ON ro.id = r.id
                JOIN RaceResult rr ON rr.race_number = r.id
                WHERE ro.year = ?
                ORDER BY ro.position;
                """;
        return jdbcTemplate.queryForList(getCutoffRaces, year).stream()
                .map(row -> new Race(
                        (int) row.get("position"),
                        (String) row.get("name"),
                        new RaceId((int) row.get("id")),
                        year))
                .toList();
    }

    public Race getRaceFromId(RaceId raceId) {
        final String sql = """
                SELECT r.id as id, r.name as name, ro.year as year, ro.position as position
                FROM RaceOrder ro
                JOIN Race r ON ro.id = r.id
                WHERE ro.id = ?
                ORDER BY ro.position;
                """;
        Map<String, Object> sqlRes = jdbcTemplate.queryForMap(sql, raceId);
        return new Race(
                (int) sqlRes.get("position"),
                (String) sqlRes.get("name"),
                new RaceId((int) sqlRes.get("id")),
                new Year((int) sqlRes.get("year"))
        );
    }

    /**
     * Gets the cutoff of the year in LocalDataTime.
     *
     * @param year of season
     * @return cutoff time in local time
     */
    public LocalDateTime getCutoffYearLocalTime(Year year) {
        return TimeUtil.instantToLocalTime(getCutoffYear(year));
    }

    /**
     * Sets the cutoff of the given race to the given time.
     *
     * @param cutoffTime for guessing
     * @param raceId     of race
     */
    public void setCutoffRace(Instant cutoffTime, RaceId raceId) {
        final String setCutoffTime = "INSERT OR REPLACE INTO RaceCutoff (race_number, cutoff) VALUES (?, ?);";
        jdbcTemplate.update(setCutoffTime, raceId, cutoffTime.toString());
    }

    /**
     * Sets the cutoff of the given season to the given time.
     *
     * @param cutoffTime for guessing
     * @param year       of season
     */
    public void setCutoffYear(Instant cutoffTime, Year year) {
        final String setCutoffTime = "INSERT OR REPLACE INTO YearCutoff (year, cutoff) VALUES (?, ?);";
        jdbcTemplate.update(setCutoffTime, year, cutoffTime);
    }

    /**
     * Gets a list of registered flags for a given race.
     *
     * @param raceId of race
     * @return registered flags
     */
    public List<RegisteredFlag> getRegisteredFlags(RaceId raceId) {
        List<RegisteredFlag> registeredFlags = new ArrayList<>();
        final String getRegisteredFlags = """
                SELECT flag, round, id, session_type
                FROM FlagStats
                WHERE race_number = ?
                ORDER BY session_type, round;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getRegisteredFlags, raceId);
        for (Map<String, Object> row : sqlRes) {
            Flag type = new Flag((String) row.get("flag"));
            int round = (int) row.get("round");
            int id = (int) row.get("id");
            SessionType sessionType = new SessionType((String) row.get("session_type"));
            registeredFlags.add(new RegisteredFlag(type, round, id, sessionType));
        }
        return registeredFlags;
    }

    /**
     * Inserts an instance of a recorded flag to the database.
     * IDs are assigned automatically.
     *
     * @param flag   type of flag
     * @param round  the round flag happened in
     * @param raceId of race
     */
    public void insertFlagStats(Flag flag, int round, RaceId raceId, SessionType sessionType) {
        final String sql = "INSERT INTO FlagStats (flag, race_number, round, session_type) VALUES (?, ?, ?, ?);";
        jdbcTemplate.update(sql, flag, raceId, round, sessionType);
    }

    /**
     * Deletes a recorded flag by its id.
     *
     * @param flagId of stat
     */
    public void deleteFlagStatsById(int flagId) {
        final String sql = "DELETE FROM FlagStats WHERE id = ?;";
        jdbcTemplate.update(sql, flagId);
    }

    /**
     * Gets the name of a race.
     *
     * @param raceId of race
     * @return name of race
     */
    public String getRaceName(RaceId raceId) {
        final String getRaceNameSql = "SELECT name FROM Race WHERE id = ?;";
        return jdbcTemplate.queryForObject(getRaceNameSql, String.class, raceId);
    }

    /**
     * Gets a list of all the types of flags.
     *
     * @return name of flag types
     */
    public List<Flag> getFlags() {
        final String sql = "SELECT name FROM Flag;";
        return jdbcTemplate.queryForList(sql, String.class).stream()
                .map(Flag::new)
                .toList();
    }

    /**
     * Gets a list of the starting grid of a race as a list of PositionedCompetitor.
     * Points are left blank.
     *
     * @param raceId of race
     * @return starting grid
     */
    public List<PositionedCompetitor> getStartingGrid(RaceId raceId) {
        final String getStartingGrid = """
                SELECT position, driver
                FROM StartingGrid
                WHERE race_number = ?
                ORDER BY position;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getStartingGrid, raceId);
        List<PositionedCompetitor> startingGrid = new ArrayList<>();
        for (Map<String, Object> row : sqlRes) {
            String position = String.valueOf((int) row.get("position"));
            String driver = (String) row.get("driver");
            startingGrid.add(new PositionedCompetitor(position, driver, ""));
        }
        return startingGrid;
    }

    /**
     * Gets a list of the race result of a race as a list of PositionedCompetitor.
     *
     * @param raceId of race
     * @return race result
     */
    public List<PositionedCompetitor> getRaceResult(RaceId raceId) {
        final String getRaceResult = """
                SELECT position, driver, points
                FROM RaceResult
                WHERE race_number = ?
                ORDER BY finishing_position;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getRaceResult, raceId);
        List<PositionedCompetitor> raceResult = new ArrayList<>();
        for (Map<String, Object> row : sqlRes) {
            String position = (String) row.get("position");
            String driver = (String) row.get("driver");
            String points = (String) row.get("points");
            raceResult.add(new PositionedCompetitor(position, driver, points));
        }
        return raceResult;
    }

    /**
     * Gets a list of the driver standings from a race as a list of PositionedCompetitor.
     *
     * @param raceId of race
     * @return driver standings
     */
    public List<PositionedCompetitor> getDriverStandings(RaceId raceId) {
        final String getDriverStandings = """
                SELECT position, driver, points
                FROM DriverStandings
                WHERE race_number = ?
                ORDER BY position;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getDriverStandings, raceId);
        List<PositionedCompetitor> standings = new ArrayList<>();
        for (Map<String, Object> row : sqlRes) {
            String position = String.valueOf((int) row.get("position"));
            String driver = (String) row.get("driver");
            String points = (String) row.get("points");
            standings.add(new PositionedCompetitor(position, driver, points));
        }
        return standings;
    }

    /**
     * Gets a list of the constructor standings from a race as a list of PositionedCompetitor.
     *
     * @param raceId of race
     * @return constructor standings
     */
    public List<PositionedCompetitor> getConstructorStandings(RaceId raceId) {
        final String getConstructorStandings = """
                SELECT position, constructor, points
                FROM ConstructorStandings
                WHERE race_number = ?
                ORDER BY position;
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(getConstructorStandings, raceId);
        List<PositionedCompetitor> standings = new ArrayList<>();
        for (Map<String, Object> row : sqlRes) {
            String position = String.valueOf((int) row.get("position"));
            String constructor = (String) row.get("constructor");
            String points = (String) row.get("points");
            standings.add(new PositionedCompetitor(position, constructor, points));
        }
        return standings;
    }

    /**
     * Checks if a driver is in DriverYear in the given season.
     *
     * @param driver the name of the driver
     * @param year   of season
     * @return true if driver is valid
     */
    public boolean isValidDriverYear(Driver driver, Year year) {
        final String existCheck = "SELECT COUNT(*) FROM DriverYear WHERE year = ? AND driver = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, year, driver) > 0;
    }

    public boolean isValidDriver(Driver driver) {
        final String existCheck = "SELECT COUNT(*) FROM Driver WHERE name = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, driver) > 0;
    }

    /**
     * Checks if a constructor is in ConstructorYear in the given season.
     *
     * @param constructor the name of the constructor
     * @param year        of season
     * @return true if constructor is valid
     */
    public boolean isValidConstructorYear(Constructor constructor, Year year) {
        final String existCheck = "SELECT COUNT(*) FROM ConstructorYear WHERE year = ? AND constructor = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, year, constructor) > 0;
    }

    public boolean isValidConstructor(Constructor constructor) {
        final String existCheck = "SELECT COUNT(*) FROM Constructor WHERE name = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, constructor) > 0;
    }

    public boolean isValidFlag(String value) {
        final String existCheck = "SELECT COUNT(*) FROM Flag WHERE name = ?;";
        return jdbcTemplate.queryForObject(existCheck, Integer.class, value) > 0;
    }

    private void addToMailingList(UUID userId, String email) {
        final String sql = "INSERT OR REPLACE INTO MailingList (user_id, email) VALUES (?, ?);";
        jdbcTemplate.update(sql, userId, email);
        removeVerificationCode(userId);
    }

    public void clearUserFromMailing(UUID userId) {
        clearMailPreferences(userId);
        clearNotified(userId);
        deleteUserFromMailingList(userId);
    }

    private void deleteUserFromMailingList(UUID userId) {
        final String sql = "DELETE FROM MailingList WHERE user_id = ?;";
        jdbcTemplate.update(sql, userId);
    }


    public boolean userHasEmail(UUID userId) {
        final String sql = "SELECT COUNT(*) FROM MailingList WHERE user_id = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, userId) > 0;
    }

    public String getEmail(UUID userId) {
        try {
            final String sql = "SELECT email FROM MailingList WHERE user_id = ?;";
            return jdbcTemplate.queryForObject(sql, String.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<UserMail> getMailingList(RaceId raceId) {
        final String sql = """
                SELECT u.google_id as google_id, u.id as id, u.username as username, ml.email as email
                FROM User u
                JOIN MailingList ml ON ml.user_id = u.id
                WHERE u.id NOT IN
                      (SELECT guesser FROM DriverPlaceGuess WHERE race_number = ? GROUP BY guesser HAVING COUNT(*) == 2);
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(sql, raceId);
        return sqlRes.stream()
                .map(row ->
                        new UserMail(
                                new User(
                                        (String) row.get("google_id"),
                                        UUID.fromString((String) row.get("id")),
                                        (String) row.get("username"))
                                , (String) row.get("email"))
                )
                .toList();
    }

    public void setNotified(RaceId raceId, UUID userId) {
        final String sql = "INSERT OR IGNORE INTO Notified (user_id, race_number) VALUES (?, ?);";
        jdbcTemplate.update(sql, userId, raceId);
    }

    public int getNotifiedCount(RaceId raceId, UUID userId) {
        final String sql = "SELECT COUNT(*) FROM Notified WHERE user_id = ? AND race_number = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, userId, raceId);
    }

    private void clearNotified(UUID userId) {
        final String sql = "DELETE FROM Notified WHERE user_id = ?;";
        jdbcTemplate.update(sql, userId);
    }

    public void addVerificationCode(UserMail userMail, int verificationCode) {
        final String sql = """
                INSERT OR REPLACE INTO VerificationCode
                (user_id, verification_code, email, cutoff)
                VALUES (?, ?, ?, ?);
                """;
        jdbcTemplate.update(sql, userMail.user().id(), verificationCode, userMail.email(), Instant.now().plus(Duration.ofMinutes(10)).toString());
    }

    public void removeVerificationCode(UUID userId) {
        final String sql = "DELETE FROM VerificationCode WHERE user_id = ?";
        jdbcTemplate.update(sql, userId);
    }

    public void removeExpiredVerificationCodes() {
        final String sql = "SELECT cutoff, user_id FROM VerificationCode;";
        List<UUID> expired = getExpiredCodes(sql);
        for (UUID userId : expired) {
            removeVerificationCode(userId);
        }
    }

    public boolean hasVerificationCode(UUID userId) {
        final String sql = "SELECT COUNT(*) FROM VerificationCode WHERE user_id = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, userId) > 0;
    }

    public boolean isValidVerificationCode(UUID userId, int verificationCode) {
        final String sql = "SELECT COUNT(*) FROM VerificationCode WHERE user_id = ? AND verification_code = ?;";
        boolean isValidCode = jdbcTemplate.queryForObject(sql, Integer.class, userId, verificationCode) > 0;
        if (!isValidCode) {
            return false;
        }
        final String getCutoffSql = "SELECT cutoff FROM VerificationCode WHERE user_id = ?;";
        Instant cutoff = Instant.parse(jdbcTemplate.queryForObject(getCutoffSql, String.class, userId));
        boolean isValidCutoff = cutoff.compareTo(Instant.now()) > 0;
        if (!isValidCutoff) {
            return false;
        }
        final String emailSql = "SELECT email FROM VerificationCode WHERE user_id = ?;";
        String email = jdbcTemplate.queryForObject(emailSql, String.class, userId);
        addToMailingList(userId, email);
        return true;
    }

    public boolean isValidReferralCode(long referralCode) {
        final String sql = "SELECT COUNT(*) FROM ReferralCode WHERE referral_code = ?;";
        boolean isValidCode = jdbcTemplate.queryForObject(sql, Integer.class, referralCode) > 0;
        if (!isValidCode) {
            return false;
        }
        final String getCutoffSql = "SELECT cutoff FROM ReferralCode WHERE referral_code = ?;";
        Instant cutoff = Instant.parse(jdbcTemplate.queryForObject(getCutoffSql, String.class, referralCode));
        return cutoff.compareTo(Instant.now()) > 0;
    }

    public void removeExpiredReferralCodes() {
        final String sql = "SELECT cutoff, user_id FROM ReferralCode;";
        List<UUID> expired = getExpiredCodes(sql);
        for (UUID userId : expired) {
            removeReferralCode(userId);
        }
    }

    private List<UUID> getExpiredCodes(final String sql) {
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(sql);
        return sqlRes.stream()
                .filter(row -> Instant.parse((String) row.get("cutoff")).compareTo(Instant.now()) < 0)
                .map(row -> UUID.fromString((String) row.get("user_id")))
                .toList();
    }

    public void removeReferralCode(UUID userId) {
        final String sql = "DELETE FROM ReferralCode WHERE user_id = ?;";
        jdbcTemplate.update(sql, userId);
    }

    public long addReferralCode(UUID userId) {
        final String sql = """
                INSERT OR REPLACE INTO ReferralCode
                (user_id, referral_code, cutoff)
                VALUES (?, ?, ?);
                """;
        long referralCode = CodeGenerator.getReferralCode();
        Instant cutoff = Instant.now().plus(Duration.ofHours(1));
        jdbcTemplate.update(sql, userId, referralCode, cutoff);
        return referralCode;
    }

    public Long getReferralCode(UUID userId) {
        final String sql = "SELECT referral_code FROM ReferralCode WHERE user_id = ?;";
        try {
            return jdbcTemplate.queryForObject(sql, Long.class, userId);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<CompetitorGuessYear<Driver>> userGuessDataDriver(UUID userId) {
        final String sql = """
                SELECT position, driver, year
                FROM DriverGuess
                WHERE guesser = ?
                ORDER BY year DESC, position;
                """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row ->
                        new CompetitorGuessYear<>(
                                (int) row.get("position"),
                                new Driver((String) row.get("driver")),
                                new Year((int) row.get("year"))
                        )).toList();
    }

    public List<CompetitorGuessYear<Constructor>> userGuessDataConstructor(UUID userId) {
        final String sql = """
                SELECT position, constructor, year
                FROM ConstructorGuess
                WHERE guesser = ?
                ORDER BY year DESC, position;
                """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row ->
                        new CompetitorGuessYear<>(
                                (int) row.get("position"),
                                new Constructor((String) row.get("constructor")),
                                new Year((int) row.get("year"))
                        )).toList();
    }

    public List<FlagGuessYear> userGuessDataFlag(UUID userId) {
        final String sql = """
                SELECT flag, amount, year
                FROM FlagGuess
                WHERE guesser = ?
                ORDER BY year DESC, flag;
                """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row -> new FlagGuessYear(
                        new Flag((String) row.get("flag")),
                        (int) row.get("amount"),
                        new Year((int) row.get("year"))
                )).toList();

    }

    public List<PlaceGuess> userGuessDataDriverPlace(UUID userId) {
        final String sql = """
                SELECT dpg.category AS category, dpg.driver AS driver, r.name AS race_name, ro.year AS year
                FROM DriverPlaceGuess dpg
                JOIN Race r ON dpg.race_number = r.id
                JOIN RaceOrder ro ON dpg.race_number = ro.id
                WHERE dpg.guesser = ?
                ORDER BY ro.year DESC, ro.position, dpg.category;
                """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row -> new PlaceGuess(
                        new Category((String) row.get("category")),
                        new Driver((String) row.get("driver")),
                        (String) row.get("race_name"),
                        new Year((int) row.get("year"))
                )).toList();
    }

    public List<UserNotifiedCount> userDataNotified(UUID userId) {
        final String sql = """
                SELECT r.name AS name, count(*) as notified_count, ro.year AS year
                FROM Notified n
                JOIN Race r ON n.race_number = r.id
                JOIN RaceOrder ro ON n.race_number = ro.id
                WHERE n.user_id = ?
                GROUP BY n.race_number, ro.position, ro.year
                ORDER BY ro.year DESC, ro.position;
                """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row -> new UserNotifiedCount(
                        (String) row.get("name"),
                        (int) row.get("notified_count"),
                        new Year((int) row.get("year"))
                )).toList();
    }

    public boolean isValidMailOption(int option) {
        final String sql = "SELECT COUNT(*) FROM MailOption WHERE option = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, option) > 0;
    }

    public void addMailOption(UUID userId, MailOption option) {
        final String sql = "INSERT OR IGNORE INTO MailPreference (user_id, option) VALUES (?, ?);";
        jdbcTemplate.update(sql, userId, option);
    }

    public void removeMailOption(UUID userId, MailOption option) {
        final String sql = "DELETE FROM MailPreference WHERE user_id = ? AND option = ?;";
        jdbcTemplate.update(sql, userId, option);
    }

    private void clearMailPreferences(UUID userId) {
        final String sql = "DELETE FROM MailPreference WHERE user_id = ?;";
        jdbcTemplate.update(sql, userId);
    }

    public List<MailOption> getMailingPreference(UUID userId) {
        final String sql = "SELECT option FROM MailPreference WHERE user_id = ? ORDER BY option DESC;";
        return jdbcTemplate.queryForList(sql, Integer.class, userId).stream()
                .map(MailOption::new)
                .toList();
    }

    public List<MailOption> getMailingOptions() {
        final String sql = "SELECT option FROM MailOption ORDER BY option;";
        return jdbcTemplate.queryForList(sql, Integer.class).stream()
                .map(MailOption::new)
                .toList();
    }

    public void setTeamDriver(Driver driver, Constructor team, Year year) {
        final String sql = "INSERT OR REPLACE INTO DriverTeam (driver, team, year) VALUES (?, ?, ?);";
        jdbcTemplate.update(sql, driver, team, year);
    }

    public void addColorConstructor(Constructor constructor, Year year, Color color) {
        if (color.value() == null) {
            return;
        }
        final String sql = "INSERT OR REPLACE INTO ConstructorColor (constructor, year, color) VALUES (?, ?, ?);";
        jdbcTemplate.update(sql, constructor, year, color);
    }

    public List<ColoredCompetitor<Constructor>> getConstructorsYearWithColors(Year year) {
        final String sql = """
                SELECT cy.constructor as constructor, cc.color as color
                FROM ConstructorYear cy
                LEFT JOIN ConstructorColor cc ON cc.constructor = cy.constructor
                WHERE cy.year = ?
                ORDER BY cy.position;
                """;
        return jdbcTemplate.queryForList(sql, year).stream()
                .map(row -> new ColoredCompetitor<>(
                        new Constructor((String) row.get("constructor")),
                        new Color((String) row.get("color"))))
                .toList();
    }

    public List<ValuedCompetitor<Driver, Constructor>> getDriversTeam(Year year) {
        final String sql = """
                SELECT dy.driver as driver, dt.team as team
                FROM DriverYear dy
                LEFT JOIN DriverTeam dt ON dt.driver = dy.driver
                WHERE dy.year = ?
                ORDER BY dy.position;
                """;
        return jdbcTemplate.queryForList(sql, year).stream()
                .map(row -> new ValuedCompetitor<>(
                        new Driver((String) row.get("driver")),
                        new Constructor((String) row.get("team"))))
                .toList();
    }


    public void addBingomaster(UUID userId) {
        final String sql = "INSERT OR IGNORE INTO Bingomaster (user_id) VALUES (?);";
        jdbcTemplate.update(sql, userId);
    }

    public void removeBingomaster(UUID userId) {
        final String sql = "DELETE FROM Bingomaster WHERE user_id = ?;";
        jdbcTemplate.update(sql, userId);
    }

    public List<User> getBingomasters() {
        final String getAllUsersSql = """
                SELECT u.id AS id, u.username AS username, u.google_id AS google_id
                FROM User u
                JOIN Bingomaster bm ON u.id = bm.user_id
                ORDER BY u.username_upper;
                """;
        return jdbcTemplate.queryForList(getAllUsersSql).stream()
                .map(row ->
                        new User(
                                (String) row.get("google_id"),
                                UUID.fromString((String) row.get("id")),
                                (String) row.get("username"))
                ).toList();
    }

    public boolean isBingomaster(UUID userId) {
        final String sql = "SELECT COUNT(*) FROM Bingomaster WHERE user_id = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, userId) > 0;
    }

    public List<BingoSquare> getBingoCard(Year year) {
        final String sql = """
                SELECT year, id, square_text, marked
                FROM BingoCard
                WHERE year = ?
                ORDER BY id;
                """;
        return jdbcTemplate.queryForList(sql, year).stream()
                .map(row ->
                        new BingoSquare(
                                (String) row.get("square_text"),
                                (int) row.get("marked") != 0,
                                (int) row.get("id"),
                                new Year((int) row.get("year"))
                        )
                ).toList();
    }

    public BingoSquare getBingoSquare(Year year, int id) {
        final String sql = """
                SELECT year, id, square_text, marked
                FROM BingoCard
                WHERE year = ? AND id = ?;
                """;
        Map<String, Object> row = jdbcTemplate.queryForMap(sql, year, id);
        return new BingoSquare(
                (String) row.get("square_text"),
                (int) row.get("marked") != 0,
                (int) row.get("id"),
                new Year((int) row.get("year"))
        );
    }

    public void addBingoSquare(BingoSquare bingoSquare) {
        final String sql = """
                INSERT OR REPLACE INTO BingoCard
                (year, id, square_text, marked)
                VALUES (?, ?, ?, ?);
                """;
        jdbcTemplate.update(
                sql,
                bingoSquare.year(),
                bingoSquare.id(),
                bingoSquare.text(),
                bingoSquare.marked() ? 1 : 0
        );
    }

    public void toogleMarkBingoSquare(Year year, int id) {
        BingoSquare bingoSquare = getBingoSquare(year, id);
        boolean newMark = !bingoSquare.marked();
        final String sql = """
                UPDATE BingoCard
                SET marked = ?
                WHERE year = ? AND id = ?;
                """;
        jdbcTemplate.update(sql, newMark ? 1 : 0, year, id);
    }

    public void setTextBingoSquare(Year year, int id, String text) {
        final String sql = """
                UPDATE BingoCard
                SET square_text = ?
                WHERE year = ? AND id = ?;
                """;
        jdbcTemplate.update(sql, text, year, id);
    }

    public boolean isBingoCardAdded(Year year) {
        final String sql = """
                SELECT COUNT(*)
                FROM BingoCard
                WHERE year = ?;
                """;
        return jdbcTemplate.queryForObject(sql, Integer.class, year) > 0;
    }

    public boolean isValidSessionType(String sessionType) {
        final String sql = "SELECT COUNT(*) FROM SessionType WHERE name = ?;";
        return jdbcTemplate.queryForObject(sql, Integer.class, sessionType) > 0;
    }

    public List<SessionType> getSessionTypes() {
        final String sql = "SELECT name FROM SessionType ORDER BY name;";
        return jdbcTemplate.queryForList(sql).stream()
                .map(row -> new SessionType((String) row.get("name")))
                .toList();
    }

    public String getAlternativeDriverName(String driver, Year year) {
        final String sql = """
                SELECT driver
                FROM DriverAlternativeName
                WHERE alternative_name = ? AND year = ?;
                """;
        try {
            return jdbcTemplate.queryForObject(sql, String.class, driver, year);
        } catch (EmptyResultDataAccessException e) {
            return driver;
        }
    }

    public Map<String, String> getAlternativeDriverNamesYear(Year year) {
        final String sql = """
                SELECT driver, alternative_name
                FROM DriverAlternativeName
                WHERE year = ?;
                """;
        Map<String, String> linkedMap = new LinkedHashMap<>();
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(sql, year);
        for (Map<String, Object> row : sqlRes) {
            String driverName = (String) row.get("driver");
            String alternativeName = (String) row.get("alternative_name");
            linkedMap.put(alternativeName, driverName);
        }
        return linkedMap;
    }

    public String getAlternativeDriverName(String driver, RaceId raceId) {
        Year year = getYearFromRaceId(raceId);
        return getAlternativeDriverName(driver, year);
    }

    public Year getYearFromRaceId(RaceId raceId) {
        final String sql = "SELECT year FROM RaceOrder WHERE id = ?;";
        return new Year(jdbcTemplate.queryForObject(sql, Integer.class, raceId));
    }

    public void addAlternativeDriverName(Driver driver, String alternativeName, Year year) {
        final String sql = """
                INSERT OR IGNORE INTO DriverAlternativeName
                (driver, alternative_name, year)
                VALUES (?, ?, ?);
                """;
        jdbcTemplate.update(sql, driver, alternativeName, year);
    }

    public void deleteAlternativeName(Driver driver, Year year, String alternativeName) {
        final String sql = "DELETE FROM DriverAlternativeName WHERE driver = ? AND year = ? AND alternative_name = ?;";
        jdbcTemplate.update(sql, driver, year, alternativeName);
    }

    public List<UUID> getAdmins() {
        final String sql = "SELECT user_id FROM Admin;";
        return jdbcTemplate.queryForList(sql, String.class).stream()
                .map(UUID::fromString)
                .toList();
    }

    public List<String> getUnregisteredUsers() {
        final String sql = """
                	SELECT SESSION_ID, LAST_ACCESS_TIME
                	FROM SPRING_SESSION
                	WHERE PRINCIPAL_NAME NOT IN (
                		SELECT google_id from User
                	);
                """;
        List<Map<String, Object>> sqlRes = jdbcTemplate.queryForList(sql);
        return sqlRes.stream()
                .filter(session -> {
                    long lastAccess = (long) session.get("LAST_ACCESS_TIME");
                    long now = System.currentTimeMillis();
                    long diff = now - lastAccess;
                    long hourMillis = 3600000;
                    return diff >= hourMillis;
                })
                .map(session -> (String) session.get("SESSION_ID"))
                .toList();
    }

    public void addYear(int year) {
        final String sql = "INSERT OR IGNORE INTO Year (year) values (?)";
        jdbcTemplate.update(sql, year);
    }

    public Summary getSummary(RaceId raceId, Year year, PublicUser user) {
        try {
            List<Map<String, Object>> categoriesRes;
            Map<String, Object> totalRes;
            if (raceId != null) {
                final String categoriesSql = """
                            SELECT category, placement, points
                            FROM PlacementCategory
                            WHERE race_number = ?
                            AND guesser = ?;
                        """;
                final String totalSql = """
                            SELECT placement, points
                            FROM PlacementRace
                            WHERE race_number = ?
                            AND guesser = ?;
                        """;
                categoriesRes = jdbcTemplate.queryForList(categoriesSql, raceId, user.id);
                totalRes = jdbcTemplate.queryForMap(totalSql, raceId, user.id);
            } else {
                final String categoriesSql = """
                            SELECT category, placement, points
                            FROM PlacementCategoryYearStart
                            WHERE year = ?
                            AND guesser = ?;
                        """;
                final String totalSql = """
                            SELECT placement, points
                            FROM PlacementRaceYearStart
                            WHERE year = ?
                            AND guesser = ?;
                        """;
                categoriesRes = jdbcTemplate.queryForList(categoriesSql, year, user.id);
                totalRes = jdbcTemplate.queryForMap(totalSql, year, user.id);
            }
            Map<Category, Placement<Points>> categories = new HashMap<>();
            for (Map<String, Object> row : categoriesRes) {
                Category category = new Category((String) row.get("category"));
                Position pos = new Position((int) row.get("placement"));
                Points points = new Points((int) row.get("points"));
                Placement<Points> placement = new Placement<>(pos, points);
                categories.put(category, placement);
            }
            Placement<Points> drivers = categories.get(new Category("DRIVER", this));
            Placement<Points> constructors = categories.get(new Category("CONSTRUCTOR", this));
            Placement<Points> flag = categories.get(new Category("FLAG", this));
            Placement<Points> winner = categories.get(new Category("FIRST", this));
            Placement<Points> tenth = categories.get(new Category("TENTH", this));
            Placement<Points> total =
                    new Placement<>(new Position((int) totalRes.get("placement")),
                            new Points((int) totalRes.get("points")));
            return new Summary(drivers, constructors, flag, winner, tenth, total);
        } catch (EmptyResultDataAccessException e) {
            return null;
        }
    }

    public List<Placement<Year>> getPreviousPlacements(UUID userId) {
        final String sql = """
            SELECT placement, year
            FROM PlacementYear
            WHERE guesser = ?
            ORDER BY year DESC;
        """;
        return jdbcTemplate.queryForList(sql, userId).stream()
                .map(row ->
                        new Placement<>(
                                new Position((int) row.get("placement")),
                                new Year((int) row.get("year"))
                        ))
                .toList();
    }

    public Medals getMedals(UUID userId) {
        final String sql = """
            SELECT COUNT(CASE WHEN placement = 1 THEN 1 END) AS gold, COUNT(CASE WHEN placement = 2 THEN 1 END) AS silver, COUNT(CASE WHEN placement = 3 THEN 1 END) AS bronze
            FROM PlacementYear
            WHERE guesser = ?;
        """;
        Map<String, Object> res = jdbcTemplate.queryForMap(sql, userId);
        return new Medals(
            new MedalCount((int) res.get("gold")),
            new MedalCount((int) res.get("silver")),
            new MedalCount((int) res.get("bronze"))
        );
    }

    public void addUserScore(UUID userId, Summary summary, RaceId raceId, Year year) {
        if (raceId != null) {
            addUserScoreRace(userId, summary, raceId);
        } else {
            addUserScoreYearStart(userId, summary, year);
        }
    }

    private void addUserScoreRace(UUID userId, Summary summary, RaceId raceId) {
        final String addTotalSql = """
            INSERT OR REPLACE INTO PlacementRace
            (race_number, guesser, placement, points)
            VALUES (?, ?, ?, ?);
        """;
        final String addCategorySql = """
            INSERT OR REPLACE INTO PlacementCategory
            (race_number, guesser, category, placement, points)
            VALUES (?, ?, ?, ?, ?);
        """;
        jdbcTemplate.update(addTotalSql, raceId, userId, summary.total().pos(), summary.total().value());
        jdbcTemplate.update(addCategorySql, raceId, userId, new Category("DRIVER"), summary.drivers().pos(), summary.drivers().value());
        jdbcTemplate.update(addCategorySql, raceId, userId, new Category("CONSTRUCTOR"), summary.constructors().pos(), summary.constructors().value());
        jdbcTemplate.update(addCategorySql, raceId, userId, new Category("FLAG"), summary.flag().pos(), summary.flag().value());
        jdbcTemplate.update(addCategorySql, raceId, userId, new Category("FIRST"), summary.winner().pos(), summary.winner().value());
        jdbcTemplate.update(addCategorySql, raceId, userId, new Category("TENTH"), summary.tenth().pos(), summary.tenth().value());
    }

    private void addUserScoreYearStart(UUID userId, Summary summary, Year year) {
        final String addTotalSql = """
            INSERT OR REPLACE INTO PlacementRaceYearStart
            (year, guesser, placement, points)
            VALUES (?, ?, ?, ?);
        """;
        final String addCategorySql = """
            INSERT OR REPLACE INTO PlacementCategoryYearStart
            (year, guesser, category, placement, points)
            VALUES (?, ?, ?, ?, ?);
        """;
        jdbcTemplate.update(addTotalSql, year, userId, summary.total().pos(), summary.total().value());
        jdbcTemplate.update(addCategorySql, year, userId, new Category("DRIVER"), summary.drivers().pos(), summary.drivers().value());
        jdbcTemplate.update(addCategorySql, year, userId, new Category("CONSTRUCTOR"), summary.constructors().pos(), summary.constructors().value());
        jdbcTemplate.update(addCategorySql, year, userId, new Category("FLAG"), summary.flag().pos(), summary.flag().value());
        jdbcTemplate.update(addCategorySql, year, userId, new Category("FIRST"), summary.winner().pos(), summary.winner().value());
        jdbcTemplate.update(addCategorySql, year, userId, new Category("TENTH"), summary.tenth().pos(), summary.tenth().value());
    }
}

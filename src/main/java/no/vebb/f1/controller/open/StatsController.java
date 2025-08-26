package no.vebb.f1.controller.open;

import no.vebb.f1.results.ResultService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import no.vebb.f1.database.Database;
import no.vebb.f1.util.RaceStats;
import no.vebb.f1.util.domainPrimitive.RaceId;
import no.vebb.f1.util.domainPrimitive.Year;
import no.vebb.f1.util.exception.InvalidRaceException;

@RestController
@RequestMapping("/api/public/stats")
public class StatsController {

	private final Database db;
	private final ResultService resultService;

	public StatsController(Database db, ResultService resultService) {
		this.db = db;
		this.resultService = resultService;
	}

	@GetMapping("/race/{raceId}")
	public ResponseEntity<RaceStats> raceStats(@PathVariable("raceId") int raceId) {
		try {
			RaceId validRaceId = new RaceId(raceId, db);
			Year year = db.getYearFromRaceId(validRaceId);
			RaceStats res = new RaceStats(validRaceId, year, db, resultService);
			return new ResponseEntity<>(res, HttpStatus.OK);
		} catch (InvalidRaceException e) {
			return new ResponseEntity<>(HttpStatus.NOT_FOUND);
		}
	}
}

package chess.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;

public class ChessApiSimulation extends Simulation {
    String baseUrl = System.getProperty("baseUrl", "http://localhost:8080");
    String perfProfile = System.getProperty("perfProfile", "assignment");
    int gameplayUsers = Integer.getInteger("gameplayUsers", "full".equals(perfProfile) ? 100 : 20);
    int constantUsersPerSecValue = Integer.getInteger("constantUsersPerSec", "full".equals(perfProfile) ? 10 : 5);
    int rampSeconds = Integer.getInteger("rampSeconds", "full".equals(perfProfile) ? 60 : 30);
    int holdSeconds = Integer.getInteger("holdSeconds", "full".equals(perfProfile) ? 60 : 30);
    int rampDownSeconds = Integer.getInteger("rampDownSeconds", "full".equals(perfProfile) ? 20 : 10);

    HttpProtocolBuilder httpProtocol = http
        .baseUrl(baseUrl)
        .acceptHeader("application/json")
        .contentTypeHeader("application/json");

    Iterator<Map<String, Object>> sessionFeeder =
        Stream.generate((Supplier<Map<String, Object>>) () -> 
            Map.of("sid", "gatling-" + UUID.randomUUID().toString().substring(0, 8))
        ).iterator();

    ScenarioBuilder pingScenario = scenario("Ping")
        .exec(http("GET /ping")
            .get("/ping")
            .check(status().is(200))
            .check(bodyString().is("pong"))
        );

    ScenarioBuilder gameplayScenario = scenario("Chess Gameplay")
        .feed(sessionFeeder)
        .exec(http("GET /api/state")
            .get("/api/state?sessionId=#{sid}")
            .check(status().is(200))
            .check(jsonPath("$.fen").exists())
        )
        .pause(Duration.ofMillis(100))
        .exec(http("POST /api/command (e2e4)")
            .post("/api/command?sessionId=#{sid}")
            .body(StringBody("{\"command\":\"e2e4\"}"))
            .check(status().in(200, 201))
        )
        .pause(Duration.ofMillis(100))
        .exec(http("GET /api/state (after move)")
            .get("/api/state?sessionId=#{sid}")
            .check(status().is(200))
        )
        .pause(Duration.ofMillis(100))
        .exec(http("GET /api/legal-moves d7")
            .get("/api/legal-moves?square=d7&sessionId=#{sid}")
            .check(status().is(200))
            .check(jsonPath("$[0]").exists())
        )
        .pause(Duration.ofMillis(100))
        .exec(http("POST /api/command (d7d5)")
            .post("/api/command?sessionId=#{sid}")
            .body(StringBody("{\"command\":\"d7d5\"}"))
            .check(status().in(200, 201))
        );

    ScenarioBuilder puzzleScenario = scenario("Puzzle FEN")
        .exec(http("GET /api/puzzles/legal-moves")
            .get("/api/puzzles/legal-moves")
            .queryParam("fen", "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1")
            .queryParam("square", "e5")
            .check(status().is(200))
            .check(jsonPath("$[0]").exists())
        );

    {
        setUp(
            pingScenario.injectOpen(constantUsersPerSec(2).during(10)),
            gameplayScenario.injectOpen(
                rampUsers(gameplayUsers).during(rampSeconds),
                constantUsersPerSec(constantUsersPerSecValue).during(holdSeconds),
                rampUsers(0).during(rampDownSeconds)
            ),
            puzzleScenario.injectOpen(
                rampUsers(Math.max(5, gameplayUsers / 2)).during(Math.max(10, rampSeconds / 2)),
                constantUsersPerSec(Math.max(1, constantUsersPerSecValue / 2)).during(Math.max(10, holdSeconds / 2))
            )
        ).protocols(httpProtocol)
         .assertions(
            global().responseTime().percentile3().lt(500),
            global().failedRequests().percent().lt(1.0)
         );
    }
}

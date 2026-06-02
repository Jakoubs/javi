/**
 * k6 Load Test for Javi Chess REST API
 *
 * Scenarios:
 *   smoke  – 1 VU, 10s   (sanity check)
 *   load   – ramp 1→20 VUs over 30s, hold 30s, ramp down 10s
 *
 * Run:
 *   ./perf/k6 run perf/k6_load_test.js
 *   ./perf/k6 run --env SCENARIO=smoke perf/k6_load_test.js
 */

import http from "k6/http";
import { check, group, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

// ── Custom metrics ──────────────────────────────────────────────────────────
const errorRate = new Rate("errors");
const pingTrend = new Trend("ping_duration", true);
const stateTrend = new Trend("state_duration", true);
const commandTrend = new Trend("command_duration", true);
const legalMovesTrend = new Trend("legal_moves_duration", true);

// ── Configuration ───────────────────────────────────────────────────────────
const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";

export const options = {
  scenarios: {
    smoke: {
      executor: "constant-vus",
      vus: 1,
      duration: "10s",
      tags: { scenario: "smoke" },
    },
    load: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "30s", target: 20 },
        { duration: "30s", target: 20 },
        { duration: "10s", target: 0 },
      ],
      startTime: "12s", // start after smoke finishes
      tags: { scenario: "load" },
    },
    stress: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 500 }, // in 1 Min auf 500 pushen
        { duration: "1m", target: 500 }, // 1 Min lang extreme Last halten
        { duration: "20s", target: 0 },  // runterfahren
      ],
      startTime: "1m25s", // start nach load
      tags: { scenario: "stress" },
    },
    spike: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "10s", target: 800 }, // Extrem schneller Anstieg
        { duration: "30s", target: 800 }, // Peak kurz halten
        { duration: "10s", target: 0 },   // Schneller Abfall
      ],
      startTime: "3m50s", // start nach stress
      tags: { scenario: "spike" },
    },
    soak: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 100 },  // Langsam auf moderate Last
        { duration: "5m", target: 100 },  // Sehr lange halten (Memory Leaks finden)
        { duration: "30s", target: 0 },   // Runterfahren
      ],
      startTime: "4m45s", // start nach spike
      tags: { scenario: "soak" },
    },
    breakpoint: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 500 },  // Erste Stufe
        { duration: "1m", target: 1000 }, // Zweite Stufe
        { duration: "1m", target: 1500 }, // Dritte Stufe
        { duration: "1m", target: 2000 }, // Absolutes Limit
        { duration: "30s", target: 0 },
      ],
      startTime: "11m20s", // start nach soak
      tags: { scenario: "breakpoint" },
    },
  },
  thresholds: {
    http_req_duration: ["p(95)<500"],   // p95 latency < 500ms
    errors:           ["rate<0.01"],    // error rate < 1%
    ping_duration:    ["p(95)<100"],    // ping should be very fast
    state_duration:   ["p(95)<500"],
    command_duration: ["p(95)<500"],
    legal_moves_duration: ["p(95)<500"],
  },
};

// ── Helpers ─────────────────────────────────────────────────────────────────
const jsonHeaders = { headers: { "Content-Type": "application/json" } };

function uniqueSessionId() {
  return `k6-${__VU}-${__ITER}-${Date.now()}`;
}

// ── Main test function ──────────────────────────────────────────────────────
export default function () {
  const sid = uniqueSessionId();

  // 1) GET /ping
  group("ping", () => {
    const res = http.get(`${BASE_URL}/ping`);
    pingTrend.add(res.timings.duration);
    const ok = check(res, {
      "ping status 200": (r) => r.status === 200,
      "ping body pong":  (r) => r.body === "pong",
    });
    errorRate.add(!ok);
  });

  // 2) GET /api/state
  group("get_state", () => {
    const res = http.get(`${BASE_URL}/api/state?sessionId=${sid}`);
    stateTrend.add(res.timings.duration);
    const ok = check(res, {
      "state status 200":  (r) => r.status === 200,
      "state has fen":     (r) => r.json("fen") !== undefined,
    });
    errorRate.add(!ok);
  });

  // 3) POST /api/command – make move e2e4
  group("command_e2e4", () => {
    const payload = JSON.stringify({ command: "e2e4" });
    const res = http.post(
      `${BASE_URL}/api/command?sessionId=${sid}`,
      payload,
      jsonHeaders
    );
    commandTrend.add(res.timings.duration);
    const ok = check(res, {
      "command status 2xx": (r) => r.status >= 200 && r.status < 300,
    });
    errorRate.add(!ok);
  });

  // 4) GET /api/legal-moves?square=d7 (after e2e4, black pawn on d7 is legal)
  group("legal_moves", () => {
    const res = http.get(
      `${BASE_URL}/api/legal-moves?square=d7&sessionId=${sid}`
    );
    legalMovesTrend.add(res.timings.duration);
    const ok = check(res, {
      "legal-moves status 200": (r) => r.status === 200,
      "legal-moves is array":   (r) => Array.isArray(r.json()),
    });
    errorRate.add(!ok);
  });

  sleep(0.3);
}

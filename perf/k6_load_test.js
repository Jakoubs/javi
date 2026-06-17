/**
 * k6 performance test for the Javi Chess REST API.
 *
 * Default profile:
 *   PERF_PROFILE=assignment k6 run perf/k6_load_test.js
 *
 * Other profiles:
 *   PERF_PROFILE=smoke      k6 run perf/k6_load_test.js
 *   PERF_PROFILE=full       k6 run perf/k6_load_test.js
 *
 * Target:
 *   BASE_URL=http://localhost:8080 k6 run perf/k6_load_test.js
 */

import http from "k6/http";
import { check, group, sleep } from "k6";
import { Rate, Trend } from "k6/metrics";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const PROFILE = __ENV.PERF_PROFILE || "assignment";

const errorRate = new Rate("errors");
const pingTrend = new Trend("ping_duration", true);
const stateTrend = new Trend("state_duration", true);
const commandTrend = new Trend("command_duration", true);
const legalMovesTrend = new Trend("legal_moves_duration", true);
const puzzleTrend = new Trend("puzzle_duration", true);
const puzzleLegalMovesTrend = new Trend("puzzle_legal_moves_duration", true);

const profiles = {
  smoke: {
    smoke: {
      executor: "constant-vus",
      vus: 1,
      duration: "10s",
      tags: { scenario: "smoke" },
    },
  },
  assignment: {
    smoke: {
      executor: "constant-vus",
      vus: 1,
      duration: "8s",
      tags: { scenario: "smoke" },
    },
    load: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "15s", target: 20 },
        { duration: "25s", target: 20 },
        { duration: "10s", target: 0 },
      ],
      startTime: "10s",
      tags: { scenario: "load" },
    },
    spike: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "5s", target: 80 },
        { duration: "10s", target: 80 },
        { duration: "5s", target: 0 },
      ],
      startTime: "1m5s",
      tags: { scenario: "spike" },
    },
  },
  full: {
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
      startTime: "12s",
      tags: { scenario: "load" },
    },
    stress: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 500 },
        { duration: "1m", target: 500 },
        { duration: "20s", target: 0 },
      ],
      startTime: "1m25s",
      tags: { scenario: "stress" },
    },
    spike: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "10s", target: 800 },
        { duration: "30s", target: 800 },
        { duration: "10s", target: 0 },
      ],
      startTime: "3m50s",
      tags: { scenario: "spike" },
    },
    soak: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 100 },
        { duration: "5m", target: 100 },
        { duration: "30s", target: 0 },
      ],
      startTime: "4m45s",
      tags: { scenario: "soak" },
    },
    breakpoint: {
      executor: "ramping-vus",
      startVUs: 1,
      stages: [
        { duration: "1m", target: 500 },
        { duration: "1m", target: 1000 },
        { duration: "1m", target: 1500 },
        { duration: "1m", target: 2000 },
        { duration: "30s", target: 0 },
      ],
      startTime: "11m20s",
      tags: { scenario: "breakpoint" },
    },
  },
};

export const options = {
  scenarios: profiles[PROFILE] || profiles.assignment,
  thresholds: {
    http_req_duration: ["p(95)<500"],
    errors: ["rate<0.01"],
    ping_duration: ["p(95)<100"],
    state_duration: ["p(95)<500"],
    command_duration: ["p(95)<500"],
    legal_moves_duration: ["p(95)<500"],
    puzzle_duration: ["p(95)<750"],
    puzzle_legal_moves_duration: ["p(95)<750"],
  },
};

const jsonHeaders = { headers: { "Content-Type": "application/json" } };
const complexFen =
  "r3k2r/p1ppqpb1/bn2pnp1/3PN3/1p2P3/2N2Q1p/PPPBBPPP/R3K2R w KQkq - 0 1";

function uniqueSessionId() {
  return `k6-${__VU}-${__ITER}-${Date.now()}`;
}

function record(ok) {
  errorRate.add(!ok);
}

export default function () {
  const sid = uniqueSessionId();

  group("ping", () => {
    const res = http.get(`${BASE_URL}/ping`);
    pingTrend.add(res.timings.duration);
    record(check(res, {
      "ping status 200": (r) => r.status === 200,
      "ping body pong": (r) => r.body === "pong",
    }));
  });

  group("state", () => {
    const res = http.get(`${BASE_URL}/api/state?sessionId=${sid}`);
    stateTrend.add(res.timings.duration);
    record(check(res, {
      "state status 200": (r) => r.status === 200,
      "state has fen": (r) => r.json("fen") !== undefined,
    }));
  });

  group("command_e2e4", () => {
    const res = http.post(
      `${BASE_URL}/api/command?sessionId=${sid}`,
      JSON.stringify({ command: "e2e4" }),
      jsonHeaders
    );
    commandTrend.add(res.timings.duration);
    record(check(res, {
      "command status 2xx": (r) => r.status >= 200 && r.status < 300,
    }));
  });

  group("legal_moves", () => {
    const res = http.get(`${BASE_URL}/api/legal-moves?square=d7&sessionId=${sid}`);
    legalMovesTrend.add(res.timings.duration);
    record(check(res, {
      "legal moves status 200": (r) => r.status === 200,
      "legal moves is array": (r) => Array.isArray(r.json()),
    }));
  });

  group("puzzle_random", () => {
    const res = http.get(`${BASE_URL}/api/puzzles/random`);
    puzzleTrend.add(res.timings.duration);
    record(check(res, {
      "puzzle random status is not server error": (r) => r.status < 500,
    }));
  });

  group("puzzle_legal_moves_from_fen", () => {
    const res = http.get(
      `${BASE_URL}/api/puzzles/legal-moves?fen=${encodeURIComponent(complexFen)}&square=e5`
    );
    puzzleLegalMovesTrend.add(res.timings.duration);
    record(check(res, {
      "puzzle legal moves status 200": (r) => r.status === 200,
      "puzzle legal moves is array": (r) => Array.isArray(r.json()),
    }));
  });

  sleep(0.3);
}

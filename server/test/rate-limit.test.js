import assert from "node:assert/strict";
import test from "node:test";

import { FixedWindowRateLimiter } from "../src/rate-limit.js";

test("limits requests inside a fixed window", () => {
  const limiter = new FixedWindowRateLimiter(1000);
  assert.equal(limiter.allow("client", 2, 100), true);
  assert.equal(limiter.allow("client", 2, 200), true);
  assert.equal(limiter.allow("client", 2, 300), false);
  assert.equal(limiter.allow("client", 2, 1100), true);
});

test("tracks rate limits independently", () => {
  const limiter = new FixedWindowRateLimiter(1000);
  assert.equal(limiter.allow("first", 1, 0), true);
  assert.equal(limiter.allow("first", 1, 1), false);
  assert.equal(limiter.allow("second", 1, 1), true);
});

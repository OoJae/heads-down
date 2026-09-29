import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    include: ["test/**/*.test.ts"],
    environment: "node",
    // PGlite boots a WASM Postgres per test file; give it room on slow CI.
    testTimeout: 60_000,
    hookTimeout: 60_000,
  },
});

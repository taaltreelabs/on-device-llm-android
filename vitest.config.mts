import { defineConfig } from 'vitest/config';

// No alias for `@taaltreelabs/on-device-llm/core` is needed: Vite's own resolver
// reads the peer package's `exports` map, so the subpath resolves to
// `build/core/index.js` the same way Node and Metro resolve it at runtime. The
// package is installed here as a git devDependency and its `prepare` script
// builds `build/` on install, so that file exists after `npm install`. If it ever
// does not, the failure is "cannot find module", not a silently different module
// — which is the failure mode worth keeping.
//
// The tsconfig `paths` entry beside this file is for tsc only (its node10
// resolver ignores `exports`); deliberately not duplicated here, so there is one
// place a wrong path can hide rather than two that can disagree.
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/**/__tests__/**/*.test.ts'],
    exclude: ['node_modules', 'build', 'example'],
  },
});

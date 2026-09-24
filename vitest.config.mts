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
    // `plugin/**` is the Expo config plugin (plain CommonJS, no build step —
    // see plugin/withOnDeviceLlmAndroid.js) and its tests are plain content-
    // transform tests with no Expo project involved, so they run through the
    // same vitest config as the rest of the package rather than a separate
    // toolchain.
    include: ['src/**/__tests__/**/*.test.ts', 'plugin/**/__tests__/**/*.test.js'],
    exclude: ['node_modules', 'build', 'example'],
  },
});

const { defineConfig } = require('eslint/config');
const universe = require('eslint-config-universe/flat/native');

// ---------------------------------------------------------------------------
//  THE INVERSE ISOLATION RULE
// ---------------------------------------------------------------------------
//
//  The main package enforces that its `core` and `openai` layers import nothing
//  React/React-Native/Expo/native, so they stay importable from plain Node. That
//  rule has no counterpart here: this package is *entirely* the native layer, so
//  there is nothing to keep Node-importable and no core/provider boundary to
//  police.
//
//  The rule that does matter here is the inverse one: **this package must never
//  import `react`.**
//
//  It has no components, no hooks and no JSX — it is a provider object with six
//  methods. `react` is not even a peer dependency (see package.json: only
//  `@taaltreelabs/on-device-llm`, `expo` and `react-native` are), so an `import
//  'react'` here would be an undeclared dependency that happens to resolve in
//  every app that installs this package and fails in every other consumer —
//  including a Node script that only wanted `availability()`. It would also
//  quietly pull a second copy of React into a bundle if versions ever diverged,
//  which is the hardest class of React bug to diagnose. The React hooks
//  (`useChat`, `useAvailability`, `useGenerate`) live in the main package's
//  `react` subpath and consume this provider through the `LLMProvider`
//  interface, which is the direction the dependency belongs.
//
//  `import type { ... } from 'react'` is permitted and is the one exception: a
//  type import is erased at compile time, so it creates no runtime edge, and it
//  is the honest way to describe a value a consumer's React code hands us. None
//  of the current sources need one; the allowance exists so that adding one does
//  not require weakening this rule.
//
//  To sanity-check that this fires: temporarily add `import 'react';` to
//  src/index.ts and run `npm run lint`. Remove it afterwards.
// ---------------------------------------------------------------------------

const noReactOverride = {
  files: ['src/**/*.{ts,tsx}'],
  rules: {
    'no-restricted-imports': [
      'error',
      {
        paths: [
          {
            name: 'react',
            message:
              'This package must not import react. It declares no react peer dependency and ' +
              'ships no components or hooks — those live in @taaltreelabs/on-device-llm/react, ' +
              'which consumes this provider through the LLMProvider interface. If you genuinely ' +
              'need a React *type*, use `import type { … } from "react"`, which is erased at ' +
              'compile time (see eslint.config.cjs).',
            allowTypeImports: true,
          },
        ],
        patterns: [
          {
            group: ['react/*', 'react-dom', 'react-dom/*'],
            message:
              'This package must not import react or react-dom (see eslint.config.cjs).',
          },
        ],
      },
    ],
    // The ESM rule above cannot see `require('react')`, which is exactly how the
    // lazy resolution in src/native/resolve.ts reaches `expo` and `react-native`
    // — so the same prohibition is restated for CommonJS.
    'no-restricted-modules': [
      'error',
      {
        paths: [
          {
            name: 'react',
            message:
              'This package must not require react (see eslint.config.cjs).',
          },
        ],
        patterns: ['react/*', 'react-dom', 'react-dom/*'],
      },
    ],
  },
};

module.exports = defineConfig([
  { ignores: ['build', 'example'] },
  ...universe,
  noReactOverride,
]);

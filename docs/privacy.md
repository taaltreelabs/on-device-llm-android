# Privacy and SDK terms

[Back to the README](../README.md)

## On-device processing and metrics

Google states that ML Kit processes inputs and outputs on-device without sending
that content to its servers. The SDK sends usage and performance metrics and may
contact Google for model updates and compatibility information. App developers
must inform users about metrics processing where required by applicable law.
See [ML Kit terms and privacy](https://developers.google.com/ml-kit/terms).

The Android provider is a separate install. Its `compileOnly` dependency lets your
app explicitly choose whether to include Google's runtime SDK through the config
plugin or Gradle setup. Installing the JavaScript package alone does not include it.

## Cloud providers and tools

A configured cloud provider receives request content when your router selects it.
Cloud summarizers and app-defined tools may also send data off-device. Keep vendor
API secrets on your backend and use appropriate authentication for your app.

The Android error mapper does not emit the shared `guardrail` code. A policy that
checks that code cannot identify Android safety refusals. Choose fallback rules
based on the signals this provider exposes and your app's data requirements.

## Google's GenAI terms

The additional terms include age and audience restrictions, limitations on certain
uses, and restrictions on production use of services designated Preview or
Experimental Access. They also address competing products, documented features,
and medical uses. Read the governing
[ML Kit GenAI additional terms](https://developers.google.com/ml-kit/genai-terms)
for your app's intended use.

Google's [Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) is
currently labeled beta and may introduce incompatible changes. The plugin pins
`1.0.0-beta4`; review SDK changes before changing that version. Google's SDK status
is separate from this npm package's release status.

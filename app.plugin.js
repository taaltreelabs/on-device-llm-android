// Expo resolves a package's config plugin by loading `app.plugin.js` at the
// package root before falling back to `main` — see `plugin/withOnDeviceLlmAndroid.js`
// for the actual implementation and the reasoning behind each of its three edits.
module.exports = require('./plugin/withOnDeviceLlmAndroid');

# Yahtzee-Online — Agent Notes

- **Default branch is `master`, not `main`.** Push/PR against `master`.
- **Backend is Firebase Realtime Database** (project `yahtzee-online-66a26`), with
  **anonymous auth** as the write gate (`FirebaseSignIn.kt`). There is no Firestore
  usage anywhere in this app.
- **Database rules are manual.** `firebase-database-rules.json` at the repo root is
  the source of truth, but nothing deploys it — there's no `firebase.json`,
  `.firebaserc`, or CI step that pushes rules. After editing that file, the JSON
  must be pasted into the Firebase console by hand, or the change has no effect.
- **Rules are a strict allowlist tied to the current write shape**: almost every
  node ends in `"$other": {".validate": false}` and requires specific fields via
  `hasChildren(...)`. If you change what the app writes (add/rename/remove a
  field) and change the rules to match, deploy order matters: pasting the new
  rules before all clients are on the matching build will get old clients'
  writes rejected; shipping the app change first without updating rules can get
  new clients' writes rejected instead. Coordinate which goes first based on the
  actual change.
- **The in-app OTA updater (`UpdateChecker.kt`) matches on any GitHub release
  asset whose filename ends in `.apk`** (first match wins if there's more than
  one) — unlike sibling projects, the exact filename does not matter here.
- **Never `adb install` to the Shield.** The user updates that device manually;
  only install/test on the Pixel over adb.

# Contributing

- Small pull requests against `main`; CI must be green before a merge.
- A change to `plugin-api` and the fixes it needs in every plugin go in the same pull request, so `main` always
  builds. Add the change to `plugin-api/CHANGELOG.md`, and say in the pull request whether it only adds (a new patch)
  or breaks (a new minor, before 1.0); `plugin-api/README.md` has the rules. The interface dump
  `plugin-api/api/plugin-api.api` changes in the same commit (CI's `apiCheck` is red otherwise); a removed or changed
  line in it is a break.
- A plugin change bumps that plugin's version in its `build.gradle` and its plugin class, with a line in its CHANGELOG.
- A plugin reaches the app only through `plugin-api` (each plugin's repo guard test checks the imports).
- Commit messages in plain words; no em-dashes.
- Tags per module after a merge: `<module>-v<version>`.

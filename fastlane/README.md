# Fastlane

This repository uses Fastlane for Android testing and release automation. The
Fastlane dependencies are declared in the root `Gemfile`.

## Setup

Install Ruby and Bundler, then install the project gems from the repository root:

```sh
bundle install
```

Android lanes also require JDK 21, the Android SDK, and the repository's Git
submodules. See [the project build guide](../readme.md) for SDK and Gradle
requirements.

## Android lanes

Run a lane from the repository root with `bundle exec fastlane android <lane>`:

| Lane | Action |
| --- | --- |
| `test` | Runs Gradle's `test` task. |
| `buildrel` | Builds the release APK with `:app:assembleRelease`. |
| `googleplay` | Builds the Google Play release bundle and uploads it to the beta track as a draft. |
| `gitrel` | Builds a release and creates a draft GitHub release. |
| `preprel` | Interactive maintainer workflow for preparing a release. |

For example:

```sh
bundle exec fastlane android test
```

Release lanes are maintainer workflows. They require the configured signing,
Google Play, or GitHub credentials as applicable, and `buildrel` and `googleplay`
require a clean checkout of the `beta` branch. Review `fastlane/Fastfile` before
running them for the currently configured metadata paths and credentials.

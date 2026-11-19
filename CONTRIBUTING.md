Thank you for your interest in contributing to Plug!

Plug is a fork of [Gramophone](https://github.com/FoedusProgramme/Gramophone).
Please preserve its GPLv3-or-later license and original copyright notices and
attribution in contributions.

We, the maintainers, would like to ask you to open a short, informal issue before starting substantial work on a pull request, so that scope, implementation details, etc can be discussed. For example, sometimes a PR you plan to send may change an area of code where a big refactor is happening at the moment which would cause most of the PR to need a rewrite before even being merged, in which case we can warn you before you write all the code if you open an issue.

We will of course do our best to accommodate pull requests that we weren't informed of in advance, but even the best feature idea may not be a great fit. Plug keeps music playback and library browsing local-first, while optional Spicy Lyrics online lookup is disabled by default. Please preserve this opt-in behavior and discuss any new network-dependent features before implementation.

## Local development

The project requires JDK 21, Android SDK Platform 37, Android NDK, and CMake 3.22.1. Clone with the Git submodules initialized, then build and test with the Gradle wrapper:

```sh
git clone --recurse-submodules https://github.com/FoedusProgramme/Gramophone.git
cd Gramophone
./gradlew :app:assembleDebug
./gradlew test
```

On Windows, use `.\gradlew.bat` instead of `./gradlew`. The tracked `package.properties` already contains `releaseType=SelfBuilt`. No `.env` file or Python dependency manifest is used. See [readme.md](./readme.md) for the module layout, build properties, signing configuration, and baseline-profile task.

It's always sad if we have to reject a well-intentioned PR: to avoid it, and to avoid wasting your precious time as well, we ask that you discuss with us in advance. Thank you for your understanding, and we look forward to your contributions!

# Release pipeline

General CI runs on pull requests and pushes to `main`, not on feature-branch
pushes or release tags. Keep the required `Hub unit tests`, `Plugin unit tests`
and SDK checks enabled: they still cover every pull request, including workflow
changes. SDK checks cancel superseded runs just like the application tests.

Hub CI tests and assembles the Release variants in one Gradle invocation. This
checks the variant that ships and populates the default-branch Gradle cache with
release compilation and test outputs. Plugin CI also tests Release variants.
The separate cache-warming workflow is unnecessary: it used to compile the hubs
again while the main test jobs ran.

AGP 9 only creates Debug unit-test tasks by default. These CI commands pass
`-Pandroid.onlyEnableUnitTestForTheTestedBuildType=false` to enable Release
unit tests as well; local Debug commands and IDE defaults remain unchanged.

Publish as before with `v<version>`, `<plugin>-v<version>`, or `sdk-v<version>`.
App and plugin release workflows still check the tag against the module version,
run tests, build from the tagged source, sign the APKs and publish only after all
those steps succeed. Tests and APK assembly share the same Release compilation
in a single Gradle invocation; they no longer compile Debug just to run the same
unit-test sources before separately compiling Release. Patcher retains its
Python bundle checks and package-read credentials.

Gradle's setup action writes caches on the default branch and reads them on PRs
and tags. A tag pushed immediately after merging may precede the latest cache
save; it can still use an older main cache and rebuild changed tasks. Publishing
does not wait for a cache warmer, skip tests based on a mutable status, or reuse
an APK built from a different commit. Signing remains exclusive to release jobs.

To measure the improvement, compare the next app release's combined test/build
step with the previous separate steps (3m13s tests + 2m27s build for v2.0.4).
The cache state and changed source affect elapsed time, so local warm builds
are not a prediction of hosted-runner release duration.

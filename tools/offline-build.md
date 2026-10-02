# Building without access to Google Maven

Normal machines (Android Studio, GitHub Actions) need nothing special: open the project and build.

Some sandboxes cannot reach `dl.google.com` (Google Maven + SDK downloads). For those:

1. Run the **deps-mirror** workflow (Actions tab -> deps-mirror -> Run workflow, or push to the
   `deps-request` branch). It resolves every dependency on GitHub's servers and publishes the
   Google-hosted ones as a plain Maven repository on the `deps-mirror` branch.
2. Fetch it: `git fetch origin deps-mirror && git worktree add /opt/m2mirror FETCH_HEAD`
3. Install `tools/google-mirror.init.gradle.kts` into `~/.gradle/init.d/` (adjust the path inside).
4. Point `local.properties` at an Android SDK containing `platforms/android-37.1` and
   `build-tools/37.0.0`.

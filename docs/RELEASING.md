# Releases

GitHub Actions owns APK publication. On main, CI restores the persistent debug signing keystore from the repository secret `ANDROID_DEBUG_KEYSTORE_BASE64`, runs all unit tests, builds the APK, and publishes a release for the version in `app/build.gradle.kts`.

To ship an update, increment both `versionCode` and `versionName`, add `docs/releases/v<VERSION>.md`, and push or merge to main. Existing releases are never overwritten. A manual workflow run on main can retry a failed publication. Pull requests build and test without access to the persistent signing key and never publish.

Keep the signing secret unchanged. Replacing it breaks in-place upgrades. The keystore uses alias `androiddebugkey` and Android's standard debug store/key password `android`; the private keystore itself must remain secret. Missing signing secrets fail the main build instead of silently generating a new key. Retain an encrypted backup of the keystore outside the repository.

v0.2.8 starts the persistent signing-key lineage. Earlier APKs used different keys and require a data-preserving migration or a backed-up reinstall.

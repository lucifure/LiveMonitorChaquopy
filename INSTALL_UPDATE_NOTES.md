# Installing updates over an existing Live Monitor app

Android will only install an APK as an update when **both** of these match the app already installed on the phone:

1. The package name / application ID (`com.livemonitor.app`).
2. The signing certificate used to sign the APK.

If Android shows **"App not installed as package conflicts with an existing package"**, the APK has the same package name as the installed app but was signed with a different certificate. Android blocks that update before the app can run, so an in-app migration cannot fix it.

## What this means for saved recordings and logs

Do **not** uninstall the existing app if you still need recordings or logs from it. Uninstalling normally removes the app's private data.

To update while keeping the existing app data, rebuild/sign the new APK with the same signing key that was used for the version already installed on the phone:

- If the installed app came from a debug build on your computer, use the same computer/user account or the same debug keystore (`~/.android/debug.keystore`) to build the replacement debug APK.
- If the installed app came from a release build, sign the new APK with that same release keystore.
- A debug APK from CI/GitHub Actions or another machine usually will **not** update an app installed from your own machine, because the debug keystore is different.

If the original signing key is not available, Android will not allow an update over the existing install. In that case, first export/copy any accessible recordings/logs from inside the installed app before uninstalling or installing a differently signed APK.

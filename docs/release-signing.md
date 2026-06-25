# Release signing secrets

The CI release build signs `app-release.apk` with a persistent GitHub Actions keystore so installs can update the existing `com.livemonitor.app` package over the air.

Create these repository secrets in GitHub under **Settings > Secrets and variables > Actions > Repository secrets**:

- `KEYSTORE_BASE64`: Base64-encoded release keystore bytes.
- `KEYSTORE_PASSWORD`: Keystore password.
- `KEY_ALIAS`: Release key alias.
- `KEY_PASSWORD`: Release key password.

The generated values for this keystore were written to the local, untracked file `RELEASE_KEYSTORE_SECRETS.txt`. Save that file in a secure password manager and add each value as a GitHub Actions repository secret. Do not commit the secret values or the decoded keystore file.

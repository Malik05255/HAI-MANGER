# HAI MANAGER release signing

HAI MANAGER must use one permanent Android signing key for every installable update.

The CI now expects only these GitHub Actions repository secrets:

- `HAI_KEYSTORE_B64`
- `HAI_KEYSTORE_PASSWORD`

The key alias is fixed in the project as `hai_manager`, and the PKCS12 private key uses the same password as the keystore. This removes the previous source of signing failures.

Expected release certificate SHA-256:

`D59B0F985ED8CF4A903938B7904A5F33EDA01E8817E994B6A20D78D164BC2441`

The workflow validates the keystore, signs a test JAR, builds the release APK, and verifies the APK certificate before publishing an installable update.

If either signing secret is missing or invalid, the workflow must not publish an installable update. Debug APKs remain CI artifacts for testing only.

After the first release signed with this key is installed, do not regenerate or replace the keystore. Changing the key will make Android reject in-place updates.

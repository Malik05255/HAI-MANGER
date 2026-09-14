# HAI MANAGER release signing

HAI MANAGER must use one permanent Android signing key for every installable update.

The CI expects these GitHub Actions repository secrets:

- `HAI_KEYSTORE_B64`
- `HAI_KEYSTORE_PASSWORD`
- `HAI_KEY_ALIAS`
- `HAI_KEY_PASSWORD`

Expected release certificate SHA-256:

`BE6D46C373883309CA8E5EA8DDC11CE02339352C4D690EF74387CEA8275E7DF2`

The workflow intentionally does **not** publish an installable beta update when those secrets are missing. Debug APKs remain available only as CI artifacts for testing.

After the first release signed with this key is installed, do not regenerate or replace the keystore. Changing the key will make Android reject in-place updates.

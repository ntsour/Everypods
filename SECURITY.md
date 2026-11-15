# Security Policy

Please do not disclose a suspected vulnerability in a public issue.

Send a private report to `automated.ventures.apps@gmail.com` with:

- the affected app version and Android version;
- the AirPods model and relevant feature;
- reproduction steps and expected behavior;
- logs or proof of concept only after removing Bluetooth addresses, tokens,
  contact data, and other personal information.

We will acknowledge reports when practical, investigate reproducible issues,
and coordinate a fix or mitigation before public disclosure when appropriate.

Do not include signing keys, passwords, API keys, or private device data in
issues, pull requests, or support emails.

## Repository protection

The repository is private. On the current GitHub plan, branch protection,
private-repository CodeQL scanning, and the dependency review action are
unavailable; review changes and run the Android checks before merging. Do not assume GitHub enforces those checks.

The manual Play bundle workflow names the `play-release` environment. On GitHub
Free, private-repository environment secrets and deployment protections are
ignored; do not use the workflow until the account supports those features and
its `main`-only environment policy and secrets have been verified. Build release
bundles locally with the configured release signing key in the meantime. Signing
keys and passwords must never be committed to this repository.

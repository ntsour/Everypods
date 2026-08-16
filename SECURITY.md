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

`main` is protected on GitHub. Changes must go through a pull request with one
approval, resolved review conversations, up-to-date required checks, linear
history, and no force pushes or branch deletion. Required checks are Android
CI, CodeQL, and dependency review. Administrators are subject to these rules.

The manual Play bundle workflow uses the protected `play-release` environment.
Its signing secrets must stay in that environment and must never be committed
to this repository.

# Contributing

Thank you for helping improve Android iMessage Relay.

## Before opening a change

- Search existing issues and discussions.
- Use a security advisory instead of a public issue for vulnerabilities.
- Keep changes focused and avoid new cloud services or Android permissions without prior discussion.
- Do not include real endpoints, account identifiers, phone numbers, credentials, keys, device names, local paths, logs containing message text, or Terraform state.

## Development checks

```shell
cd android
./gradlew test lint assembleDebug
```

```shell
cd mac
python3.12 -m venv .venv
.venv/bin/pip install --require-hashes -r dev-requirements.lock
.venv/bin/pip install --no-deps -e .
.venv/bin/pytest
```

```shell
cd infra/cloudflare
terraform fmt -check
terraform init -backend=false
terraform validate
```

Use English for source, comments, documentation, issue content, and commit messages. Prefer a Conventional Commit such as `fix(mac): recover expired lease`.

## Pull requests

Explain the problem, security effect, tests, and operational impact. Keep generated output, secrets, state, APKs, certificates, and local configuration out of the branch. All required checks must pass before merge.

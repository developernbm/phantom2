# Contributing to Phantom2

Thanks for your interest. This project accepts contributions under specific
conditions — please read this whole file before opening a PR.

## Before You Contribute

By submitting any code, documentation, or asset to this repository, you agree
that:

1. You have the legal right to submit it.
2. It is licensed under the same MIT license as this project.
3. You understand the ethical and legal boundaries described in
   [DISCLAIMER.md](DISCLAIMER.md).
4. You will not add features that facilitate unauthorized surveillance,
   non-consensual tracking, or abuse of any kind.

## What We Accept

- Bug fixes
- Documentation improvements
- New overlay templates (with screenshot)
- New AI analysis scopes
- Performance improvements
- New language translations of the panel UI
- Android compatibility fixes for newer SDK versions
- Security hardening (TLS, token rotation, etc.)

## What We Reject

- Features designed specifically to harm non-consenting users
- Anything that targets children, healthcare devices, or emergency services
- Code that hides the tool's purpose from the operator
- Additions that introduce network dependencies on third-party trackers
- Bulk copy-paste from other RAT projects without attribution

## Development Setup

See [docs/BUILDING.md](docs/BUILDING.md).

## Coding Style

### JavaScript (panel)
- 4-space indent
- Single quotes for strings
- Semicolons required
- `const` over `let` when possible
- No external libraries unless already in `package.json`

### Java (Android client)
- 4-space indent
- All activities extend `android.app.Activity` (no AppCompat)
- `try/catch (Throwable)` around every Android API call that can fail
- No reflection unless necessary
- Every `findViewById` wrapped in try/catch

### Documentation
- Markdown
- Line length under 100 characters
- Code blocks with language tags

## Pull Request Process

1. Fork the repo
2. Create a branch: `git checkout -b feature/your-feature-name`
3. Commit with clear messages: `Add TLS support to SocketManager`
4. Push to your fork
5. Open a PR against `main`
6. Fill out the PR template completely
7. Wait for review — expect 3–14 days

## Testing

Before submitting:
- Panel: `npm start` and click through every tab
- Android: build with `gradlew assembleRelease`, install on a test device
- Verify your change doesn't break the wire protocol (see `docs/PROTOCOL.md`)

## Reporting Bugs

Use the GitHub issue tracker. Include:
- Phantom2 version
- Android version (if client-related)
- Host OS (if panel-related)
- Steps to reproduce
- Expected vs actual behavior
- Screenshots or log excerpts

## Reporting Security Issues

Do NOT open a public issue for security vulnerabilities. Email the
maintainers directly (see GitHub profile) with details.

## Code of Conduct

- Be respectful
- No politics
- No harassment
- No discussion of illegal use of the tool
- Keep threads on-topic

Violations result in immediate ban.

## Attribution

If you fork Phantom2 and publish a modified version, please credit the
original project in your README. This is not legally required (MIT), but it
is the norm in the security research community.

## Thank You

Every contribution — code, docs, bug reports, translations — makes Phantom2
better for everyone doing legitimate security work.
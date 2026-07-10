# Security Policy

Arcanum is a security and duress tool - its users may be journalists, activists, or people at personal risk. I take vulnerability reports seriously and aim to fix genuine issues quickly.

## Supported versions

Only the latest release receives security fixes. Please reproduce issues against the most recent version before reporting.

| Version | Supported |
|---------|-----------|
| 1.3.x   | Yes       |
| < 1.3   | No        |

## Reporting a vulnerability

Please report security issues **privately** through GitHub Security Advisories:

**https://github.com/Esdex/Arcanum/security/advisories/new**

(In the repository: the **Security** tab -> **Report a vulnerability**.)

Do not open a public issue, pull request, or discussion for a vulnerability - public disclosure before a fix ships is 0-day disclosure that puts users at risk. Low-severity, non-exploitable hardening ideas are fine as normal issues.

If you cannot use GitHub Security Advisories, open a public issue containing no technical details - just a request for a private contact channel - and I will follow up.

## What to include

A good report is much faster to fix. Where possible, please include:

- Affected version (versionName / versionCode) and, for a binary, the APK SHA-256 and the source commit.
- Build flavor (fdroid / playstore) and the Android version / API level you tested.
- A clear description of the issue and its security impact - which boundary it crosses (PIN, panic PIN, biometric, calculator disguise, panic wipe, FLAG_SECURE, or container encryption).
- Step-by-step reproduction, and a proof of concept if you have one.
- A suggested fix (optional, appreciated).

## Scope

In scope - anything that breaks a security guarantee of the app, for example:

- Recovery of encrypted container contents without the correct password or keyfile.
- Bypass of the PIN, panic PIN, biometric, or calculator disguise.
- Panic mode failing to perform its configured action (for example, a container that is not deleted).
- Leakage of decrypted vault data (files, thumbnails, filenames, metadata) to other apps, the system, logs, or backups.
- Weaknesses in the cryptography, the JNI bridge, or key / keyfile handling.

Out of scope - generally not treated as vulnerabilities:

- Attacks that require a rooted or otherwise compromised device, a malicious OS, or physical extraction of an already-unlocked device's memory.
- Attacks that require the user's PIN or an already-mounted container.
- Social engineering, phishing, or shoulder-surfing.
- Reports from automated scanners without a demonstrated, exploitable impact.

If you are unsure whether something is in scope, report it anyway.

## Disclosure process

- I will acknowledge your report as soon as I can, normally within a few days.
- I will confirm the issue, assess its severity, and keep you updated on progress.
- I aim to fix critical issues before the next release and to ship high-severity fixes promptly.
- Please give me a reasonable window to release a fix before disclosing publicly. I am happy to coordinate timing and to credit you in the release notes and the advisory, unless you prefer to remain anonymous.

There is no paid bug bounty program, but your work is genuinely valued and will be credited.

## Cryptography note

Arcanum's container format is VeraCrypt-compatible. Issues in the underlying VeraCrypt design should be reported upstream; issues in Arcanum's own implementation, integration, or Android layer belong here.

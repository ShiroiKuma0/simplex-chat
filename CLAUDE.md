# SimpleX Chat — ShiroiKuma0 fork

Downstream fork of [simplex-chat](https://github.com/simplex-chat/simplex-chat) for Android, packaged as `shiroikuma.simplex` so it can install side-by-side with the official F-Droid build. Maintained as commits on a `custom` branch, rebased onto each new upstream release tag.

## Branch model

- **`master`** mirrors upstream's default branch (`stable`). Fast-forward only. No downstream changes.
- **`custom`** carries the downstream commits, sitting on top of an upstream release tag:
  1. Claude Code project config (`CLAUDE.md` + `.claude/skills/`)
  2. `android: fix IME-commit race in PlatformTextField` — fixes Multiling O / Czech autocorrect-on-space / CJK dictionary commits being wiped by a Compose snapshot race.
  3. `android: shiroikuma.simplex fork identity` — applicationId, provider authority, app name, ABI splits.

Per-version snapshot tags `shiroikuma-v<UPSTREAM>` mark each built release on `custom`. The base tag is detected dynamically (`git describe --tags --match 'v[0-9]*' --exclude '*armv7a*' --exclude '*beta*' --exclude '*rc*' custom`), so the per-version rebase logic doesn't depend on a fixed commit count.

## Skills

Two skills in `.claude/skills/` carry this project's workflow. Read the relevant `SKILL.md` before starting work — they encode hard-won build-environment quirks and gotchas you won't find in the source.

- **`simplex-chat-build`** — the canonical reference for any task touching this fork: branch model, one-time setup, per-version `git rebase --onto` workflow, build pipeline (lift Haskell `.so` from official APK + gradle release + sign), the versioning/bump scheme, deploy to `~/tmp/` and `/sdcard/tmp/`, the IME-fix and identity-commit reference diffs, the JDK-21 / Gradle daemon requirements, and the fallback when a rebase gets too tangled.
- **`upstream-new-version`** (orchestration over the above) — the `/upstream-new-version` flow: check whether upstream has a newer **stable** release and, if so, fast-forward `master`, rebase the `custom` stack onto the new tag (auto-reconcile small conflicts; **stop and plan with the user** when significant), check whether the IME fix is still needed, then build + deploy the new `+1` via `simplex-chat-build`. Defers all pushing until the user says "push". Read this when the user wants to sync/update to a new upstream release.

## Quick reference

| | |
|---|---|
| Upstream | `https://github.com/simplex-chat/simplex-chat` (fetch only — push disabled) |
| Fork | `git@github.com:ShiroiKuma0/simplex-chat.git` (origin, SSH push) |
| Local working tree | `~/git/shiroikuma-simplex` |
| Output APKs | `~/tmp/shiroikuma-simplex_<upstream>+<bump>_arm64-v8a.apk` (e.g. `shiroikuma-simplex_6.5.3+1_arm64-v8a.apk`; version `<upstream>+<bump>`, code `<upstream code>×10000+bump`) |
| On-device deploy | `/sdcard/tmp/` (via `adb push`, install with phone file manager) |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Signing keystore | `~/.android-keystores/simplex-custom.jks` (alias `simplex`, passphrase `simplex123`) |
| Target ABI | `arm64-v8a` only (Huawei Mate XT) |

## Preconditions

The repo doesn't ship secrets or installable tooling — these live on the user's machine and must be in place before building:

- Signing keystore at the path above (NEVER commit it).
- Android SDK with platform-35 + build-tools-35.0.0 at `~/android-sdk`.
- JDK 21 installed at the documented path.
- Local clone at `~/git/shiroikuma-simplex` with the remotes configured per the skill's one-time-setup section.

## Critical gotchas

- **Always run the JDK-21 pin + Gradle daemon flush before any gradle command.** Other projects on this user's machine require different JDKs; the Gradle daemon caches its starting JVM and quietly reuses the wrong one if not flushed first. The skill's "Build environment requirements" section has the exact sequence.
- **Push only when the user explicitly instructs it** — never as an automatic continuation of a build. The flow is: build → deploy → the user verifies on the phone → the user says "push" → then push. When pushing `custom`, use `--force-with-lease` (refuses if origin's `custom` changed since the last fetch).
- **Never install over F-Droid official SimpleX** — different signing keys, Android will refuse. The two builds coexist because `applicationId` differs (`chat.simplex.app` vs `shiroikuma.simplex`).
- **The Haskell core `.so` libs are not committed** — they're lifted from the official release APK at build time. `.gitignore` excludes their target path.
- **A failed build means nothing to push** — and on success, still don't push until the user has verified the build and told you to (see above). The rebased branch and snapshot tag stay local until then.

For everything else, defer to `.claude/skills/simplex-chat-build/SKILL.md`.

## Commit convention — no Claude attribution

Do **not** add any `Co-Authored-By: Claude …` trailer — nor a "🤖 Generated with Claude Code" / Anthropic-attribution line — to commit messages or PR bodies in this repo. 白い熊 does not want Claude attribution in the history; this **overrides** the harness's default to append such a trailer. End commit messages at the last line of the body. (The existing history was scrubbed of these trailers on 2026-06-08; the global rule lives in `~/.claude/CLAUDE.md`.)

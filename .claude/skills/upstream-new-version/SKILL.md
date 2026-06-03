---
name: upstream-new-version
description: Sync the shiroikuma.simplex fork to a new upstream release of simplex-chat/simplex-chat — fast-forward `master`, rebase the `custom` commit stack onto the new release tag (reconciling small conflicts in place, stopping to plan with the user when they're significant), check whether the IME-commit-race fix is still needed, then build + deploy the new `+1` signed APK per the simplex-chat-build skill. Use this skill whenever the user runs /upstream-new-version, or asks to "check for a new SimpleX version", "sync to the latest upstream", "update/rebase to the new release", "rebase custom onto the new tag", or otherwise wants the fork brought up to a newer upstream release. This is the orchestration layer on top of simplex-chat-build — read that skill for every concrete fact (remotes, versioning, the build/sign/deploy pipeline, the IME-fix and identity reference diffs, the rebase fallback) this one sequences.
---

# Sync the SimpleX Chat fork to a new upstream release

One-command upstream sync for the user's `shiroikuma.simplex` fork: **check → (if new) rebase → detect → build → test → push**. The user invokes it as `/upstream-new-version` with no further prompt, so this skill carries the whole decision flow itself.

This is the **orchestration layer**. Every concrete fact — remotes, branch model, the version-bump scheme, the lifted-`.so` build pipeline, the IME-fix and identity reference diffs, the rebase fallback, the deploy targets — lives in the **`simplex-chat-build`** skill. **Read it before running this**, especially its "Per-version update", "Versioning", "Build", and "Deploy" sections. This skill sequences those pieces and adds the simplex-specific decision points: *is the IME fix still needed, and are the rebase conflicts small enough to auto-resolve or significant enough to stop and plan?*

> **SimpleX tracks stable only.** `master` mirrors upstream's default branch (`stable`); the rebase target is the newest **stable** release tag (`vX.Y.Z`), never a `beta`/`rc`/`armv7a` tag. Unlike the keyboard fork, do **not** sync onto pre-releases.

## The one discipline that overrides everything: don't push until the user says "push"

Same governing rule as the rest of this fork (see CLAUDE.md / simplex-chat-build). The entire rebase + build happens on the **local** working tree as a scratchpad. **No `git push` — not `master`, not `custom`, not the snapshot tag — until the user explicitly says "push".** They always verify the build on the phone *first*; the push is a separate step run only on their instruction, never as an automatic continuation of a successful build. A rebase only rewrites local `custom` history and is freely re-runnable (`git rebase --abort`, or reset to `origin/custom`) right up until that point.

Also unconditional, from CLAUDE.md / the build skill: never `adb install` (deploy is `adb push` to `/sdcard/tmp/` — the user installs from the file manager); never install over the F-Droid official SimpleX; never commit the keystore or the Haskell `.so` libs; push to `upstream` is disabled.

## Step 0 — Preconditions

- cwd is the repo (`~/git/shiroikuma-simplex`); remotes are `origin` (SSH, fork — push) and `upstream` (HTTPS, simplex-chat, fetch-only / push-disabled) — confirm with `git remote -v`.
- Working tree clean (`git status --short` empty). If dirty, surface it and ask before proceeding — uncommitted scratch work would be swept into the rebase. (The lifted `.so` libs under `apps/multiplatform/common/src/commonMain/cpp/android/libs/` are gitignored, so they won't show as dirty.)
- On (or able to check out) `custom`.

## Step 1 — Check upstream for a new stable release

```bash
cd ~/git/shiroikuma-simplex
git fetch --tags upstream
git fetch origin            # so origin/master, origin/custom, and origin tags are current for later

# newest STABLE upstream tag (drop armv7a / beta / rc)
new_tag=$(git tag --list 'v*' --sort=-v:refname | grep -vE 'armv7a|beta|rc' | head -1)

# the upstream tag custom is currently rebased onto
old_tag=$(git describe --tags --abbrev=0 \
  --match 'v[0-9]*' --exclude '*armv7a*' --exclude '*beta*' --exclude '*rc*' \
  custom)

echo "custom is based on:        $old_tag"
echo "newest upstream stable:    $new_tag"
```

- **`new_tag == old_tag`** → already on the newest upstream stable tag. Report it ("custom is on `v6.5.3`, the newest upstream stable — nothing to sync") and **stop**.
- **`new_tag` newer than `old_tag`** → continue. First show the user what's coming and the replay count:
  ```bash
  git log --oneline "$old_tag".."$new_tag" | head -50
  git rev-list --count "$old_tag"..custom    # number of our commits to replay (IME fix + identity + tooling)
  ```

(`--sort=-v:refname` orders semver tags correctly; the `grep -vE` keeps this to real stable releases. If a tag's nature is unclear, check the GitHub releases page or ask.)

## Step 2 — Fast-forward `master` (local only; push deferred to "push")

`master` is a pure mirror of upstream's default branch (`stable`), fast-forward only, never carries our changes.

```bash
upstream_head=$(git remote show upstream | sed -n 's/.*HEAD branch: //p')
git checkout master
git merge --ff-only "upstream/$upstream_head"
git checkout custom
```

Do **not** `git push origin master` here — defer it to the "push" step alongside the `custom` push.

## Step 3 — Rebase the `custom` stack onto the new tag

`custom` sits on a *tag*, not on `master`, so rebase **onto the tag**, transplanting only our commits off the old base:

```bash
git rebase --onto "$new_tag" "$old_tag" custom
```

Then triage the outcome.

### Clean rebase → continue to Step 3.5.

A commit may go **empty** and auto-drop — most importantly the IME-fix commit, if upstream fixed the bug or migrated to `BasicTextField` (git sees our patch is already present). That's expected; it's exactly what Step 3.5 detects. Let git skip it (`git rebase --skip` if it pauses on the empty patch).

### Conflicts → decide: "small" vs "significant"

Resolve in place and `git rebase --continue` **only when the conflict is small and the right end state is unambiguous** from the build skill's reference diffs:

- **Identity commit** (`android: shiroikuma.simplex fork identity`) conflicts in `apps/multiplatform/android/build.gradle.kts` because upstream shifted lines around our four edits → re-anchor to the exact end state in simplex-chat-build's **"The identity commit"** table (`applicationId`, `provider_authorities`, the `app_name` placeholder before the **first** `isMinifyEnabled = false`, ABI split → `arm64-v8a` only). Keep our values, take upstream's surrounding changes.
- **IME-fix commit** (`android: fix IME-commit race…`) conflicts in `PlatformTextField.android.kt` because upstream restructured the file **without** fixing the bug → land the same three logical edits documented in simplex-chat-build's **"The IME-fix commit"** diff (re-read `composeState.value` inside the update lambda, the composing-region guard, the `TYPE_TEXT_FLAG_AUTO_CORRECT` flag). Line numbers will have drifted; the three edits are stable.
- Pure context-line shifts where our hunk obviously slots into moved-but-equivalent code; the **tooling commit** (`chore: add Claude Code project config`) conflicting on `CLAUDE.md`/`.claude/` (rare).

**STOP and plan with the user when conflicts are significant** — when in doubt, treat them as significant and ask:

- Upstream **refactored/renamed/migrated** the text-input path so the IME fix no longer maps cleanly (e.g. a partial `BasicTextField` migration that still has the race), or moved the `applicationId`/`splits`/`buildTypes` structure so the identity edits don't re-anchor obviously.
- The **same file conflicts repeatedly** across the replay, or a **semantic** conflict (hunks merge textually but the surrounding API/behaviour changed).
- Any resolution whose correctness isn't obvious from the build skill's reference diffs.

When significant, don't `--abort` silently and don't force a resolution. Gather the picture (`git status` names the replaying commit; `git diff` the hunks; `git log --oneline "$old_tag".."$new_tag" -- <file>` and `git show` for what upstream changed), map it to the IME-fix or identity commit, then **present a plan and ask** (AskUserQuestion, or EnterPlanMode for a multi-commit mess). Typical options: resolve together, re-derive the affected commit from the build skill's reference diffs (the **Fallback** below), drop/defer it, or abort the sync (`git rebase --abort` — the tree returns exactly to where it was; nothing is lost; re-runnable with the same `git rebase --onto`).

### Fallback — regenerate `custom` from the documented edits

When the rebase is too tangled, abort and rebuild the stack on the new tag from the build skill's reference diffs — this produces an identical `custom`:

```bash
git rebase --abort
git checkout -B custom "$new_tag"
# COMMIT 1: re-apply the IME fix as direct edits to PlatformTextField.android.kt
#           (simplex-chat-build → "The IME-fix commit"), git add, commit with the exact message.
# COMMIT 2: re-run the four identity seds (simplex-chat-build → "The identity commit" / one-time setup),
#           commit with the identity message.
# Cherry-pick any further commits that were on custom (e.g. the Claude Code config / tooling commit).
```

If the IME bug is already fixed upstream (Step 3.5), **omit Commit 1** — `custom` becomes identity (+ tooling) only.

## Step 3.5 — Is the IME fix still needed?

Even on a clean rebase, confirm whether upstream now fixes the bug the fork exists for:

```bash
git show "$new_tag:apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt" \
  | grep -A 5 'cs.message.text != it.text.toString()'
```

- **Upstream still has the bug** (the divergence block does the captured-`cs` `it.setText(cs.message.text)`) → our IME-fix commit is still doing real work. Good — proceed.
- **Upstream fixed it** (the block re-reads `composeState.value`, e.g. `val freshCs = composeState.value`, before comparing/`setText`) **or migrated to `BasicTextField`** → our patch is redundant. The `--onto` rebase will have dropped the IME-fix commit as an empty patch (or it conflicted — resolve by dropping it). `custom` becomes the identity commit (+ tooling) only; the fork lives on purely for the side-by-side install. See simplex-chat-build's **"When upstream finally fixes this"** for the full handling and the longer-term options. Flag this to the user — it's a notable change in why the fork exists.

## Step 4 — Verify our customizations survived

```bash
git log --oneline "$new_tag"..custom    # the commits now on top of the new tag
grep -n 'shiroikuma.simplex\|白い熊 SimpleX\|include("arm64-v8a")' apps/multiplatform/android/build.gradle.kts
grep -n 'freshCs\|TYPE_TEXT_FLAG_AUTO_CORRECT'  apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
```

Expect the identity grep to hit `applicationId = "shiroikuma.simplex"`, `app_name = "白い熊 SimpleX"`, and `include("arm64-v8a")`. The IME grep hits only while the fix is still ours (Step 3.5 "still has the bug"); after upstream fixes it, that's empty and expected. The Kotlin/Java namespace stays `chat.simplex.app` — never renamed.

## Step 5 — Build the new `+1` (via simplex-chat-build)

Run the **simplex-chat-build** "Build" pipeline against `$new_tag`: the JDK-21 + Gradle-daemon-flush prelude, lift the Haskell `.so` libs **from the new release's `simplex.apk`** (use `$new_tag` in the release-download URL), write `local.properties`, clean (`./gradlew clean` + `rm -rf` the `build`/`.cxx` dirs), then the "Set the custom build version" step → assemble → revert `gradle.properties` → zipalign → apksigner → verify.

The version is **`<new upstream>+1`** automatically: the build skill's bump detection scans the device + `~/tmp` for prior APKs of *this* upstream version, finds none for a brand-new release, and so resolves `bump=1` (e.g. upstream `6.5.4` → `versionName 6.5.4+1`, `versionCode 3520001`, `shiroikuma-simplex_6.5.4+1_arm64-v8a.apk`). No manual reset needed.

Pause and confirm with the user before kicking off the build (it's 3–8 min). On `BUILD FAILED`, capture the stacktrace and share it before retrying — and if the failure stems from the rebase (not a transient flake or a toolchain bump), treat it like a significant conflict: diagnose and replan rather than patching blindly.

## Step 6 — Deploy, then stop and let the user test

Deploy per the build skill's **"Deploy"** section: `adb push` to `/sdcard/tmp/$apk_name` first (device is the build that matters and the durable bump archive), then `cp` to `~/tmp/$apk_name` as backup. Never `adb install`. If the cable's absent, keep the `~/tmp/` copy and report the adb failure — don't abort.

Then **stop**. The user installs over the previous fork build (same keystore → in-place update, no uninstall) and verifies the customizations on the new base. They may report regressions from the upstream bump → iterate locally (more edits, rebuild a higher `+N`) — still no push. Report the build + deploy result and wait.

## Step 7 — Only when the user says "push"

```bash
git push --force-with-lease origin custom    # rebased stack — history rewritten; lease refuses if origin/custom moved
git push origin master                        # the deferred ff from Step 2 (only if master moved)
git tag -f "shiroikuma-$new_tag"              # snapshot tag for this built release
git push -f origin "shiroikuma-$new_tag"
```

`--force-with-lease` (never bare `--force`) so a surprise update to `origin/custom` aborts instead of clobbering. If the build failed there is nothing to push; and even on success, only push after the user has verified on the phone **and** told you to.

After pushing, if the sync taught something durable (a new conflict-resolution learning, a structural change in how upstream lays out the text-input path or build script, the IME bug finally being fixed upstream), record it in **simplex-chat-build**'s relevant section so the next sync benefits.

## Reference — conflict watch-points (condensed from simplex-chat-build)

- **IME-fix commit** → `apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt`. The three logical edits and the authoritative diff are in simplex-chat-build → "The IME-fix commit".
- **Identity commit** → `apps/multiplatform/android/build.gradle.kts`. The four edits (and the "first `isMinifyEnabled` only" caveat) are in simplex-chat-build → "The identity commit".
- **Versioning** lives in `apps/multiplatform/gradle.properties` (`android.version_name`/`android.version_code`) — **injected at build time, reverted after, never committed**. The identity commit must not touch them.
- The Haskell `.so` libs are **never committed** — lifted from the official APK at build time into a gitignored path.

## Related skills

- **`simplex-chat-build`** — the canonical skill this one orchestrates: project identity, remotes/branch model, the full lift-`.so` + build + sign + deploy pipeline, the versioning/bump scheme, the IME-fix and identity reference diffs, the rebase fallback, and "When upstream finally fixes this". Read it first; this skill only sequences it and adds the sync decision points.

---
name: simplex-chat-build
description: Maintain and build the user's downstream fork of SimpleX Chat for Android (`ShiroiKuma0/simplex-chat`, package `shiroikuma.simplex`, installable side-by-side with the official SimpleX from F-Droid). The fork is maintained as commits on a `custom` branch, rebased onto each new upstream release tag. Downstream commits are an IME-commit-race fix in PlatformTextField.android.kt (breaks dictionary keyboards like Multiling O / Czech autocorrect-on-space / CJK candidate selection) plus four identity customizations (applicationId, provider authority, app_name, ABI splits). Use this skill any time the conversation involves SimpleX Chat for Android — pulling/rebasing a new upstream version, checking whether the IME fix is still needed, rebuilding the APK, deploying to the user's phone, or discussing the IME bug itself. Default to assuming this skill applies when in doubt.
---

# SimpleX Chat — fork build skill

The user maintains a downstream fork of [simplex-chat](https://github.com/simplex-chat/simplex-chat) for Android, used on a Huawei Mate XT alongside the official F-Droid build. The fork follows the user's standard `master`/`custom` model: a clean `master` mirroring upstream and a `custom` branch holding all downstream changes as commits, rebased onto each new upstream release tag.

## Project identity

| Item | Value |
|------|-------|
| Upstream repo | `simplex-chat/simplex-chat` |
| User's fork | `ShiroiKuma0/simplex-chat` |
| Local working tree | `~/git/shiroikuma-simplex` |
| `origin` (push) | `git@github.com:ShiroiKuma0/simplex-chat.git` (SSH) |
| `upstream` (fetch only) | `https://github.com/simplex-chat/simplex-chat.git` (push disabled via `DISABLE_PUSH_TO_UPSTREAM` sentinel URL) |
| Mirror branch | `master` — ff-only from upstream's default branch (`stable`) |
| Work branch | `custom` — downstream commits, rebased onto each new release tag |
| Snapshot tags | `shiroikuma-v<UPSTREAM_VERSION>` (e.g. `shiroikuma-v6.5.2`) |
| Android `applicationId` | `shiroikuma.simplex` |
| App display name | `白い熊 SimpleX` |
| Kotlin/Java namespace (unchanged) | `chat.simplex.app` — do NOT rename, it's baked into thousands of `package` declarations |
| Signing keystore | `~/.android-keystores/simplex-custom.jks` (alias `simplex`, passphrase `simplex123`) |
| APK output | `~/tmp/shiroikuma-simplex_<UPSTREAM>+<BUMP>_arm64-v8a.apk` (e.g. `shiroikuma-simplex_6.5.3+1_arm64-v8a.apk`) |
| Build version | `versionName = <UPSTREAM>+<BUMP>`, `versionCode = <upstream code>×10000 + BUMP` — see "Versioning" |
| On-device deploy | `/sdcard/tmp/` (via `adb push`, install with phone file manager) |
| Build host | Tuxedo OS |
| Target ABI | `arm64-v8a` only |
| Build JDK | OpenJDK 21 at `/usr/lib/jvm/java-21-openjdk-amd64` |
| Android SDK | `~/android-sdk`, platform-35 + build-tools-35.0.0 |

## Branch model

- **`master`** mirrors upstream's default branch (`stable`). Fast-forward only. Never carries downstream changes. Its only purpose is to provide a clean reference for comparison and rebase targeting.
- **`custom`** carries the downstream commits, sitting on top of an upstream release tag:
  1. `android: fix IME-commit race in PlatformTextField` — three changes documented under "The IME-fix commit" below.
  2. `android: shiroikuma.simplex fork identity` — four identity customizations under "The identity commit" below.
  3. Optionally: a tooling commit (`chore: add Claude Code project config`) carrying `CLAUDE.md` + `.claude/skills/` so this skill travels with the repo.
- `origin` is the user's fork (SSH push); `upstream` is the canonical repo (HTTPS, push disabled).
- The Haskell core `.so` libraries are **not** committed — they're lifted from the official release APK at build time. Keep them out of git.

## Why the fork exists — the IME bug

Upstream `apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt` syncs `composeState` → `EditText` inside the `AndroidView` update lambda using a `cs` value captured at composition start. When an IME commits text (tapping a suggestion, autocorrect-on-space, Multiling O dictionary commits, CJK candidate selection), the sequence is:

1. IME calls `IC.commitText("word", 1)`. EditText's text becomes `"word"`.
2. `doOnTextChanged` fires, calls `onMessageChange("word")`, which writes `composeState.value`.
3. The `AndroidView` update lambda runs. Its captured `cs` reflects the snapshot from *before* step 2 (Compose snapshot semantics defer state writes until next composition).
4. `cs.message.text` is pre-commit; `it.text.toString()` is post-commit. They differ, so the lambda calls `setText(cs.message.text)`, **wiping the IME's commit**.
5. The IME, seeing its commit undone, abandons the post-commit space and sometimes tears down the InputConnection.

Android analogue of upstream PR [#4045](https://github.com/simplex-chat/simplex-chat/pull/4045) ("ios: fix typing using keyboard suggestions"). The iOS fix gated on `markedTextRange == nil`; Android's composing region clears as part of `commitText`, so a composing-region check alone isn't enough.

**The fix:** re-read `composeState.value` *inside* the update lambda instead of trusting captured `cs`. The fresh read sees what `onMessageChange` just wrote, so the divergence check correctly returns "no actual divergence" and skips the destructive `setText`. Plus a defensive composing-region check for active multi-step compositions, and `TYPE_TEXT_FLAG_AUTO_CORRECT` on the inputType.

## One-time setup

Run once to establish the model. Assumes the local checkout already exists at `~/git/shiroikuma-simplex`. Skip on subsequent rebuilds.

**Step 1 (manual, on GitHub):** click Fork on `simplex-chat/simplex-chat` to create `ShiroiKuma0/simplex-chat`. Do not tick "Copy the main branch only" — keep all branches and tags.

**Step 2 (in `~/git/shiroikuma-simplex`):**

Set the base tag to the current stable upstream release, then:

```bash
# Clean tree
git checkout -- .
git clean -fd apps/multiplatform/common/src/commonMain/cpp/android/libs

# Remotes
git remote set-url origin git@github.com:ShiroiKuma0/simplex-chat.git 2>/dev/null \
  || git remote add origin git@github.com:ShiroiKuma0/simplex-chat.git
git remote remove upstream 2>/dev/null || true
git remote add upstream https://github.com/simplex-chat/simplex-chat.git
git remote set-url --push upstream DISABLE_PUSH_TO_UPSTREAM
git fetch --tags upstream
git fetch origin

# master mirrors upstream's default branch
upstream_head=$(git remote show upstream | sed -n 's/.*HEAD branch: //p')
git checkout -B master "upstream/$upstream_head"
git branch --set-upstream-to="upstream/$upstream_head" master

# custom off the base tag
base_tag="v6.5.2"  # adjust to current stable
git checkout -B custom "$base_tag"
```

**Commit 1 — IME fix.** Make the three changes documented under "The IME-fix commit" below directly to `apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt`, then stage and commit. (The diff in that reference section is the source of truth for what changes; line numbers will drift in newer upstream versions, but the three logical edits stay the same.)

```bash
git add apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
```

Commit message (use exactly):
```
android: fix IME-commit race in PlatformTextField

The AndroidView update lambda used the `cs` value captured from the
@Composable's body, which reflects the snapshot at composition start.
When the IME commits text via doOnTextChanged -> onMessageChange (which
writes a NEW composeState value), the captured `cs` does not reflect
that value (Compose snapshot semantics). The lambda's divergence check
then sees `cs != it.text`, calls setText(cs.message.text), and wipes
the IME's commit -- breaking dictionary-based keyboards (Multiling O,
Czech autocorrect-on-space, CJK candidate selection).

Re-read composeState.value inside the update lambda; the divergence
check then correctly identifies whether state really differs from
EditText, or whether `cs` was just stale. Also adds a defensive
composing-region guard for active multi-step compositions, and the
TYPE_TEXT_FLAG_AUTO_CORRECT inputType flag.

Android analogue of upstream PR #4045 ("ios: fix typing using
keyboard suggestions").
```

**Commit 2 — identity customizations.** Four edits to `apps/multiplatform/android/build.gradle.kts` (see "The identity commit" for the exact targets):

```bash
sed -i 's|applicationId = "chat.simplex.app"|applicationId = "shiroikuma.simplex"|' apps/multiplatform/android/build.gradle.kts
sed -i 's|manifestPlaceholders\["provider_authorities"\] = "chat.simplex.app.provider"|manifestPlaceholders["provider_authorities"] = "shiroikuma.simplex.provider"|' apps/multiplatform/android/build.gradle.kts
sed -i '0,/isMinifyEnabled = false/{s|isMinifyEnabled = false|manifestPlaceholders["app_name"] = "白い熊 SimpleX"\n            isMinifyEnabled = false|}' apps/multiplatform/android/build.gradle.kts
sed -i 's/include(.*/include("arm64-v8a")/' apps/multiplatform/android/build.gradle.kts
git add apps/multiplatform/android/build.gradle.kts
```

Commit message:
```
android: shiroikuma.simplex fork identity

Renames applicationId to shiroikuma.simplex (installable side-by-side
with official SimpleX from F-Droid). Renames provider_authorities to
match. Java/Kotlin namespace (chat.simplex.app) stays unchanged so
package declarations in source files don't break.

Sets app_name to "白い熊 SimpleX" in the release block (launcher
distinguishability). Scoped to the FIRST isMinifyEnabled -- v6.5.x has
a redundant second buildTypes block we don't touch.

Restricts ABI splits to arm64-v8a only (drop armeabi-v7a, not used on
the target device; also avoids a CMake-for-v7a step that fails because
we don't lift v7a Haskell libs from the official APK).
```

**Push and tag:**

```bash
git push -u origin master
git push -u origin custom
git tag -f "shiroikuma-$base_tag"
git push -f origin "shiroikuma-$base_tag"
```

`-f` on tag create + push makes the operation idempotent across re-runs.

## Per-version update

When upstream releases a new version, advance the mirror and rebase `custom` onto the new tag. The base tag is detected dynamically via `git describe`, so the procedure is robust regardless of how many commits are on `custom`.

**Identify versions and check whether the fix is still needed:**

```bash
git fetch --tags upstream
git fetch origin

new_tag=$(git tag --list 'v*' --sort=-v:refname | grep -vE 'armv7a|beta|rc' | head -1)
old_tag=$(git describe --tags --abbrev=0 \
  --match 'v[0-9]*' --exclude '*armv7a*' --exclude '*beta*' --exclude '*rc*' \
  custom)

# Detection: does the new upstream still have the bug?
git show "$new_tag:apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt" \
  | grep -A 5 'cs.message.text != it.text.toString()'
```

In the grep output, look at the body of the divergence check. **Upstream has fixed the bug** if the block re-reads `composeState.value` (e.g. `val freshCs = composeState.value`) and uses that for the comparison/setText, OR if the file has been migrated to Compose's `BasicTextField` (the maintainer's stated long-term plan). **Upstream still has the bug** if the block does the captured-`cs` `it.setText(cs.message.text)`. If fixed upstream, see "When upstream finally fixes this" below.

**Advance master mirror (fast-forward only):**

```bash
upstream_head=$(git remote show upstream | sed -n 's/.*HEAD branch: //p')
git checkout master
git merge --ff-only "upstream/$upstream_head"
```

**Rebase `custom` onto the new tag:**

```bash
git checkout custom
git rebase --onto "$new_tag" "$old_tag" custom
```

On conflict (usually `PlatformTextField.android.kt` if upstream restructured the file but didn't fix the bug, or `build.gradle.kts` if identity-edit lines shifted): edit the conflicted file to land the same end state documented under "The IME-fix commit" or "The identity commit" below, `git add`, `git rebase --continue`. If conflicts are too messy, abort and use the fallback below.

**Fallback — regenerate commits from documented edits:**

```bash
git rebase --abort
git checkout -B custom "$new_tag"
# COMMIT 1: re-apply IME fix as direct edits to PlatformTextField.android.kt,
#           following the diff under "The IME-fix commit" below.
git add apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
git commit -m "android: fix IME-commit race in PlatformTextField" # full message as in setup
# COMMIT 2: re-run the four identity seds (from setup), then commit with the identity message
# Re-apply any additional commits that were on custom (e.g. Claude Code config) by cherry-picking
```

This produces an identical `custom`.

**Verify after rebase, before building:**

```bash
git log --oneline "$new_tag"..custom   # commits on top of new tag
grep -n 'shiroikuma.simplex\|白い熊 SimpleX\|include("arm64-v8a")' apps/multiplatform/android/build.gradle.kts
grep -n 'freshCs\|TYPE_TEXT_FLAG_AUTO_CORRECT' apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
```

## Versioning

Every custom build's version is derived from the upstream release it was built from, plus a **build bump** that increments on each build — so custom builds install over each other (monotonic `versionCode`) and read as distinct from upstream.

- **`versionName`** = `<upstream versionName>+<BUMP>` — upstream `6.5.3` → `6.5.3+1`, `6.5.3+2`, …
- **`versionCode`** = `<upstream versionCode> × 10000 + BUMP` — upstream `351` → `3510001`, `3510002`, …. The `×10000` leaves room for 9999 custom builds per upstream version; the next upstream code (`352` → `3520001`) always sorts above.
- **`BUMP`** = **1** for the first build after a rebase onto a new upstream tag, +1 for each rebuild on the same upstream base.
- **APK filename** = `shiroikuma-simplex_<versionName>_arm64-v8a.apk` — e.g. `shiroikuma-simplex_6.5.3+1_arm64-v8a.apk`. No timestamp; `+BUMP` is the unique discriminator.

Upstream's `versionName`/`versionCode` are the committed values in `apps/multiplatform/gradle.properties` (`android.version_name` / `android.version_code`) — the identity commit does **not** touch them. The custom version is injected at **build time** and reverted afterward (never committed, since it changes every build). The derivation + injection is the "Set the custom build version" build step below.

## Build

The build lifts the Haskell core `.so` libraries from the official release APK rather than rebuilding the Nix-based Haskell core from source. The libraries are not committed; they go into the gitignored target path.

**Required environment** (see "Build environment requirements" below for why this matters every time):

```bash
pkill -f '[G]radleDaemon' || true   # bracket avoids matching the wrapper shell when run non-interactively
sleep 1
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"        # local.properties has no sdk.dir; non-interactive shells don't inherit it
export ANDROID_SDK_ROOT="$HOME/android-sdk"
# Bump the Gradle daemon heap BEFORE the first ./gradlew call: assembleRelease OOMs at
# the committed -Xmx2048m while deflating the ~192MB libsimplex.so at compression.level=9
# (java.lang.OutOfMemoryError in zipflinger Compressor.deflate). Editing it pre-daemon
# means the daemon born at `./gradlew clean` already carries 6g; the host has ~93GiB RAM.
# Reverted later with the injected version via `git checkout gradle.properties`.
sed -i -E 's/-Xmx[0-9]+[mg]/-Xmx6g/' apps/multiplatform/gradle.properties
java -version  # confirm 21
```

**Lift the Haskell `.so` libraries from the official APK:**

```bash
rm -rf /tmp/sx-libs && mkdir -p /tmp/sx-libs
curl -L -o /tmp/sx-libs/simplex.apk \
  "https://github.com/simplex-chat/simplex-chat/releases/download/${new_tag}/simplex.apk"
unzip -o /tmp/sx-libs/simplex.apk "lib/arm64-v8a/*" -d /tmp/sx-libs/
mkdir -p apps/multiplatform/common/src/commonMain/cpp/android/libs/arm64-v8a
cp /tmp/sx-libs/lib/arm64-v8a/libsimplex.so \
   /tmp/sx-libs/lib/arm64-v8a/libsupport.so \
   apps/multiplatform/common/src/commonMain/cpp/android/libs/arm64-v8a/
```

**Local properties and clean state:**

```bash
cat > apps/multiplatform/local.properties <<'EOF'
abi_filter=arm64-v8a
compression.level=9
enable_debuggable=false
EOF

(cd apps/multiplatform && ./gradlew clean)
rm -rf apps/multiplatform/android/build apps/multiplatform/common/build apps/multiplatform/android/.cxx
```

The clean + `rm -rf` is essential — stale `.cxx` state from prior builds (especially with armeabi-v7a artifacts) causes `IncrementalSplitterRunnable` failures in `packageRelease`.

**Set the custom build version** (per "Versioning"). Compute the bump and inject into `gradle.properties` — reverted right after the build, never committed:

```bash
upstream_vname=$(sed -n 's/^android.version_name=//p' apps/multiplatform/gradle.properties)  # e.g. 6.5.3
upstream_vcode=$(sed -n 's/^android.version_code=//p' apps/multiplatform/gradle.properties)  # e.g. 351
# Highest existing bump for this upstream version. The phone's /sdcard/tmp is the durable
# archive (every build is pushed there and they accumulate); ~/tmp is a local mirror that
# can be cleaned out. Take the max across both so the new versionCode always exceeds
# whatever is already staged/installed. (Offline with a cleaned ~/tmp: verify bump by hand.)
prev=$( { adb shell ls /sdcard/tmp/ 2>/dev/null; ls ~/tmp/ 2>/dev/null; } \
        | grep -oE "shiroikuma-simplex_${upstream_vname}\+[0-9]+_arm64-v8a\.apk" \
        | sed -E 's|.*\+([0-9]+)_.*|\1|' | sort -n | tail -1 )
bump=$(( ${prev:-0} + 1 ))                          # first build after a rebase -> 1
custom_vname="${upstream_vname}+${bump}"            # e.g. 6.5.3+1
custom_vcode=$(( upstream_vcode * 10000 + bump ))   # e.g. 3510001
apk_name="shiroikuma-simplex_${custom_vname}_arm64-v8a.apk"

sed -i "s/^android.version_name=.*/android.version_name=${custom_vname}/" apps/multiplatform/gradle.properties
sed -i "s/^android.version_code=.*/android.version_code=${custom_vcode}/" apps/multiplatform/gradle.properties
```

**Build, sign, verify:**

```bash
(cd apps/multiplatform && ./gradlew --stacktrace :android:assembleRelease)
git checkout apps/multiplatform/gradle.properties   # revert the injected version + heap bump (keep them out of git)
grep -E '"versionCode"|"versionName"' apps/multiplatform/android/build/outputs/apk/release/output-metadata.json  # sanity-check the baked version

unsigned_apk="apps/multiplatform/android/build/outputs/apk/release/android-arm64-v8a-release-unsigned.apk"
zipalign -p -f 4 "$unsigned_apk" /tmp/sx-aligned.apk
apksigner sign \
  --ks ~/.android-keystores/simplex-custom.jks \
  --ks-key-alias simplex \
  --ks-pass pass:simplex123 \
  --key-pass pass:simplex123 \
  --out /tmp/sx-signed.apk \
  /tmp/sx-aligned.apk
apksigner verify --verbose /tmp/sx-signed.apk
```

Build takes 3-8 minutes after the gradle cache warms up. Pause and confirm with the user before kicking it off; on a failure, capture the stacktrace and share it before retrying.

**Deploy** — see "Deploy" section — then **stop**. Do not push anything yet.

**Push only when the user explicitly tells you to.** After the build is deployed, the user installs and verifies it on the phone *first*; the push + tag are a **separate step, run on their instruction** (e.g. they say "push"). Never push as an automatic continuation of the build — not even on a fully successful, clean-looking build. Until the user says so, `custom`, `master`, and the snapshot tag stay **local**. Report the build + deploy result and wait.

When the user says to push:

```bash
git push --force-with-lease origin custom
git push origin master                     # only if master moved this round
git tag -f "shiroikuma-$new_tag"
git push -f origin "shiroikuma-$new_tag"
```

If the build fails there is nothing to push. And even on success, do not push until the build is verified to work **and** the user authorizes it — the rebase/changes and snapshot tag stay local until then.

## Build environment requirements

The Gradle daemon is keyed on the JVM that started it. Setting `JAVA_HOME` does **not** retire an already-running daemon bound to a different JVM — the next `./gradlew` reuses the existing daemon and inherits its (possibly wrong) JVM. So the daemon must be killed before any export:

```bash
pkill -f '[G]radleDaemon' || true
sleep 1
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$HOME/android-sdk"
java -version
```

`|| true` swallows pkill's exit-1 when no daemons are running (normal case). The `sleep 1` lets a daemon mid-task flush before the next gradle command spawns a fresh one.

This matters specifically because the user has multiple JDKs installed (Tuxedo OS defaults to JDK 21 system-wide, but their other build project — the Flutter-based 白い熊の辞書 fork — pins Gradle 7.2 + Zulu 11 at `/usr/lib/jvm/zulu11`). Shell sessions cross-pollinate `JAVA_HOME` and daemons; without the prelude, building SimpleX shortly after that project can silently reuse a Zulu 11 daemon and fail with AGP class-version mismatches.

### Gradle heap: assembleRelease OOMs at the committed `-Xmx2048m`

`apps/multiplatform/gradle.properties` commits `org.gradle.jvmargs=-Xmx2048m`. That is not enough to package a release. `local.properties` sets `compression.level=9`, and zipflinger builds the whole deflated entry in memory — for the ~192MB `libsimplex.so` (the lifted Haskell core) at max compression it throws `java.lang.OutOfMemoryError: Java heap space` in `Compressor.deflate` during `:android:assembleRelease`. A warm daemon inherited from another project with a bigger heap can mask it, so it surfaces intermittently (it bit the 6.5.4+2 build on a cold daemon).

The prelude bumps the heap to `-Xmx6g` **before** the first `./gradlew` call, so the daemon is born with the larger heap. Bumping it *after* `./gradlew clean` doesn't help — the already-started 2g daemon is reused for `assembleRelease` and still OOMs unless you `pkill` and respawn. The host has ~93GiB RAM, so 6g is comfortable. The edit lives in the same `gradle.properties` as the injected build version, so the post-build `git checkout apps/multiplatform/gradle.properties` reverts both — it never lands in git.

## Deploy: copy to `~/tmp/`, then `/after-build`

Every successful build delivers in two steps, in this fixed order:

1. **Local backup first** via `cp /tmp/sx-signed.apk ~/tmp/"$apk_name"` (`apk_name` from "Versioning", e.g. `shiroikuma-simplex_6.5.3+1_arm64-v8a.apk`) — this is the backup every delivery keys off, so it always happens.
2. **Deliver via the global `/after-build` skill**: it runs `/adb-check` UNSANDBOXED, then `/adb-push` to `/sdcard/tmp/` if the phone is connected (the user installs from `/sdcard/tmp/` via the phone's file manager), else `/scp` to skhw — announcing the filename, without asking. Never prompt "is the phone connected?" — `/adb-check` answers it. Never use `adb install` instead of `adb push` — the user wants the APK file landing on device storage to install via the file manager.

Local copy first because it's the fixed artifact `/after-build` reads. If no device is connected, `/after-build` falls back to `/scp` to skhw automatically; the `~/tmp/` copy is also there to transfer via KDE Connect / Bluetooth / file copy. Don't abort the build on a missing-cable moment.

Updates over an existing custom build install cleanly without uninstall (same keystore). **Never** install over the F-Droid official SimpleX — different signing keys, Android refuses. They coexist because `applicationId` differs.

## The IME-fix commit

Commit on `custom`. Three changes to `apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt`:

1. **`TYPE_TEXT_FLAG_AUTO_CORRECT`** added to `inputType`. Chat input fields should declare autocorrect support.
2. **Composing-region guard** in `doOnTextChanged`'s `inProgress` branch — `setText` mid-IME-composition destroys the composing region and confuses dictionary IMEs.
3. **The actual fix** in the `AndroidView` `update` lambda: re-read `composeState.value` to get the post-`onMessageChange` value, plus an `isComposing` guard.

The diff against `v6.4.11` (commit `462e47ba`) is reproduced below as the authoritative reference for what the commit changes. Line numbers will drift in newer upstream versions; the three logical edits are stable.

```diff
diff --git a/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt b/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
index 4f48ccca..0883f564 100644
--- a/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
+++ b/apps/multiplatform/common/src/androidMain/kotlin/chat/simplex/common/platform/PlatformTextField.android.kt
@@ -129,7 +129,7 @@ actual fun PlatformTextField(
     }
     editText.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
     editText.maxLines = 16
-    editText.inputType = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or editText.inputType
+    editText.inputType = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT or editText.inputType
     editText.setTextColor(textColor.toArgb())
     editText.textSize = textStyle.value.fontSize.value * appPrefs.fontScale.get()
     editText.background = ColorDrawable(Color.Transparent.toArgb())
@@ -161,8 +161,15 @@ actual fun PlatformTextField(
       if (!composeState.value.inProgress) {
         onMessageChange(ComposeMessage(text.toString(), TextRange(minOf(editText.selectionStart, editText.selectionEnd), maxOf(editText.selectionStart, editText.selectionEnd))))
       } else if (text.toString() != composeState.value.message.text) {
-        editText.setText(composeState.value.message.text)
-        editText.setSelection(composeState.value.message.selection.start, composeState.value.message.selection.end)
+        // Don't break an active IME composition (autocorrect, swipe-typing, dictionary lookup).
+        val ed = editText.editableText
+        val cStart = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(ed)
+        val cEnd = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(ed)
+        val isComposing = cStart >= 0 && cEnd >= 0 && cStart != cEnd
+        if (!isComposing) {
+          editText.setText(composeState.value.message.text)
+          editText.setSelection(composeState.value.message.selection.start, composeState.value.message.selection.end)
+        }
       }
     }
     editText.doAfterTextChanged { text -> if (composeState.value.preview is ComposePreview.VoicePreview && text.toString() != "") editText.setText("") }
@@ -179,8 +186,28 @@ actual fun PlatformTextField(
     it.isFocusable = composeState.value.preview !is ComposePreview.VoicePreview
     it.isFocusableInTouchMode = it.isFocusable
     if (cs.message.text != it.text.toString() || cs.message.selection.start != it.selectionStart || cs.message.selection.end != it.selectionEnd) {
-      it.setText(cs.message.text)
-      it.setSelection(cs.message.selection.start, cs.message.selection.end)
+      // The outer `cs` is captured from the @Composable's body and reflects the snapshot
+      // at composition start. Between then and now, the IME may have committed new text
+      // via doOnTextChanged -> onMessageChange, which writes a NEW composeState value.
+      // The captured `cs` does not reflect that newer value (Compose snapshot semantics).
+      // Re-read composeState.value here to see the freshest state. If it already matches
+      // the EditText, the captured cs was stale -- do nothing, otherwise we'd undo the
+      // IME's commit (e.g. wipe a tapped suggestion or autocorrect-on-space replacement).
+      //
+      // Also guard against an active IME composition: setText/setSelection mid-composition
+      // destroys the composing region and confuses dictionary-based IMEs.
+      val freshCs = composeState.value
+      val freshDiverges = freshCs.message.text != it.text.toString()
+        || freshCs.message.selection.start != it.selectionStart
+        || freshCs.message.selection.end != it.selectionEnd
+      val ed = it.editableText
+      val cStart = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(ed)
+      val cEnd = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(ed)
+      val isComposing = cStart >= 0 && cEnd >= 0 && cStart != cEnd
+      if (freshDiverges && !isComposing) {
+        it.setText(freshCs.message.text)
+        it.setSelection(freshCs.message.selection.start, freshCs.message.selection.end)
+      }
     }
     if (showKeyboard) {
       it.requestFocus()
```

## The identity commit

Commit on `custom`. Four changes to `apps/multiplatform/android/build.gradle.kts`:

| What | From | To |
|---|---|---|
| `applicationId` | `"chat.simplex.app"` | `"shiroikuma.simplex"` |
| `provider_authorities` placeholder | `"chat.simplex.app.provider"` | `"shiroikuma.simplex.provider"` |
| `app_name` placeholder | (not set in release block) | `"白い熊 SimpleX"` — added before the **first** `isMinifyEnabled = false` only; the second `buildTypes { getByName("release") { ... } }` block in v6.5.x is redundant and not touched |
| `splits.abi.include(...)` | `("arm64-v8a", "armeabi-v7a")` | `("arm64-v8a")` — both occurrences |

Java/Kotlin `namespace` stays `chat.simplex.app` — do NOT rename it, it's baked into thousands of `package` declarations.

FileProvider authorities must be globally unique on-device. Kotlin code references the provider as `"$APPLICATION_ID.provider"` so the rename stays internally consistent.

## When upstream finally fixes this

When detection shows upstream now re-reads `composeState.value` or migrated to `BasicTextField`, the next `git rebase --onto` will either:

- **Drop the IME-fix commit automatically** (empty patch — git sees it's already upstream). `custom` ends up with just the identity commit (and any tooling commits). The `git describe` line still finds the right base. Build as normal — the fork still serves the side-by-side install.
- **Conflict**, if upstream's fix uses different mechanics. Abort, then regenerate `custom` from `new_tag` with only the identity commit (and tooling commits):
  ```bash
  git rebase --abort
  git checkout -B custom "$new_tag"
  # re-run the four identity seds, then commit (identity commit only)
  # cherry-pick any tooling commits if needed
  ```

After the fix lands upstream, options: keep the fork for the side-by-side install (identity commit + tooling only), or migrate back to F-Droid SimpleX (export chat archive → uninstall custom → install F-Droid → import archive; then delete `custom` and the `upstream` remote).

---

**Commit convention — no Claude attribution.** Never add a `Co-Authored-By: Claude …` / "Generated with Claude" trailer to commit messages or PR bodies; end the message at the last line of the body. This overrides the harness default. (Global rule: `~/.claude/CLAUDE.md`.)

<div align="center">

<img src="apps/multiplatform/android/src/main/res/mipmap-xxhdpi/icon.png" width="120" alt="白い熊 SimpleX icon" />

# 白い熊 SimpleX

**SimpleX Chat for Android that plays nice with dictionary keyboards — restyled black-yellow, customizable down to the tick.**

A fork of [SimpleX Chat](https://github.com/simplex-chat/simplex-chat) with **major additions**: an IME-commit-race fix that makes dictionary keyboards (Multiling O, Czech autocorrect, CJK input) work in the message field, the **白い熊 Simplex UI** page — every color, font, bubble, and tick in the app settable in one place, with export/import of the whole configuration **including your accounts**, backed up headlessly on request by an automation app — and a black-yellow fork identity.

Installs **side-by-side** with official SimpleX Chat (app id `shiroikuma.simplex`).

**📥 Latest release: [`7.0-beta.6+3`](https://github.com/ShiroiKuma0/simplex-chat/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/simplex-chat/releases)

</div>

---

## ⌨️ Dictionary keyboards actually work

Stock SimpleX for Android has a Compose snapshot race in its message field: when an IME *commits* text — tapping a suggestion, autocorrect-on-space, selecting a CJK candidate — the app's own state sync fires with a stale value and **wipes the keyboard's commit**, so dictionary-based keyboards (Multiling O and friends) constantly lose words. This fork re-reads the fresh compose state inside the update pass, guards active IME compositions, and declares `TYPE_TEXT_FLAG_AUTO_CORRECT` — typing with a real dictionary keyboard just works. (Android analogue of upstream's iOS-only fix in PR [#4045](https://github.com/simplex-chat/simplex-chat/pull/4045); the bug is still present upstream.)

---

## 🎨 The 白い熊 Simplex UI page

One settings page (first item in Settings; also on long-press of the cog, your avatar, the chat menu, or the new-chat button) that makes the whole app yours: global background/text/accent/secondary colors applied after theme resolution, an external font (`.ttf`/`.otf`) replacing Inter app-wide with a size slider, chat-list name color and avatar roundness, per-direction bubble background/text/border colors with border width, corner roundness, tail toggle and sender-row sizing, date-header styling, call-icon scale — every control with a live preview, colors picked in an RGBA slider editor with one-tap recent-color presets. Styled kxkb-fashion: bold yellow headings underlined exactly as wide as their text, sections split by hairline rules.

---

## 📤 Export / Import every setting — accounts included

The top of the UI page: pick an export directory once and it shows your latest export whenever the page opens. Export writes a ZIP (manifest + one JSON per category + your font files) one-tap into that directory; import restores any selection of categories — **Accounts**, App colors, Font, Chat list, Chat bubbles, Chat view, Delivery ticks — from any exported archive, with a restart-now option to apply everything. The Accounts category embeds the full SimpleX chat-database archive (all profiles, contacts, and messages), making one export a complete portable backup; importing it replaces the database after an explicit confirmation.

---

## 🤖 Backed up on command, without touching the phone

The same export runs **headlessly** when an automation task asks for it. A token-gated broadcast — the token lives on the UI page, is copied with a tap, and is regenerable — makes the app write exactly one ZIP wherever the caller names, report progress in real counts (`区分 3/8 — Chat bubbles`, or `512 MB / 4.2 GB` while streaming the chat database), and reply with the written path and its exact size. Categories are selectable by id, down to sub-options such as the font files alone. Off by default; nothing responds until the switch is on.

---

## ✔️ Customizable delivery ticks

Scale the sent/received checkmarks from 1× to 15×, set their stroke thickness, and give sent and received ticks their own colors (defaults: light-blue sent, theme-yellow received). The ticks are redrawn as true vector strokes on a Canvas, so they stay crisp at any size instead of plateauing at the stock icon's intrinsic limit.

---

## 🖤💛 Black-yellow identity

The launcher icon is restyled into the fork's black/yellow scheme — the SimpleX hashmark traced in yellow on black, faithful to upstream's geometry — and the app presents as **白い熊 SimpleX**, so it's unmistakable next to the official install.

---

## 📦 Tracks upstream fast, ships lean

The fork rebases onto every new upstream release — **betas included** — usually days after it ships. APKs are `arm64-v8a` only and versioned `<upstream>+<build>` (e.g. `7.0-beta.6+3`), with version codes that always sort above the corresponding upstream build.

---

## Built on SimpleX Chat

A fork of [SimpleX Chat](https://github.com/simplex-chat/simplex-chat) — the first messaging platform with no user identifiers of any kind (app id `shiroikuma.simplex`, so it coexists with the official build). All credit for the messenger itself, its protocol, and its privacy design goes to the SimpleX Chat team. The code remains under [AGPL-3.0](https://github.com/simplex-chat/simplex-chat/blob/stable/LICENSE).

## Building

```bash
git clone git@github.com:ShiroiKuma0/simplex-chat.git
cd simplex-chat   # branch: custom

# Lift the Haskell core libraries from the official release APK (they are not committed):
curl -L -o /tmp/simplex.apk \
  "https://github.com/simplex-chat/simplex-chat/releases/download/<upstream-tag>/simplex.apk"
unzip -o /tmp/simplex.apk "lib/arm64-v8a/*" -d /tmp/sx-libs/
mkdir -p apps/multiplatform/common/src/commonMain/cpp/android/libs/arm64-v8a
cp /tmp/sx-libs/lib/arm64-v8a/libsimplex.so /tmp/sx-libs/lib/arm64-v8a/libsupport.so \
   apps/multiplatform/common/src/commonMain/cpp/android/libs/arm64-v8a/

# Build (JDK 21, Android SDK platform 35):
printf 'abi_filter=arm64-v8a\ncompression.level=9\nenable_debuggable=false\n' \
  > apps/multiplatform/local.properties
(cd apps/multiplatform && ./gradlew :android:assembleRelease)
# then zipalign + apksigner with your own keystore
```

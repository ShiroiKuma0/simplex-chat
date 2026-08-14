<div align="center">

<img src="apps/multiplatform/android/src/main/res/mipmap-xxhdpi/icon.png" width="120" alt="白い熊 SimpleX icon" />

# 白い熊 SimpleX

**SimpleX Chat for Android that plays nice with dictionary keyboards — restyled black-yellow, customizable down to the tick.**

A fork of [SimpleX Chat](https://github.com/simplex-chat/simplex-chat) with **major additions**: an IME-commit-race fix that makes dictionary keyboards (Multiling O, Czech autocorrect, CJK input) work in the message field, the **白い熊 Simplex UI** page — every color, font, bubble, and tick in the app settable in one place, with export/import of the whole configuration **including your accounts**, backed up headlessly on request by an automation app — and a black-yellow fork identity.

Installs **side-by-side** with official SimpleX Chat (app id `shiroikuma.simplex`).

**📥 Latest release: [`7.1-beta.0+1`](https://github.com/ShiroiKuma0/simplex-chat/releases/latest)** — [all releases & APK downloads »](https://github.com/ShiroiKuma0/simplex-chat/releases)

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

The same export runs **headlessly** when an automation task asks for it. A token-gated broadcast — the token lives on the UI page, is copied with a tap, and is regenerable — makes the app write exactly one ZIP wherever the caller names, report progress in real counts (`区分 3/8 — Chat bubbles`, or `512 MB / 4.2 GB` while streaming the chat database), and reply with the written path and its exact size. Categories are selectable by id, down to sub-options such as the font files alone, and the app *states* which ones should start ticked rather than leaving the caller to guess.

A running export can also be **stopped from outside**, and stopping it means stopping it: the archive is written under a temporary name and only claims the real one once complete, so a cancelled run leaves the backup directory exactly as it found it — no half-written backup that looks finished. The check runs between the copy buffers of the chat database, not just between categories, so a cancel during a multi-gigabyte export takes effect in milliseconds instead of playing out to the end. Off by default; nothing responds until the switch is on.

---

## ✔️ Delivery ticks, down to the glyph

Scale the sent/delivered indicators from 1× to 15×, set their stroke thickness, give each its own color — and now pick **what shape each one is**: single, double or triple tick, dot, tick in a circle, up arrow, clock, exclamation mark, or hidden. The picker previews every option at your configured size, color and thickness, drawn by the same code the chat footer uses, so what you choose is exactly what you get. Delivered defaults to a **dot** rather than a second tick — shape, not stroke count, tells the two rungs apart — with a dot-size multiplier (1–5 in tenths) to match its weight to a tick. Everything is redrawn as true vector strokes on a Canvas, so it stays crisp at any size instead of plateauing at the stock icon's intrinsic limit.

---

## 🖤 Truly black surfaces, marked in yellow

Stock lifts "raised" dark surfaces by mixing a few percent of the text color into the background — which on a black/yellow theme comes out olive-green. Here every surface is pure black and separated by a **rounded yellow border** instead: the profile sheet, every settings card, the app bars, the one-hand chooser. The muted color that carries timestamps, delivery dots, date headers and toolbar icons was translucent yellow (`#99ffff00` — literally `#999900` over black); it is opaque `#FFFF00` now.

---

## 👆 Built for one hand on a big phone

The **bottom** bar is 50% taller than stock — and only the bottom one — with its avatar and new-chat button scaled to match, so the tap targets are comfortable on a foldable. Every long-press shortcut into the UI page fires at 250 ms instead of the platform's sluggish default, without making message menus trigger by accident. **Private notes** is hidden from the chat list unless you switch it on, so the list holds only real conversations.

---

## 🖤💛 Black-yellow identity

The launcher icon is restyled into the fork's black/yellow scheme — the SimpleX hashmark traced in yellow on black, faithful to upstream's geometry — and the app presents as **白い熊 SimpleX**, so it's unmistakable next to the official install.

---

## 📦 Tracks upstream fast, ships lean

The fork rebases onto every new upstream release — **betas included** — usually days after it ships, and this build sits on upstream **7.1-beta.0** the day it landed. APKs are `arm64-v8a` only and versioned `<upstream>+<build>` (e.g. `7.1-beta.0+1`), with version codes that always sort above the corresponding upstream build. Since upstream split Android into `google` (Play Billing) and `foss` variants in 7.1, this is a **`foss`** build — no Play dependencies.

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
(cd apps/multiplatform && ./gradlew :android:assembleFossRelease)
# then zipalign + apksigner with your own keystore
```

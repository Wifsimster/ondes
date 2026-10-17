# 🚀 Google Play Store — publication checklist

Tracking everything required to ship **Ondes** on the Google Play Store.
Ondes was originally built for sideloading (no store, no account), so this
covers the gap to a production Play listing.

Legend: ✅ done · 🟡 partially done / needs action · ⬜ not started ·
🔴 blocker.

---

## 1. Build & signing (in this repo)

| # | Item | Status | Notes |
|---|------|--------|-------|
| 1.1 | Release signing config reads an **upload key** from `keystore.properties` / CI env vars, falls back to debug key when absent | ✅ | `app/build.gradle.kts`. See `keystore.properties.example`. |
| 1.2 | Generate the real **upload keystore** and store it safely (1Password / secret manager) | ⬜ | Run `./scripts/setup-upload-keystore.sh`. Full walkthrough: [`docs/SIGNING_SETUP.md`](SIGNING_SETUP.md). **Never commit it.** |
| 1.3 | Add CI secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | ⬜ | The setup script prints the exact `gh secret set` commands. See [`docs/SIGNING_SETUP.md`](SIGNING_SETUP.md) §3. |
| 1.4 | Build the **Android App Bundle (.aab)** — Play requires AAB, not APK | ✅ | `./gradlew bundleRelease` → `app/build/outputs/bundle/release/app-release.aab`. CI uploads `ondes-release-aab`. |
| 1.5 | `targetSdk` meets Play's current minimum for new apps | ✅ | `targetSdk = 36` (Android 16), `compileSdk = 36`. |
| 1.6 | **16 KB page size** compliance (required for new apps/updates on Android 15+) | ✅ | Enforced in CI: `scripts/check-16kb-alignment.sh` inspects every `.so` in the AAB and fails the build if any LOAD segment isn't 16 KB-aligned (passes trivially if there are no native libs). Run locally on any `.aab`/`.apk` too. |
| 1.7 | `versionCode` strictly increases on every upload | ✅ | Derived from semver in `app/build.gradle.kts` (major*10000 + minor*100 + patch). v1.4.0 → `10400`. |
| 1.8 | Backup / data-extraction rules declared (Android 12+) | ✅ | `data_extraction_rules.xml` + `backup_rules.xml` referenced in the manifest. |
| 1.9 | R8/ProGuard release build verified (no crash from stripped classes) | 🟡 | Smoke-test the release AAB on a device before submitting. |

## 2. Google Play Console — account & process

| # | Item | Status | Notes |
|---|------|--------|-------|
| 2.1 | Developer account ($25 one-time) + **identity verification** | ⬜ | Registering as an **organization** (see business details below) → requires a **D-U-N-S number** (free via Dun & Bradstreet, ~1–4 weeks) and matching legal name/address. Plan this lead time. A sole trader may alternatively register as an individual. |
| 2.2 | **Closed testing** before production | ⬜ | New personal accounts must run a closed test with **≥12 testers for ≥14 days** to unlock production. Plan this lead time. |
| 2.3 | Create the app, choose **Music & Audio** category, free | ⬜ | |
| 2.4 | Enroll in **Play App Signing** | ⬜ | Recommended — Google manages the app signing key; you keep the upload key. |
| 2.5 | Internal testing track for fast iteration | ⬜ | Optional but useful while filling forms. |

## 3. Policy & declarations (Play Console forms)

| # | Item | Status | Notes |
|---|------|--------|-------|
| 3.1 | **Privacy policy URL** (hosted, public) | 🔴 ⬜ | Mandatory for all apps. Draft below in §6. Host it (GitHub Pages works). |
| 3.2 | **Data safety** form | ⬜ | Ondes stores everything on-device, no accounts, no analytics/ads. Declare: no data collected/shared. Mention network calls to iTunes search + RSS feeds (not "collection" per Play's definition, but be accurate). |
| 3.3 | **Foreground service** declaration | ⬜ | Justify `FOREGROUND_SERVICE_MEDIA_PLAYBACK` — background audio playback (core feature). |
| 3.4 | **Content rating** (IARC) questionnaire | ⬜ | Likely "Everyone"; podcast content is user-fetched, so review the music/streaming questions. |
| 3.5 | **Ads** declaration | ⬜ | No ads. |
| 3.6 | **App access** | ⬜ | No login required — declare full access, no credentials needed. |
| 3.7 | **Target audience & content** | ⬜ | Not directed at children. |
| 3.8 | Government / financial / health declarations | ✅ N/A | None apply. |
| 3.9 | Account/data **deletion** disclosure | ✅ N/A | No account; data is local and removable via uninstall + in-app backup/restore. Note in Data safety if asked. |

## 4. Store listing assets

| # | Item | Status | Notes |
|---|------|--------|-------|
| 4.1 | App name (≤30 chars) | ✅ | Renamed to **"Ondes"** (display name); see §5. |
| 4.2 | Short description (≤80) + full description (≤4000) | ✅ | Drafted EN + FR in [`docs/store-listing.md`](store-listing.md) (§8); lengths verified within limits — name 22/27, short 76/79, full 1580/1827 chars (EN/FR). |
| 4.3 | **Hi-res icon 512×512 PNG** (32-bit, alpha) | ✅ | `docs/store-assets/icon-512.png` (matches the in-app adaptive icon). |
| 4.4 | **Feature graphic 1024×500 PNG/JPG** | ✅ | `docs/store-assets/feature-graphic-1024x500.png`. |
| 4.5 | Phone screenshots (2–8, min 320px, 16:9 or 9:16) | 🟡 | 8 exist in `docs/screenshots/` (fictional demo shows), all **540×1200** PNG. They clear the 320px min, but the **2.22:1** side ratio exceeds Play's **2:1** max → re-export padded/cropped to ≤2:1 (e.g. ≤1280px tall at 640px wide), and prefer device-native width (**≥1080px**) for crisper listings. |
| 4.6 | (Optional) tablet / Android Auto / 7"+10" screenshots | ⬜ | App supports Android Auto — consider a car screenshot. |
| 4.7 | Contact email + (optional) website | ✅ | battistella@proton.me (see §7). |

## 5. Branding / legal risk — ✅ resolved

The app was previously named **"Ondes"**, themed around, and **auto-subscribed**
the third-party podcast **"Silicon Carne"** by Carlos Diaz with no affiliation —
which risked tripping Google Play's **Impersonation** and **Intellectual
Property** policies.

**Mitigation applied in this branch:**
- ✅ Renamed the user-facing app to **"Ondes"** (`app_name`, onboarding/home
  copy, OPML export title, HTTP User-Agent).
- ✅ Removed the first-launch auto-subscription of the Silicon Carne feed
  (`OndesApp.bootstrapDefaultSubscription` and `SILICON_CARNE_FEED` deleted).
  Users now add shows themselves via search / RSS / OPML.
- ✅ Updated README to drop the Silicon Carne references.

Note: the internal `applicationId`/package stays `ovh.battistella.ondes` (not
user-visible, generic word, not infringing) to avoid a permanent ID change and
a large package refactor. Revisit only if you want a fully on-brand store URL
**before** the first publish (the ID is immutable once published).

## 6. Privacy policy

A ready-to-host policy lives at [`docs/privacy-policy.md`](privacy-policy.md).
Publish it at a stable public URL (GitHub Pages works) and paste that URL into
the Play Console "Privacy policy" field. It already references the legal entity
and contact below.

## 7. Business / developer details

Used for the Play Console organization account, the privacy policy, and the
app's legal/imprint info.

| Field | Value |
|-------|-------|
| Legal entity (SIREN) | 103 406 161 |
| Establishment (SIRET) | 103 406 161 00010 |
| Activity | Programmation informatique |
| Registered | 2026-04-07, Artigues-près-Bordeaux (France) |
| Contact email | battistella@proton.me |
| Country | France |

D-U-N-S: request one matching this legal name/address before starting the
organization account verification (2.1).

## 8. Store listing copy

Ready-to-paste EN + FR titles, short and full descriptions live at
[`docs/store-listing.md`](store-listing.md).

---

## Suggested order of operations

1. ✅ Branding resolved (§5) — app renamed to "Ondes", Silicon Carne seed removed.
2. Request a **D-U-N-S number** for the legal entity (§7) — longest lead time.
3. Generate the **upload keystore** (1.2) and add **CI secrets** (1.3).
4. **Host the privacy policy** (3.1 / §6 / `docs/privacy-policy.md`).
5. Build the **signed AAB** via CI, smoke-test on a device (1.4, 1.6, 1.9).
6. Create the **developer account** + identity verification (2.1).
7. Create the Play Console app, fill **all policy forms** (§3) and the
   **store listing** (§4 / §8).
8. Run **closed testing** (≥12 testers / 14 days) (2.2), watch the
   **pre-launch report**.
9. Submit for **production** review.

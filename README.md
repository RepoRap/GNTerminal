# GN Terminal — Gold & Nifty Options for Android

A terminal-style Android app (amber on black, dense panels, F-key tabs) plus a
home-screen widget. It ties **gold** (spot, COMEX, MCX / India landed price) and
**Nifty options** (live option chain) to the **macro drivers** and **news** that
move them.

## What's inside

| Tab | What it shows |
|---|---|
| **F1 MKT** | Monitor of 10 instruments with intraday sparklines, gold summary, option-chain summary, cross-asset pressure gauges, top market-moving headlines |
| **F2 GOLD** | Spot (gold-api.com), COMEX futures, USD/INR, step-by-step India landed price (duty + GST), live MCX gold futures and premium/discount, 1-year chart with SMA50/200, technicals, "what's moving gold", gold news |
| **F3 OPTIONS** | Full NIFTY option chain (±12 strikes around ATM) with OI bars, ΔOI, IV, LTP; PCR, max pain, OI support/resistance, ATM IV, straddle-implied move, 1σ range, put skew, positioning bias; Nifty technicals; FII/DII cash flows |
| **F4 DRIVERS** | 60-day correlation map (DXY, US 10Y, USD/INR, Brent, S&P 500, India VIX, silver, Bank Nifty vs gold and Nifty), net macro push on each, textbook playbook |
| **F5 NEWS** | Google News, ET Markets, ET Commodities, Mint, Moneycontrol. Each headline is tagged with its driver (FED CUT, USD STRONG, INR WEAK, GEOPOLITICS, FII SELLING, DUTY HIKE…) and an AU / NF ▲▼ push. Tap to open the story |
| **F6 SET** | Import duty (default 15%), GST toggle, refresh rate, live status of every data source |

**Widget (4×2):** Nifty, India VIX, gold spot, MCX (or landed) gold, PCR, max
pain, USD/INR, option bias, and the top tagged headline. Tap ⟳ to refresh;
tap anywhere else to open the app. Refreshes about every 15 min in the
background (Android's minimum).

## Build it — option A: Android Studio (simplest)

1. Install Android Studio (free) on a laptop.
2. Unzip this folder → **File ▸ Open** → pick the `GNTerminal` folder. Let Gradle sync (first time takes a few minutes; accept any "upgrade" prompts only if the sync fails).
3. On your phone: **Settings ▸ About phone** → tap *Build number* 7 times → **Developer options ▸ USB debugging** on. Connect by USB.
4. Press ▶ **Run**. The app installs and opens.
   *Or* **Build ▸ Build App Bundle(s)/APK(s) ▸ Build APK(s)**, then copy `app/build/outputs/apk/debug/app-debug.apk` to the phone and install it.

## Build it — option B: GitHub builds the APK for you (no Android Studio)

1. Create a new **private** repository on github.com and upload the contents of this folder (keep the `.github` folder).
2. Open the **Actions** tab → the *Build APK* workflow runs automatically (≈5 min).
3. Open the finished run → download **GNTerminal-apk** → unzip → copy `app-debug.apk` to your phone → open it and allow "install unknown apps".

## Add the widget

Long-press the home screen → **Widgets** → **GN Terminal** → drag it out.

## Data sources and limits

- **Yahoo Finance** chart API — indices, COMEX gold/silver, USD/INR, DXY, US 10Y, Brent, S&P 500. Unofficial; may lag a few minutes.
- **gold-api.com** — free real-time XAU spot.
- **NSE** — option chain (`option-chain-v3`, legacy fallback) and FII/DII flows.
- **MCX** — market watch for GOLD futures.
- **RSS** — Google News, Economic Times, Mint, Moneycontrol.

NSE and MCX use bot protection. The app first tries plain HTTP; if blocked it
quietly loads the exchange page in a hidden browser view and fetches the data
from inside it (first load is slower). If MCX still can't be reached, the app
shows the computed **landed price** instead and says so.

Landed price = spot $/oz × USD/INR ÷ 31.1035 × 10 × (1 + duty). Duty defaults to
**15% (10% BCD + 5% AIDC, effective 13 May 2026)** — change it in F6 if the
government revises it.

All "bias", "tilt" and "pressure" readings are transparent rule-based
heuristics (shown with their reasoning), not forecasts or investment advice.
Free sources can change format without notice; check F6 ▸ Data sources if a
panel goes blank.

## Project layout

```
app/src/main/java/com/gnterminal/app/
  core/     pure Kotlin: JSON, parsers, indicators, option maths, drivers, news tagger
  net/      OkHttp client + hidden-WebView fallback for NSE/MCX
  data/     repository, sources, settings, widget snapshot store
  ui/       Compose terminal UI (tabs, panels, chain table, charts)
  widget/   home-screen widget + WorkManager refresh
```

Tech: Kotlin 2.0, Jetpack Compose, OkHttp, WorkManager. minSdk 26 (Android 8.0), targetSdk 35.

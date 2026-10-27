# Forbidden Streams TV

IPTV player for Android TV boxes (Android 5.0+), black and green theme, laid out like Heat Live.

## What's in it
- **Login:** Xtream Codes (server, username, password) or M3U link + optional XMLTV EPG link.
- **Top menu:** MENU · CHANNELS · MOVIES · TV SHOWS · NFL · NBA · NHL · MLB
- **Channels:** group sidebar (Search, All, History, Favorite List, your groups) and a channel list with number, logo, current show and catch-up icon. Hold OK = favorite. Press ▶ (right) on a channel = TV guide.
- **TV guide:** info panel (logo, duration, program, description), DATE strip (3 days back, 3 ahead), time grid with a "now" line. OK on a past show plays catch-up, OK on a live show plays the channel. Hold OK = Record / Set reminder / Watch from start / Favorite.
- **Movies / TV Shows:** groups + poster grid, details page, seasons and episodes.
- **NFL / NBA / NHL / MLB:** date strip with game-count badges, full-width game rows (logos, "Away vs. Home", venue, network, time or live score). Scores refresh every 30 seconds from ESPN. OK opens the team channel from your provider's "USA | <League> Teams" group; if both teams have channels you pick which feed.
- **Player:** up/down or CH+/CH− flips channels, OK shows the controls.
- **MENU:** recordings, reminders, refresh, account, log out.

## Build the APK
Option 1 — GitHub (no computer needed): put this folder in a GitHub repo. The included workflow builds the APK on every push and publishes it as the release named `latest`:
`https://github.com/<you>/<repo>/releases/download/latest/ForbiddenStreamsTV.apk`

Option 2 — Android Studio: open the folder, then Build › Build APK(s).

## Install on the TV box
1. Install **Downloader** (by AFTVnews) from the box's app store.
2. Allow Downloader to install unknown apps (Settings › Security / Apps › Special access).
3. Enter the APK link from step 1 and install.
New versions install over the old one (same signing key).

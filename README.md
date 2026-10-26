# MyTV: a minimal home screen for Chromecast with Google TV

A clean, Apple TV-style launcher on a light blue background:

| Row | What it does |
| --- | --- |
| **1. Apps** | Every streaming app installed on the Chromecast, shown with its TV banner. Long-press an app to move it left or right or hide it. The **+ Apps** tile at the end shows or hides apps. |
| **2. Teams** | **Arsenal** and **Lakers** buttons. When a game is on, the button shows a red **LIVE** badge and the score. Pressing it opens the game in the app that's carrying it. If there's no game, you get a page with upcoming games, recent results and the league table or conference standings. Long-press always opens that page. |
| **3. Continue Watching** | Shows and movies you're in the middle of, most recent first, pulled from your [Trakt](https://trakt.tv) account. Select one to jump to it. |

A clock and a ⚙ settings button sit in the top-right.

> Works on **Chromecast with Google TV** and **Google TV Streamer**. Older
> cast-only Chromecasts (the puck that plugs in with no remote) can't run apps.

---

## 1. Get the APK

Every push to this repo builds the app on GitHub Actions:

1. Open the repo on GitHub → **Actions** → latest **Build APK** run.
2. Download the **MyTV-apk** artifact (a zip containing `app-release.apk`).

Or build it yourself with Android Studio / `./gradlew assembleRelease`.

## 2. Install it on the Chromecast

1. On the Chromecast: **Settings → System → About**, then click **Android TV OS build** 7 times to turn on developer mode.
2. **Settings → System → Developer options**: turn on **USB debugging** (this covers network ADB too).
3. Find the Chromecast's IP address under **Settings → Network & Internet → (your Wi-Fi)**.
4. On your computer:
   ```sh
   adb connect 192.168.1.50:5555      # your Chromecast's IP; approve the prompt on the TV
   adb install -r app-release.apk
   ```

No computer? Install **Downloader** (by AFTVnews) from the Play Store on the Chromecast and open a direct link to the APK. Or use **Send Files to TV**.

## 3. Make it your home screen

Google TV doesn't let you pick a different home app in Settings. Two options:

**Option A (easy, nothing disabled):** install **Button Mapper** from the Play Store and remap the remote's **Home** button to open **MyTV**.

**Option B (true replacement):** disable the stock Google TV home screen over ADB:
```sh
adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith
```
Press Home and MyTV comes up. To undo:
```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
```
With the stock launcher disabled, Google's profile and account screens and the Google TV "For you" content aren't available. Everything else, including the Google Assistant / mic search, keeps working.

## 4. Turn on Continue Watching (Trakt)

Android doesn't let a home-screen app read the Continue Watching lists inside Netflix, Disney+, Max and so on. Google TV's own row comes from Google's servers, and no third-party app can get at it. The best cross-app source available is **Trakt.tv**. Trakt collects watch progress from its streaming-service sync, apps that scrobble to it (Plex, Kodi, Infuse, Jellyfin and others) and manual check-ins.

1. Create a free account at [trakt.tv](https://trakt.tv) and connect your streaming services or apps there.
2. Create an API app at **trakt.tv/oauth/applications/new**:
   - Name: `MyTV`
   - Redirect URI: `urn:ietf:wg:oauth:2.0:oob`
3. Add the **Client ID** and **Client Secret** as repository secrets named `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` (repo **Settings → Secrets and variables → Actions**). Then re-run the build. (For local builds, put them in `local.properties`.)
4. On the TV, select **Connect Trakt** in the Continue Watching row. Enter the code shown at trakt.tv/activate on your phone.

The row shows two kinds of items:
- things paused part-way through (with a progress bar), and
- the **next episode** of shows you've watched in the last ~6 months.

Selecting an item hands it to Google TV search, which shows **Watch on <app>** for whichever service has it.

## How the team buttons find the game

Scores, schedules, standings and broadcaster info come from ESPN's free public API. No account or key is needed.

When you press **Arsenal** or **Lakers**:
1. MyTV checks today's games in every competition the team plays: Premier League, Champions League, FA Cup and Carabao Cup for Arsenal, and NBA for the Lakers. A game counts if it's live or starts within 20 minutes.
2. It reads which networks ESPN lists for that game (e.g. "Peacock", "USA Net", "ESPN").
3. It picks an installed app that carries that network. It tries the network's own app first (Peacock, Paramount+, ESPN, Prime Video, Max, NBA, Spectrum SportsNet…), then live-TV apps (YouTube TV, Hulu, Sling, Fubo, DirecTV).
4. If none is installed, it searches Google TV for "Arsenal vs <opponent> live".

On the team page during a game, each app that can show it gets its own **Watch on …** button, so you can pick a different one.

The networks-to-apps mapping lives in [`Broadcasters.kt`](app/src/main/java/com/mytv/launcher/sports/Broadcasters.kt). Each competition's fallback broadcasters (used when ESPN lists none) are in [`Team.kt`](app/src/main/java/com/mytv/launcher/sports/Team.kt). Edit those if your setup differs, e.g. you watch the Lakers on Spectrum SportsNet.

**Honest limits:** streaming apps don't publish "play this live game" links, so MyTV opens the right app, and from there it's usually one click on the live game, which they put front and centre. ESPN's API is unofficial and could change.

## Customising

- **Colours:** [`ui/Theme.kt`](app/src/main/java/com/mytv/launcher/ui/Theme.kt) (`Palette.Sky` is the background).
- **Add another team:** add a `Team(...)` in `Team.kt` and put it in `Teams.all`. You'll need its ESPN team ID: the number in the team's espn.com URL.

## Project layout

```
app/src/main/java/com/mytv/launcher/
  MainActivity.kt          screen switching, Home/Back handling
  LauncherViewModel.kt     all app state + actions
  apps/                    installed apps, ordering/hiding, Google TV search
  sports/                  ESPN client + parser, broadcaster → app mapping, teams
  watching/                Trakt login + Continue Watching
  ui/                      Compose for TV screens
```

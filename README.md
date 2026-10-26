# MyTV: a minimal home screen for Chromecast with Google TV

A clean, Apple TV-style launcher on a black-to-graphite gradient:

| Row | What it does |
| --- | --- |
| **Top 10** | Not a row you select: as you move across the apps, the area above shows **Top 10 on Netflix / Hulu / Prime Video / Disney+ / Apple TV / Peacock** with posters, and the #1 title's artwork fades in behind. Other apps (YouTube, YouTube TV...) get a greeting and the date instead. |
| **1. Apps** | Every streaming app installed on the Chromecast, shown with its TV banner. Long-press an app to move it left or right or hide it. The **+ Apps** tile at the end shows or hides apps. |
| **2. Teams** | **Arsenal** and **Lakers** buttons. When a game is on, the button shows a red **LIVE** badge and the score. Pressing it opens the game in the app that's carrying it. If there's no game, you get a page with upcoming games, recent results and the league table or conference standings. Long-press always opens that page. |
| **3. Continue Watching** | Shows and movies you're in the middle of in any app, most recent first, with progress bars. Select one to resume it in its app. |
| **4. Popular on Your Apps** | The Top 10s of your streaming apps (Paramount+ included, through Prime Video), merged into one ranking. Hold select to like, mark watched, dislike, or hide a title. |
| **5. Shows for You** | Appears once you've rated 3 titles: picks based on what you watched and liked, mixed across genres, shows and movies. |

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

## 4. Continue Watching

Android TV keeps a shared **Watch Next** list. Streaming apps add to it whenever you watch something, and Google TV's own Continue Watching row reads from it. MyTV reads the same list, so nothing needs logging. Each card resumes playback inside the app it came from.

It needs the **TV listings** permission, once:
- MyTV asks on first launch. Choose **Allow**.
- If you said no, or no prompt appeared, select the **Turn on Continue Watching** card. If that still doesn't work, the firmware has no on-screen prompt; grant it from a computer:
  ```sh
  adb shell pm grant com.mytv.launcher android.permission.READ_TV_LISTINGS
  ```
  If that says "Unknown permission", run `adb shell pm list permissions | grep -i -E "tv|epg|watch"` and grant the one listed.

What shows up depends on each app: only apps that publish to Watch Next appear. Most big streaming apps do, but not every one. If an app's shows appear on Google TV's stock row, they'll appear here.

### Optional: Trakt

To also include things you watched on other devices (phone, laptop), you can connect [Trakt.tv](https://trakt.tv). Its automatic streaming-service sync is a paid VIP feature.
1. Create an API app at **trakt.tv/oauth/applications/new** (redirect URI `urn:ietf:wg:oauth:2.0:oob`).
2. Add the Client ID and Secret as repository secrets named `TRAKT_CLIENT_ID` and `TRAKT_CLIENT_SECRET` (or put them in `local.properties`), then rebuild.
3. A **Connect Trakt** card appears at the end of the row. Select it and enter the code at trakt.tv/activate.

## My Watchlist

The **★ My Watchlist** button sits next to Ang's. It has four tabs:

| Tab | What's in it |
| --- | --- |
| **Watched** | Everything you marked "Already watched" or "Like it" (hold select on any title on the home screen) |
| **For You** | Recommendations from those, with IMDb ratings on every poster, mixed across genres |
| **New Seasons** | New seasons, out now, of shows you watched or liked |
| **Coming Soon** | Announced next seasons of your shows, plus upcoming new shows in the genres you watch most |

Your ratings are separate from Ang's. A like counts double an "Already watched" when picking recommendations.

## Ang's TV

The **Ang** button, next to the team buttons, opens her own guide in Love Island colours (teal, pink and yellow):

| Row | What's in it |
| --- | --- |
| **Continue watching** | What she's in the middle of, from the TV's Continue Watching, narrowed to her kind of shows. Select one to resume it. |
| **Watch now** | The Kardashians, plus a new season of any of her shows or of anything she's liked |
| **New shows you might like** | Romance, rom-com, coming-of-age and dating shows that started in the last year |
| **Shows you might have missed** | Well-rated shows like that from the last 10 years |
| **Movies for you** | Rom-coms, romance and romantic dramas from the last 10 years, new ones included |
| **Coming soon** | Announced next seasons of her shows and liked shows, plus new shows of her kind premiering in the next 6 months on your apps |

Every suggestion is English, from the US, Canada or UK, on a streaming app you have installed, and never adult or erotic.

**Teaching it her taste:** hold select on any title.
- **♥ Like it:** shows like it move up and get added to her rows, and it's followed for new seasons (Coming soon, then Watch now).
- **✓ Already watched / Won't watch:** hides it.
- **✕ Dislike:** hides it, and pushes shows like it down.

Ratings are saved on the TV. Her hand-picked shows are in [`ang/AngShows.kt`](app/src/main/java/com/mytv/launcher/ang/AngShows.kt). Suggestions come from TMDB, so they need the `TMDB_API_KEY` secret.

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

## Top 10 (TMDB key)

The Top 10s come from [TMDB](https://www.themoviedb.org): the most popular titles right now among everything streaming on that service in the US. Only Netflix publishes an official Top 10, so all six use the same source. That means the order won't exactly match what the apps show.

1. Create a free account at themoviedb.org.
2. Go to **Settings → API** and request a key. Choose "Personal" use; it's approved instantly.
3. Copy the **API Key** (the short one) or the **API Read Access Token** (the long one). Either works.
4. Add it as a repository secret named `TMDB_API_KEY`, then re-run **Build APK**. The TV picks it up through the auto-updater.

## IMDb ratings (OMDb key)

When you stop on a show or movie in Ang's TV or the Popular row, a small **IMDb 7.8** tag appears on the poster. IMDb has no free API, so ratings come from [OMDb](https://www.omdbapi.com). It's free, with a limit of 1,000 lookups a day, and ratings are cached for a week.

1. Go to omdbapi.com → **API Key** → choose **FREE**, and enter your email.
2. Click the activation link in the email. The key arrives in that same email.
3. Add it as a repository secret named `OMDB_API_KEY`, then re-run **Build APK**.

Until the key is added, the tag shows TMDB's score instead (blue **TMDB 7.6**).

## Updates

MyTV updates itself. Every push builds a new version and publishes it as this repo's `latest` release (`MyTV.apk` plus `version.json`). MyTV checks for one when you open it and every few hours after that, and downloads it in the background.

- **First update:** an **Update ready** button appears in the top-right. Select it, then **Update** on Android's prompt. If Android says MyTV isn't allowed to install apps, turn MyTV on in the screen it opens, press Back and select **Update ready** again.
- **After that** (Android 12+): updates install by themselves while MyTV is in the background, e.g. while you're in another app or the screensaver is on.
- The version number is shown in the top-right of the **+ Apps** screen.

The repo needs to be **public**, since the TV downloads updates without signing in to GitHub.

Every build must be signed with the same key, because Android only accepts an update signed with the same key as the installed app. The key is kept out of the code, in a repository secret named `SIGNING_KEYSTORE_BASE64` (repo **Settings → Secrets and variables → Actions**). Until that secret exists, builds still run but no release is published. That way you can't install a build that would later refuse to update.

## Customising

- **Colours:** [`ui/Theme.kt`](app/src/main/java/com/mytv/launcher/ui/Theme.kt) (`Palette.Background` is the gradient).
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

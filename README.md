# Nuvio Recommendation Opener

Independent Android TV app that opens Google TV recommendations directly in
Nuvio. It is free and has no accounts or subscriptions.

## Download

Download the latest signed APK from
[GitHub Releases](https://github.com/nswsys/GoogleTV-nuvio-bridge/releases/latest).

Current release:
[NuvioRecommendationOpener-v1.4.15.apk](https://github.com/nswsys/GoogleTV-nuvio-bridge/releases/download/v1.4.15/NuvioRecommendationOpener-v1.4.15.apk)

## Improvements

- Uses Nuvio's current TMDB deep links directly:
  `nuvio://tmdb/movie/{id}` and `nuvio://tmdb/tv/{id}`.
- Works with both known Nuvio TV package names (`com.nuvio.tv` and
  `com.nuvio.app`).
- Supports common recommendation metadata in English and Spanish.
- Recognizes single-title cards used by Browse by genre and Top free picks,
  while excluding launcher navigation, genre tiles and installed apps.
- Removes streaming-provider and Rotten Tomatoes metadata from Google TV card
  descriptions before resolving titles through TMDB.
- Preserves titles containing commas, such as `Monsters, Inc.`.
- Scores several TMDB results instead of blindly selecting the first result.
- Uses the year when the card provides one.
- Detects Google TV detail pages by their title row, year and playback actions.
- Adds a remote-friendly **Open in Nuvio** accessibility overlay when a card
  hides its title (for example, obfuscated `Column 1` free-pick cards).
- Offers a chooser for remakes or movie/series titles with multiple strong
  TMDB matches instead of silently opening the wrong result.
- When a title is ambiguous, waits for Google TV's detail metadata and uses the
  release year before opening the chooser.
- Retries detail detection while Google TV loads and places the Nuvio action
  beside the native primary action.
- Uses a rounded, focus-aware TV chooser styled to match Google TV more closely.
- Caches matches for 14 days.
- Debounces repeated clicks and discards stale network results.
- Optionally skips Google TV sponsored rows by moving focus to the next
  recommendation while preserving the current horizontal column.
- Restricts the accessibility service to the Google TV launcher package.
- Sends no account, payment, subscription, viewing-history or device data to a
  custom backend.

## Requirements

- Google TV launcher (`com.google.android.apps.tv.launcherx`).
- Nuvio TV installed.
- Android 7.0 or newer.
- A free TMDB API v3 key.
- Android Studio with JDK 17 and Android SDK 36.

## Configure

The easiest option is to open the installed app, enter your TMDB API v3 key and
choose **Save TMDB key**. It is stored only in the app's private local settings.

To preconfigure the key while compiling instead:

1. Copy `local.properties.example` to `local.properties`.
2. Add the key:

   ```properties
   TMDB_API_KEY=your_key_here
   ```

3. Open the project in Android Studio and build the `app` module.

Run the local unit tests with:

```bash
./gradlew test
```

The API key is compiled into the APK. Treat it as a client identifier, restrict
its use where TMDB supports it, and do not place private backend secrets in the
app.

## Install and enable

Install the APK with Android Studio or ADB:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Open the app and choose **Enable accessibility service**. Android 13 and newer
may require allowing restricted settings for sideloaded apps from the app-info
screen. The exact menu varies by Google TV manufacturer.

Use **Test Nuvio integration** to open *The Matrix* through a TMDB deep link.

## Privacy

The accessibility service receives click, focus and window-content events only
from the Google TV launcher. Candidate titles are sent to TMDB for
identification. Results are stored locally for 14 days. The app has no
analytics, ads, user accounts, subscription checks or payment processing.

## Scope

This app redirects metadata cards to a third-party app. It does not host or
provide audiovisual content, streams, torrents or add-ons. It is not affiliated
with or endorsed by Google, TMDB or Nuvio.

Nuvio compatibility is based on the public deep-link interface in the official
[Nuvio TV repository](https://github.com/NuvioMedia/NuvioTV). No Nuvio source
code is included in this project.

## License

MIT. See `LICENSE`.

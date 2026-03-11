# KOMF (Kavita-Focused Fork)
Forked from https://github.com/Snd-R/komf

## Overview
This fork is focused on Kavita deployments. It fetches metadata and thumbnails for your library, can automatically pick up new series, and supports manual identify/match workflows.

### Fork behavior

- `server.kavitaOnly` defaults to `true` in this fork.
- Komga runtime/routes are disabled by default in Kavita-only mode.
- Compatibility routes are kept where needed for existing clients.

### Changes From Upstream KOMF

This fork includes focused Kavita hardening and behavior fixes beyond upstream defaults:

- `kavitaOnly` runtime mode:
  - defaults to `true`
  - Komga runtime initialization is disabled in that mode
  - Komga routes are disabled, with compatibility fallbacks where needed
- Scan flow update for Kavita:
  - per-series match/update triggers per-series scan when run individually
  - full-library match defers per-series scans and triggers one library scan at the end
  - avoids kicking off both series scan and library scan for the same library run
  - optional safe full-library mode can suppress per-series scans during full-library runs and only issue a library-end scan
- Apply-mode behavior for Kavita:
  - `CORE` is the recommended default for normal Kavita metadata runs
  - `CORE` updates series metadata, summary, external links, series cover, and volume covers
  - `CORE` does not write chapter-level metadata
  - `CHAPTERS` only writes chapter-level metadata
  - `FULL` combines `CORE` and `CHAPTERS`
  - if `applyMode` is omitted by a client, this fork defaults to `CORE` for library runs
  - this means older/basic clients that do not expose apply-mode controls still avoid chapter metadata writes by default
- Cover-lock behavior:
  - supports split lock fields (`lockSeriesCover`, `lockVolumeCover`) in config/API
  - legacy `lockCovers` compatibility remains for older clients
- Build/runtime baseline:
  - upgraded to Java 21 toolchain/runtime
  - this fork should be treated as Java 21+; do not assume Java 17 compatibility
- Safer default throttling for SQLite-backed deployments:
  - `updateEventsPerMinute: 30`
  - `scanEventsPerMinute: 5`
  - `deferredLibraryScanDelaySeconds: 300`
  - `waitForActiveScanToFinish: true`
  - `activeScanWaitTimeoutSeconds: 1800`
  - `activeScanPollIntervalSeconds: 2`

### Upstream Base / Fork Traceability

- Upstream base (from `master`): `5d0f689dc9832056e669e60f41dd8f01d7b275fe`
- Current fork head (`kavita-hardening`): `fb03544`

### WebUI integration
The browser extension/userscript can configure KOMF and run identify/match from Kavita UI.

- [Chrome download]( https://chromewebstore.google.com/detail/komf/bhppjldobkpocplgfcimljjhdjgbpdnh)
- [Firefox download](https://addons.mozilla.org/en-US/firefox/addon/komf/)
- [Kavita-focused userscript fork (DMD770)](https://github.com/DMD770/komf-userscript-kavita) (recommended in this fork workflow; exposes split `Lock Series Cover` and `Lock Volume Cover`)
- [Original Komf userscript (Snd-R)](https://github.com/Snd-R/komf-userscript) (upstream reference)

Credit: userscript foundation and original work by Snd-R.

Client choice note:

- The Kavita-focused userscript fork is the tailored client for this repo and is the recommended UI companion.
- The official browser extension should still work with this fork for normal Kavita use because compatibility routes and legacy config compatibility are retained.
- The main difference is feature exposure:
  - the tailored userscript exposes this fork's Kavita-focused options more directly
  - the official extension may still present older/generalized controls and may not expose newer fork-specific details
- In particular, the tailored userscript exposes:
  - split `Lock Series Cover` and `Lock Volume Cover`
  - apply-mode aware library/series actions aligned with this fork
- The official extension will generally still work, but users may miss some fork-specific controls or wording and may only see the older combined `Lock Covers` style UX.

Lock behavior note:

- Official extension keeps a single `Lock Covers` control; in practice this applies cover lock behavior for both series and volume updates.
- The customized userscript used in this fork exposes split controls (`Lock Series Cover` and `Lock Volume Cover`) for finer control.
- In this fork's config defaults, `lockSeriesCover` defaults to `true` and `lockVolumeCover` defaults to `false`.
- Legacy `lockCovers` compatibility remains for older clients/config updaters.

Route compatibility note:

- This fork keeps compatibility routes for existing clients.
- The Kavita-focused userscript is already written to try both route shapes:
  - newer style: `/api/{server}/metadata/...`
  - legacy style: `/{server}/...`
- Most reads and library-wide actions try the newer route first and fall back to the legacy route on `404`.
- `identify` and per-series `match` intentionally try the legacy route first and then fall back to the newer route on `404`.
- Job polling also supports both route shapes: `/api/jobs/...` first, then `/jobs/...`.
- The userscript does not probe both routes up front. It uses the first one that succeeds.

Apply-mode note for extension users:

- The Kavita-focused userscript defaults interactive match actions to `CORE`.
- If another client or older extension does not send `applyMode` at all, the server-side default in this fork is still `CORE`.
- Per-series match requests also inherit the configured default apply mode when `applyMode` is omitted.
- In practice, "regular" runs against this fork should therefore skip chapter metadata writes unless a client explicitly requests `CHAPTERS` or `FULL`.

### Library-run QoL endpoints (Kavita-focused)

For large runs where some series IDs go stale (204/404), this fork exposes targeted retry and run-summary APIs:

- `GET /api/kavita/metadata/skipped/library/{libraryId}`
  - list stale/skipped series from the latest run
- `POST /api/kavita/metadata/retry-skipped/library/{libraryId}?dryRun=false`
  - retries only skipped entries; remaps to current IDs when possible
- `DELETE /api/kavita/metadata/skipped/library/{libraryId}`
  - clears stored skipped entries
- `GET /api/kavita/metadata/summary/library/{libraryId}/latest`
  - latest library run summary (processed/updated/skipped/errors/timing)
- `GET /api/kavita/metadata/summary/library/{libraryId}?limit=10`
  - recent summary history
- `GET /api/kavita/metadata/control/library/{libraryId}/status`
  - reports whether a run is active, paused, stop-requested, and whether a resumable checkpoint exists
- `POST /api/kavita/metadata/control/library/{libraryId}/pause`
  - requests pause for the active library run
- `POST /api/kavita/metadata/control/library/{libraryId}/resume?mode=CONTINUE|NEW`
  - resumes from checkpoint or starts fresh depending on mode
- `POST /api/kavita/metadata/control/library/{libraryId}/stop`
  - requests graceful stop for the active library run

### Health / compatibility endpoint

- `GET /api/health/compat`
  - runs Kavita API compatibility checks and returns a compatibility report
  - `200 OK` means pass/warn, `503` means a failing compatibility check was detected
  - useful after upgrading Kavita or validating a new deployment

### Environment variables

This fork can be configured through `application.yml`, environment variables, or a mix of both. For container deployments, env vars are often the easiest option.

Commonly used env vars:

- `KOMF_SERVER_PORT`
- `KOMF_SERVER_KAVITA_ONLY`
- `KOMF_SERVER_METADATA_REQUESTS_PER_MINUTE`
- `KOMF_LOG_LEVEL`
- `KOMF_KAVITA_BASE_URI`
- `KOMF_KAVITA_API_KEY`
- `KOMF_KAVITA_API_RATE_LIMIT_UPDATE_EVENTS_PER_MINUTE`
- `KOMF_KAVITA_API_RATE_LIMIT_SCAN_EVENTS_PER_MINUTE`
- `KOMF_KAVITA_SCAN_DEFERRED_LIBRARY_SCAN_DELAY_SECONDS`
- `KOMF_KAVITA_SCAN_WAIT_FOR_ACTIVE_SCAN_TO_FINISH`
- `KOMF_KAVITA_SCAN_ACTIVE_SCAN_WAIT_TIMEOUT_SECONDS`
- `KOMF_KAVITA_SCAN_ACTIVE_SCAN_POLL_INTERVAL_SECONDS`
- `KOMF_KAVITA_SAFE_FULL_LIBRARY_ENABLED`
- `KOMF_KAVITA_SAFE_FULL_LIBRARY_SCAN_POLICY`
- `KOMF_KAVITA_SAFE_FULL_LIBRARY_FAIL_FAST_ON_SQLITE_ERRORS`
- `KOMF_KAVITA_SAFE_FULL_LIBRARY_APPLY_MODE`

Environment variable note:

- `application.yml` is still the clearest place for larger configs like provider ordering and per-library metadata rules.
- Env vars are best for deployment/runtime toggles, secrets, and the Kavita safety/rate-limit knobs above.
- Some newer scan pause/gate knobs currently remain `application.yml`-only even though they are documented in the config model:
  - `kavita.scan.pauseOnActiveScan`
  - `kavita.scan.resumeQuietPeriodSeconds`
  - `kavita.scan.pauseTimeoutSeconds`
  - `kavita.scan.pollIntervalSeconds`
- If you use an older client, UI/config updates may still write legacy-compatible fields, but the server normalizes them into the current split config.

## Building

To build the application, follow these steps:

1. Run `./gradlew :komf-app:clean :komf-app:shadowjar`.
2. The output will be in `komf-app/build/libs`.

## Running

To run the application, you can either use the JAR file or Docker Compose.

### Running with JAR

To run the application using the JAR file, follow these steps:

1. Ensure you have Java 21 or higher installed on your system (this fork is upgraded to JDK 21 and should not be assumed to run on Java 17).
2. Run `java -jar komf-1.0-SNAPSHOT-all.jar <path to config>`.
3. By default the server listens on `http://localhost:8085` (customizable via config/env).

Java version note:

- Upstream assumptions about Java 17 do not apply to this fork.
- Use Java 21+ for local JAR runs, builds, and containers based on this repo.

### Running with Docker Compose

To run the application using Docker Compose, use the following YAML configuration:

```yml
version: "3.7"
services:
  komf:
    image: sndxr/komf:latest
    container_name: komf
    ports:
      - "8085:8085"
    user: "1000:1000"
    environment:
      - KOMF_KOMGA_BASE_URI=http://komga:25600
      - KOMF_KOMGA_USER=admin@example.org
      - KOMF_KOMGA_PASSWORD=admin
      - KOMF_KAVITA_BASE_URI=http://kavita:5000
      - KOMF_KAVITA_API_KEY=16707507-d05d-4696-b126-c3976ae14ffb
      - KOMF_KAVITA_API_RATE_LIMIT_UPDATE_EVENTS_PER_MINUTE=30
      - KOMF_KAVITA_API_RATE_LIMIT_SCAN_EVENTS_PER_MINUTE=5
      - KOMF_KAVITA_SCAN_DEFERRED_LIBRARY_SCAN_DELAY_SECONDS=300
      - KOMF_KAVITA_SCAN_WAIT_FOR_ACTIVE_SCAN_TO_FINISH=true
      - KOMF_KAVITA_SCAN_ACTIVE_SCAN_WAIT_TIMEOUT_SECONDS=1800
      - KOMF_KAVITA_SCAN_ACTIVE_SCAN_POLL_INTERVAL_SECONDS=2
      - KOMF_KAVITA_SAFE_FULL_LIBRARY_ENABLED=false
      - KOMF_KAVITA_SAFE_FULL_LIBRARY_SCAN_POLICY=NONE
      - KOMF_KAVITA_SAFE_FULL_LIBRARY_FAIL_FAST_ON_SQLITE_ERRORS=true
      - KOMF_KAVITA_SAFE_FULL_LIBRARY_APPLY_MODE=CORE
      - KOMF_SERVER_KAVITA_ONLY=true
      - KOMF_SERVER_METADATA_REQUESTS_PER_MINUTE=0
      - KOMF_LOG_LEVEL=INFO
      # optional jvm options. Example config for low memory usage. Runs guaranteed cleanup up every 3600000ms(1hour)
      - JAVA_TOOL_OPTIONS=-XX:+UnlockExperimentalVMOptions -XX:+UseShenandoahGC -XX:ShenandoahGCHeuristics=compact -XX:ShenandoahGuaranteedGCInterval=3600000 -XX:TrimNativeHeapInterval=3600000
    volumes:
      - /path/to/config:/config #path to directory with application.yml and database file
    restart: unless-stopped
```

### Unraid Template (Import URL)

You can import this template in Unraid:

- `https://raw.githubusercontent.com/DMD770/komf-kavita-only/kavita-hardening/unraid/komf-kavita-only.xml`

### Docker Image Publish Targets

This fork's CI publishes container images to:

- GHCR: `ghcr.io/dmd770/komf-kavita-only`
- Docker Hub: `docker.io/devilmaydie770/komf-kavita-only`

Recommended tag right now:

- `devilmaydie770/komf-kavita-only:kavita-hardening`

### Running with Docker Create

```
docker create \
  --name komf \
  -p 8085:8085 \
  -u 1000:1000 \
  -e KOMF_KOMGA_BASE_URI=http://komga:25600 \
  -e KOMF_KOMGA_USER=admin@example.org \
  -e KOMF_KOMGA_PASSWORD=admin \
  -e KOMF_KAVITA_BASE_URI=http://kavita:5000 \
  -e KOMF_KAVITA_API_KEY=16707507-d05d-4696-b126-c3976ae14ffb \
  -e KOMF_KAVITA_API_RATE_LIMIT_UPDATE_EVENTS_PER_MINUTE=30 \
  -e KOMF_KAVITA_API_RATE_LIMIT_SCAN_EVENTS_PER_MINUTE=5 \
  -e KOMF_KAVITA_SCAN_DEFERRED_LIBRARY_SCAN_DELAY_SECONDS=300 \
  -e KOMF_KAVITA_SCAN_WAIT_FOR_ACTIVE_SCAN_TO_FINISH=true \
  -e KOMF_KAVITA_SCAN_ACTIVE_SCAN_WAIT_TIMEOUT_SECONDS=1800 \
  -e KOMF_KAVITA_SCAN_ACTIVE_SCAN_POLL_INTERVAL_SECONDS=2 \
  -e KOMF_KAVITA_SAFE_FULL_LIBRARY_ENABLED=false \
  -e KOMF_KAVITA_SAFE_FULL_LIBRARY_SCAN_POLICY=NONE \
  -e KOMF_KAVITA_SAFE_FULL_LIBRARY_FAIL_FAST_ON_SQLITE_ERRORS=true \
  -e KOMF_KAVITA_SAFE_FULL_LIBRARY_APPLY_MODE=CORE \
  -e KOMF_SERVER_KAVITA_ONLY=true \
  -e KOMF_SERVER_METADATA_REQUESTS_PER_MINUTE=0 \
  -e KOMF_LOG_LEVEL=INFO \
  -v /path/to/config:/config \
  --restart unless-stopped \
  devilmaydie770/komf-kavita-only:kavita-hardening
```

- if you don't already have a komga or kavita network you'll need to network create a new one
    - `docker network create my_network`
- attach komf and media server to new network:
    - `docker network connect my_network komga_or_kavita`
    - `docker network connect my_network komf`
- start the container `docker start komf`

## Example `application.yml` Config

```yml
komga:
  baseUri: http://localhost:25600 #or env:KOMF_KOMGA_BASE_URI
  komgaUser: admin@example.org #or env:KOMF_KOMGA_USER
  komgaPassword: admin #or env:KOMF_KOMGA_PASSWORD
  eventListener:
    enabled: false # if disabled will not connect to komga and won't pick up newly added entries
    metadataLibraryFilter: [ ]  # listen to all events if empty
    metadataSeriesExcludeFilter: [ ]
    notificationsLibraryFilter: [ ] # Will send notifications if any notification source is enabled. If empty will send notifications for all libraries
  metadataUpdate:
    default:
      libraryType: "MANGA" # Can be "MANGA", "NOVEL", "COMIC" or "WEBTOON". Hint to help better match book numbers
      updateModes: [ API ] # can use multiple options at once. available options are API, COMIC_INFO
      aggregate: false # if enabled will search and aggregate metadata from all configured providers
      mergeTags: false # if true and aggregate is enabled will merge tags from all providers
      mergeGenres: false # if true and aggregate is enabled will merge genres from all providers
      bookCovers: false # update book thumbnails
      seriesCovers: false # update series thumbnails
      overrideExistingCovers: true # if false will upload but not select new cover if another cover already exists
      overrideComicInfo: false # Replace existing ComicInfo file. If false, only append additional data
      postProcessing:
        seriesTitle: false # update series title
        seriesTitleLanguage: "en" # series title update language. If empty chose first matching title
        fallbackToAltTitle: false # fallback to first alternative tile if series title is not found
        alternativeSeriesTitles: false # use other title types as alternative title option
        alternativeSeriesTitleLanguages: # alternative title languages
          - "en"
          - "ja"
          - "ja-ro"
        orderBooks: false # will order books using parsed volume or chapter number
        scoreTagName: "score" # adds score tag of specified format e.g. "score: 8" only uses integer part of rating. Can be used in search using query: tag:"score: 8" in komga
        readingDirectionValue: # override reading direction for all series. should be one of these: LEFT_TO_RIGHT, RIGHT_TO_LEFT, VERTICAL, WEBTOON
        languageValue: # set default language for series. Must use BCP 47 format e.g. "en"
        #TagName: if specified and if provider has data about publisher in that language then additional tag will be added using format ({TagName}: publisherName)
        #e.g. originalPublisherTagName: "Original Publisher" will add tag "Original Publisher: Shueisha"
        originalPublisherTagName:
        #publisherTagNames:
        #  - tagName: "English Publisher"
        #    language: "en"

kavita:
  baseUri: "http://localhost:5000" #or env:KOMF_KAVITA_BASE_URI
  apiKey: "16707507-d05d-4696-b126-c3976ae14ffb" #or env:KOMF_KAVITA_API_KEY
  apiRateLimit:
    updateEventsPerMinute: 30 #or env:KOMF_KAVITA_API_RATE_LIMIT_UPDATE_EVENTS_PER_MINUTE
    scanEventsPerMinute: 5 #or env:KOMF_KAVITA_API_RATE_LIMIT_SCAN_EVENTS_PER_MINUTE
  scan:
    deferredLibraryScanDelaySeconds: 300 #or env:KOMF_KAVITA_SCAN_DEFERRED_LIBRARY_SCAN_DELAY_SECONDS
    waitForActiveScanToFinish: true #or env:KOMF_KAVITA_SCAN_WAIT_FOR_ACTIVE_SCAN_TO_FINISH
    activeScanWaitTimeoutSeconds: 1800 #or env:KOMF_KAVITA_SCAN_ACTIVE_SCAN_WAIT_TIMEOUT_SECONDS
    activeScanPollIntervalSeconds: 2 #or env:KOMF_KAVITA_SCAN_ACTIVE_SCAN_POLL_INTERVAL_SECONDS
    pauseOnActiveScan: true #application.yml only currently
    resumeQuietPeriodSeconds: 30 #application.yml only currently
    pauseTimeoutSeconds: 1800 #application.yml only currently
    pollIntervalSeconds: 2 #application.yml only currently
  safeFullLibrary:
    enabled: false #or env:KOMF_KAVITA_SAFE_FULL_LIBRARY_ENABLED
    scanPolicy: NONE #or env:KOMF_KAVITA_SAFE_FULL_LIBRARY_SCAN_POLICY. NONE or LIBRARY_END
    failFastOnSqliteErrors: true #or env:KOMF_KAVITA_SAFE_FULL_LIBRARY_FAIL_FAST_ON_SQLITE_ERRORS
    applyMode: CORE #or env:KOMF_KAVITA_SAFE_FULL_LIBRARY_APPLY_MODE

# Note: active-scan waiting relies on Kavita event listener updates, so keep `kavita.eventListener.enabled: true`
# when using scan safety guards.
  eventListener:
    enabled: false # if disabled will not connect to kavita and won't pick up newly added entries
    metadataLibraryFilter: [ ]  # listen to all events if empty
    metadataSeriesExcludeFilter: [ ]
    notificationsLibraryFilter: [ ] # Will send notifications if any notification source is enabled. If empty will send notifications for all libraries
  metadataUpdate:
    default:
      libraryType: "MANGA" # Can be "MANGA", "NOVEL", "COMIC" or "WEBTOON". Hint to help better match book numbers
      updateModes: [ API ] # can use multiple options at once. available options are API, COMIC_INFO
      aggregate: false # if enabled will search and aggregate metadata from all configured providers
      mergeTags: false # if true and aggregate is enabled will merge tags from all providers
      mergeGenres: false # if true and aggregate is enabled will merge genres from all providers
      bookCovers: false #update book thumbnails
      seriesCovers: false #update series thumbnails
      overrideExistingCovers: true # if false will upload but not select new cover if another cover already exists
      lockCovers: true # legacy compatibility field; retained for older clients
      lockSeriesCover: true # split cover-lock control for series cover uploads
      lockVolumeCover: false # split cover-lock control for volume cover uploads
      postProcessing:
        seriesTitle: false #update series title
        seriesTitleLanguage: "en" # series title update language. If empty chose first matching title
        alternativeSeriesTitles: false # use other title types as alternative title option
        alternativeSeriesTitleLanguages: # alternative title language. Only first language is used. Use single value for consistency
          - "ja-ro"
        orderBooks: false # will order books using parsed volume or chapter number. works only with COMIC_INFO
        languageValue: # set default language for series. Must use BCP 47 format e.g. "en"

notifications:
  templatesDirectory: "./" # path to a directory with templates
  discord:
    # List of discord webhook urls. Will call these webhooks after series or books were added. 
    webhooks: # config example: webhooks: ["https://discord.com/api/webhooks/9..."] (env:KOMF_DISCORD_WEBHOOKS - comma separated list of webhooks)
    seriesCover: false # include series cover in message
    embedColor: "1F8B4C"
  apprise:
    # List of apprise urls. Will call these after series or books were added. 
    urls:
    seriesCover: false # include series cover as attachment

database:
  file: ./database.sqlite # database file location.

metadataProviders:
  malClientId: "" # required for mal provider. See https://myanimelist.net/forum/?topicid=1973077 env:KOMF_METADATA_PROVIDERS_MAL_CLIENT_ID
  comicVineApiKey: # required for comicVine provider https://comicvine.gamespot.com/api/ env:KOMF_METADATA_PROVIDERS_COMIC_VINE_API_KEY
  comicVineSearchLimit: # define ComicVine search result Limit, default is 10
  comicVineIssueName: # string that contains "{number}" which will be replaced by the issue number ie. "Issue #{number}". Used when an issue has no name on ComicVine, default is null
  comicVineIdFormat: # string that contains "{id}" which will serve to parse the ComicVine volume of a given book from its title or folder name ie. "[cv-{id}]" which will correctly identify '.../Uncanny X-Men Omnibus (2006) [cv-27512]' as being [4050-27512](https://comicvine.gamespot.com/uncanny-x-men-omnibus/4050-27512/)
  bangumiToken: # bangumi provider require a token to show nsfw items https://next.bgm.tv/demo/access-token  env:KOMF_METADATA_PROVIDERS_BANGUMI_TOKEN
  defaultProviders:
    mangaUpdates:
      priority: 10
      enabled: true
      mediaType: "MANGA" # filter used in matching. Can be NOVEL, MANGA or WEBTOON. MANGA type includes everything except novels
      authorRoles: [ "WRITER" ] # roles that will be mapped to author role
      artistRoles: [ "PENCILLER","INKER","COLORIST","LETTERER","COVER" ] # roles that will be mapped to artist role
    mal:
      priority: 20
      enabled: false
      mediaType: "MANGA" # filter used in matching. Can be NOVEL, MANGA or WEBTOON. MANGA type includes everything except novels
    nautiljon:
      priority: 30
      enabled: false
    aniList:
      priority: 40
      enabled: false
      mediaType: "MANGA" # filter used in matching. Can be NOVEL, MANGA or WEBTOON. MANGA type includes everything except novels
      tagsScoreThreshold: 60 # tags with this score or higher will be included
      tagsSizeLimit: 15 # amount of tags that will be included
    yenPress:
      priority: 50
      enabled: false
      mediaType: "MANGA" # filter used in matching. Can be NOVEL or MANGA.
    kodansha:
      priority: 60
      enabled: false
    viz:
      priority: 70
      enabled: false
    bookWalker:
      priority: 80
      enabled: false
      mediaType: "MANGA" # filter used in matching. Can be NOVEL, MANGA or WEBTOON.
    mangaDex:
      priority: 90
      enabled: false
      coverLanguages:
        - "en"
        - "ja"
    bangumi: # Chinese metadata provider. https://bgm.tv/
      priority: 100
      enabled: false
    comicVine: # https://comicvine.gamespot.com/ requires API key. Experimental provider, can mismatch issue numbers
      priority: 110
      enabled: false
    hentag:
      priority: 120
      enabled: false
    webtoons:
      priority: 130
      enabled: false
    mangaBaka:
      priority: 140
      enabled: false
      # Datasource used for metadata retrieval. DATABASE mode will only work if MangaBaka database is installed
      # API or DATABASE 
      mode: API

server:
  port: 8085 # or env:KOMF_SERVER_PORT
  kavitaOnly: true # or env:KOMF_SERVER_KAVITA_ONLY. If true, Komga routes are disabled.
  metadataRequestsPerMinute: 0 # or env:KOMF_SERVER_METADATA_REQUESTS_PER_MINUTE. 0 disables limiter for metadata HTTP routes.

logLevel: INFO # or env:KOMF_LOG_LEVEL
```

### Kavita event-listener behavior

- Keep `kavita.eventListener.enabled: true` if you want KOMF to react automatically to Kavita-side changes.
- If `kavita.scan.pauseOnActiveScan` is enabled, this fork may still start the Kavita listener machinery for scan-safety tracking even when metadata auto-listening is otherwise disabled.
- The listener is used for:
  - automatic pickup of newly added series after Kavita scans
  - automatic processing of series updates detected during scan completion
  - active-scan tracking for safer deferred scan/write behavior
  - notification triggers when notification outputs are configured
- In current Kavita nightly behavior, new-series detection is handled from `SeriesAdded` events.
- Existing-series scan changes are still processed from scan-progress completion plus collected update events.
- If the event listener is disabled, manual identify/match/reset still work, but automatic "Kavita scanned new content, now KOMF should react" behavior will not.

## Metadata update config for a library

### Kavita rate-limit guidance (SQLite-safe default)

- Default values in this repo are intentionally conservative for SQLite-backed deployments:
  - `updateEventsPerMinute: 30`
  - `scanEventsPerMinute: 5`
  - `deferredLibraryScanDelaySeconds: 300`
- This is especially important when migrating to a new system/platform where you may need a fresh instance and cannot rely on prior DB state.
- If your system is stable and storage is fast (e.g., local NVMe), you can increase these gradually.
- For large library-wide runs, prefer quiet windows (avoid overlapping heavy scan/update jobs).
- Per-series operations are usually lower risk than full-library runs.

### Safe full-library mode

- `kavita.safeFullLibrary.enabled: true` enables extra protection for full-library runs.
- The main intent is to reduce risky write/scan overlap on Kavita SQLite deployments.
- `scanPolicy: NONE`
  - do not force an additional library-end scan policy change beyond the normal hardened behavior
- `scanPolicy: LIBRARY_END`
  - suppress per-series end scans during a full-library run and issue a single library scan at the end
- `applyMode`
  - defines the default apply mode used when a client omits `applyMode`
  - the default in this fork is `CORE`
- `failFastOnSqliteErrors`
  - aborts quickly when Kavita returns known SQLite/corruption-like server errors instead of retrying through them

### Scan-safety knobs

- `kavita.scan.waitForActiveScanToFinish`
  - delays sensitive work until Kavita scan/maintenance activity settles
- `kavita.scan.pauseOnActiveScan`
  - pauses queued Kavita writes while active scan/maintenance work is detected
- `kavita.scan.resumeQuietPeriodSeconds`
  - requires a quiet period after activity ends before writes resume
- `kavita.scan.pauseTimeoutSeconds`
  - maximum time the write gate will stay paused before continuing
- `kavita.scan.pollIntervalSeconds`
  - polling interval used by the pause gate
- These are primarily useful on SQLite-backed Kavita instances where overlapping work can be noisy or risky.

You can configure a set of metadata update options that will only be used with specified library. If no options are
specified for a library
then default options will be used. kavita or komga library ids are used as library identifiers

```yaml
komga_or_kavita:
  metadataUpdate:
    default:
      aggregate: false
    library:
      09PERX1TW8GEK:
        updateModes: [ API ]
        aggregate: false
        bookCovers: false
        seriesCovers: false
        postProcessing:
          seriesTitle: false
          titleType: LOCALIZED
          alternativeSeriesTitles: false
          languageValue:
      123:
        aggregate: true
        seriesCovers: true
```

## Providers config for a library

You can configure a set of metadata providers that will only be used with specified library. If no providers are
specified for a library
then default providers will be used. kavita or komga library ids are used as library identifiers

```yaml
metadataProviders:
  defaultProviders:
    mangaUpdates:
      priority: 10
      enabled: true
  libraryProviders:
    09PERX1TW8GEK:
      mangaUpdates:
        priority: 10
        enabled: true
      bookWalker:
        priority: 20
        enabled: true
    123:
      aniList:
        priority: 10
        enabled: true
      mal:
        priority: 20
        enabled: true
```

## Metadata aggregation

By default, all metadata will be fetched from the first positive match in configured providers by order of priority. If
you want to enable metadata aggregation from multiple sources you need to set `aggregateMetadata` to true in the config.

If enabled, initial metadata will be taken from the first positive match in configured providers. Additional search
request will be made to all the other configured providers and metadata will be aggregated from the results. Metadata
fields will only be set from another provider if previous provider did not have any data for that particular field. For
example provider1 did not return thumbnail in that case thumbnail will be taken from provider2

You can configure which fields each provider will have in the config both for series and books. By default, all
available fields will be fetched. Example of default fields configuration

```yml
metadataProviders:
  default:
    mangaUpdates:
      priority: 10
      enabled: true
      authorRoles: [ "WRITER" ]
      artistRoles: [ "PENCILLER","INKER","COLORIST","LETTERER","COVER" ]
      seriesMetadata:
        status: true
        title: true
        titleSort: true
        summary: true
        publisher: true
        readingDirection: true
        ageRating: true
        language: true
        genres: true
        tags: true
        totalBookCount: true
        authors: true
        thumbnail: true
        releaseDate: true
        links: true
        score: true
        books: true
        useOriginalPublisher: true # prefer original publisher and volume information if source has data about multiple providers. If false will use english or other available publisher
      bookMetadata:
        title: true
        summary: true
        number: true
        numberSort: true
        releaseDate: true
        authors: true
        tags: true
        isbn: true
        links: true
        thumbnail: true
```

If you want to disable particular field you just need to set the field value to false

```yml
metadataProviders:
  default:
    mangaUpdates:
      priority: 10
      enabled: true
      seriesMetadata:
        thumbnail: false
```

## Notifications

if any webhook urls are specified then after new book is added a call to webhooks will be triggered. You can change
message format by providing your own template files and specifying directory path in `templatesDirectory/discord` or
`templatesDirectory/apprise`.
For docker deployments templates should be
placed in mounted `/config/<discord or apprise>` directory without specifying `templatesDirectory`

### Discord template file names:

- title.vm
- title_url.vm
- description.vm
- footer.vm
- field_<index>_name<_inline>.vm
- field_<index>_value.vm

### Apprise template file names:

- apprise_title.vm
- apprise_body.vm

Templates are written using Apache Velocity ([link to docs](https://velocity.apache.org/engine/2.3/user-guide.html)).

```velocity
## Example of the default description template
**$series.name**

#if ($series.metadata.summary != "")
    $series.metadata.summary

#end
#if($books.size() == 1)
***new book was added to library $library.name:***
#else
***new books were added to library $library.name:***
#end
#foreach ($book in $books)
**$book.name**
#end
```

### Template variables
```typescript
// Variables available in templates:
interface Webhook {
    library: {
        id: string,
        name: string
    },
    series: {
        id: string,
        name: string,
        bookCount: number,
        metadata: {
            status: string,
            title: string,
            titleSort: string,
            alternativeTitles: { label: string, title: string }[],
            summary: string,
            readingDirection?: string,
            publisher?: string,
            alternativePublishers: string[],
            ageRating?: number,
            language?: string,
            genres: string[],
            tags: string[],
            totalBookCount?: number,
            authors: { name: string, role: string }[],
            releaseYear: number,
            liks: { label: string, url: string }[],
        }
    },
    books: {
        id: string,
        name: string,
        number: int,
        metadata: {
            title: string,
            summary: string,
            number: string,
            releaseDate: string,
            authors: { name: string, role: string }
            tags: string[],
            isbn?: string,
            links: { label: string, url: string }[]
        }
    }[],
    mediaServer: string //can be `KOMGA` or `KAVITA`
}
```

## HTTP Endpoints

In this fork, prefer Kavita endpoints (`/kavita/...`) because Kavita-only mode is the default runtime path.

### Providers

Use the following HTTP endpoints to get information about enabled metadata providers:

- `GET /{media-server}/providers`: list of enabled metadata providers. Optional `libraryId` parameter can be used for
  library providers.

### Search

Use the following HTTP endpoint to search for metadata:

- `GET /{media-server}/search?name=...`: search results from enabled metadata providers. Optional `libraryId` parameter
  can be used for library providers.

### Identify

Use the following HTTP endpoint to set series metadata from specified provider:

- `POST /{media-server}/identify`:

```json
{
  "libraryId": "09TDSWK3Q0XRA",
  "seriesId": "07XF6HKAWHHV4",
  "provider": "MANGA_UPDATES",
  "providerSeriesId": "1"
}

```

- `POST /{media-server}/match/library/{libraryId}/series/{seriesId}`: Attempts to match the specified series in the
  specified library. Optional query `applyMode=CORE|CHAPTERS|FULL`. If omitted, the server uses the configured default
  apply mode. In this fork, the effective default is `CORE`, and `CORE` is the recommended mode for normal Kavita runs
  because it skips chapter writes while still applying series metadata, series cover, and volume covers.
- `POST /{media-server}/match/library/{libraryId}`: Attempts to match all series in the specified library.
  Optional query `dryRun=true` will only log planned matches/updates without writing metadata or triggering scans.
  Optional query `applyMode=CORE|CHAPTERS|FULL`. If omitted, this fork defaults to `CORE`.
- `GET /{media-server}/summary/library/{libraryId}/latest`: latest run summary for the library.
- `GET /{media-server}/summary/library/{libraryId}?limit=10`: recent run summary history.
- `GET /{media-server}/control/library/{libraryId}/status`: active/paused/stop/checkpoint status for a library run.
- `POST /{media-server}/control/library/{libraryId}/pause`: pause the active library run.
- `POST /{media-server}/control/library/{libraryId}/resume?mode=CONTINUE|NEW`: resume a paused/checkpointed run.
- `POST /{media-server}/control/library/{libraryId}/stop`: request graceful stop for the active library run.
- `POST /{media-server}/reset/library/{libraryId}/series/{seriesId}`: Resets all metadata for the specified series in
  the specified library.
- `POST /{media-server}/reset/library/{libraryId}`: Resets all metadata for all series in the specified library.

### Apply modes summary

- `CORE`
  - writes series metadata
  - uploads/selects series covers when enabled
  - uploads/selects volume covers when enabled
  - skips chapter metadata writes
  - is the default/recommended mode in this fork
- `CHAPTERS`
  - only writes chapter metadata
  - does not perform core series/cover writes
- `FULL`
  - combines `CORE` and `CHAPTERS`

For Kavita users who mainly want series metadata and cover updates without chapter-level churn, use `CORE` or rely on the fork default by omitting `applyMode`.

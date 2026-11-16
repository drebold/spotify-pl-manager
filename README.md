# Spotify Playlist Manager

An Android app for managing your Spotify library while listening. See what's playing, move tracks between playlists, remove them from their source, or skip — all without leaving the app.

## Features

- Displays the currently playing track (album art, title, artists, source context)
- Move a track from its current source directly into a chosen target playlist
- Delete a track from its current source (playlist or Liked Songs)
- Skip to the next track
- Polling keeps the now-playing view in sync every 30 seconds while the app is in the foreground
- Persists your last-selected target playlist across sessions

## Requirements

- Spotify Premium account (playback control and queue APIs require Premium)
- Android 8.0+ (API 26)

## Setup

1. Register an application at [developer.spotify.com](https://developer.spotify.com) and add `myapp://callback` as a Redirect URI.
2. Replace `CLIENT_ID` in `AuthManager.kt` with your application's Client ID.
3. Build and run with Android Studio.

---

## Architecture

```
app/
├── auth/           AuthManager          OAuth 2.0 PKCE flow, token lifecycle
├── api/            SpotifyApi           Retrofit interface + models + interceptors
├── domain/         TrackMover           Move/delete business logic
│                   SpotifyRepository    Abstraction over the API layer
├── data/           TokenStorage         Encrypted token persistence
│                   PrefsStorage         Last-selected playlist preference
└── ui/             MainViewModel        State machine driving the UI
                    MainScreen           Compose UI
```

The app follows a standard MVVM layering. The UI layer only talks to `MainViewModel`; the ViewModel coordinates the domain and repository layers; the repository layer wraps the Retrofit API surface.

---

## API Calls & Workflow

### Authentication — OAuth 2.0 with PKCE

PKCE (Proof Key for Code Exchange) is used so no client secret needs to be embedded in the APK.

**Login flow:**
1. `AuthManager.startAuth()` generates a random 96-byte `code_verifier`, derives a SHA-256 `code_challenge` from it, and opens Spotify's authorization endpoint in a Chrome Custom Tab:
   ```
   GET https://accounts.spotify.com/authorize
     ?client_id=...
     &response_type=code
     &redirect_uri=myapp://callback
     &scope=...
     &code_challenge_method=S256
     &code_challenge=<sha256(verifier)>
     &state=<random 16-byte hex>
   ```
   Requested scopes: `user-read-playback-state`, `user-modify-playback-state`, `playlist-read-private`, `playlist-read-collaborative`, `playlist-modify-private`, `playlist-modify-public`, `user-library-modify`, `user-library-read`.

2. Spotify redirects back to `myapp://callback?code=...&state=...`. `MainActivity.onNewIntent()` / `onCreate()` intercepts the URI and calls `AuthManager.handleCallback()`.

3. `handleCallback()` verifies the returned `state` matches, then POSTs to Spotify's token endpoint:
   ```
   POST https://accounts.spotify.com/api/token
   Body: grant_type=authorization_code
         code=<auth code>
         redirect_uri=myapp://callback
         client_id=...
         code_verifier=<original verifier>
   ```
   The response contains `access_token` (valid for 1 hour), `refresh_token`, and `expires_in`. Tokens are stored in `TokenStorage` with the expiry timestamp set 60 seconds early as a safety margin.

**Token refresh:**  
Before every API call, `AuthInterceptor` calls `AuthManager.getValidAccessToken()`. If the stored token is expired (or within the 60-second margin), `refreshAccessToken()` is called automatically:
```
POST https://accounts.spotify.com/api/token
Body: grant_type=refresh_token
      refresh_token=<stored token>
      client_id=...
```
The new access token (and potentially a new refresh token) is saved back to storage. A `400` response means the refresh token has been revoked — `TokenStorage.clear()` is called, requiring the user to log in again.

---

### Startup — Loading Playlists

After a successful login (or on cold start if already authenticated):

1. **`GET /v1/me`** — Fetches the current user's `id`. This is used to filter playlists to only those the user owns or collaborates on.

2. **`GET /v1/me/playlists?limit=50&offset=N`** — Paginated fetch of all user playlists. The app loops, incrementing `offset` by 50, until the response's `next` field is `null`. Only playlists where `owner.id == userId` or `collaborative == true` are kept.

3. The last-selected target playlist ID is restored from `PrefsStorage`. If it still exists in the loaded list, its full track URI set is pre-fetched (see below).

---

### Now Playing — Polling

`MainViewModel.startPolling()` fires every 30 seconds while the app is in the foreground (paused on `ON_PAUSE`, resumed on `ON_RESUME`). The first poll is delayed 2 seconds to avoid racing with the playlist load.

**`GET /v1/me/player`** — Returns the full playback state:
- `item`: the current `TrackObject` (id, uri, name, artists, album, album art images)
- `context`: the playback source — `type` (`"playlist"`, `"album"`, `"collection"`) and `uri` (e.g. `spotify:playlist:37i9dQZF...`)
- `is_playing`, `shuffle_state`, `progress_ms`

The ViewModel parses the context URI to extract a playlist ID when applicable. If the source is a playlist that exists in the loaded playlist list, `GET /v1/playlists/{id}/items?limit=1&offset=0` is called once to get the total track count — only when the source playlist changes, not on every poll.

A `204 No Content` or empty body means nothing is playing; the UI clears the now-playing card.

---

### Selecting a Target Playlist

When the user picks a target playlist from the bottom sheet:

**`GET /v1/playlists/{id}/items?limit=50&offset=N`** (paginated) — All track URIs in the target playlist are fetched and cached in memory (`targetPlaylistUris: Set<String>`). This cache is keyed by playlist ID and avoids repeat fetches unless the selection changes.

The URI set is used at move-time to skip the duplicate-add API call if the track is already present.

---

### Move Operation

Triggered by the **Move** button. The operation runs with `isOperationInProgress = true` to disable all buttons until it completes.

1. **Refresh now playing** — `GET /v1/me/player` is called immediately before acting to get the freshest track and context, avoiding stale state from the polling lag.

2. **Duplicate check** — The track's URI is looked up in the cached `targetPlaylistUris`. If not present:
   - **`POST /v1/playlists/{targetId}/items`**
     ```json
     { "uris": ["spotify:track:..."] }
     ```
     Returns a `snapshot_id`. A blank snapshot ID is treated as a failure.

3. **Remove from source** — Depends on the playback context:
   - **Playlist source:**
     - **`GET /v1/playlists/{sourceId}/items?limit=1&offset=0`** — Fetches the current `snapshot_id` of the source playlist. Spotify uses snapshot IDs to guard concurrent modifications.
     - **`DELETE /v1/playlists/{sourceId}/items`**
       ```json
       {
         "items": [{ "uri": "spotify:track:..." }],
         "snapshot_id": "..."
       }
       ```
   - **Liked Songs source:**
     - **`DELETE /v1/me/tracks`**
       ```json
       { "ids": ["<trackId>"] }
       ```
   - **Album / unknown source:** No removal is performed. The track is added to the target only, and the user is informed via snackbar.

4. **Result:**
   - `Success` — added and removed.
   - `AlreadyExists` — track was already in the target; source removal still proceeds.
   - `AddOnlySuccess` — added but source could not be removed (e.g. playing from an album).
   - `Failure` — add step failed (e.g. permission denied on the target playlist).

After the operation, `targetPlaylistUris` is updated in memory (adds the new URI) and a fresh now-playing poll fires immediately.

---

### Delete Operation

Triggered by the **Delete** button (only enabled when playing from a playlist or Liked Songs).

1. **Refresh now playing** — same pre-action refresh as Move.
2. **Remove from source** — identical logic to the remove step in Move, but no add step precedes it.
3. The source track count is decremented optimistically in the UI.

---

### Skip Operation

**`POST /v1/me/player/next`** — Tells Spotify to advance to the next track. After a 600 ms delay (to let Spotify's player state update), a now-playing refresh fires.

Requires Spotify Premium; a `403` response surfaces a "Spotify Premium required" message.

---

### HTTP Layer

**`AuthInterceptor`** — Runs on every request. Calls `getValidAccessToken()` (which refreshes silently if needed) and injects `Authorization: Bearer <token>`.

**`RetryInterceptor`** — Handles two error cases:
- **429 Too Many Requests:** Reads the `Retry-After` header. If the delay is ≤ 60 seconds, it sleeps and retries up to 4 times with exponential backoff. If `Retry-After` exceeds 60 seconds, the error surfaces immediately rather than blocking the UI thread.
- **401 Unauthorized:** Refreshes the access token and retries the request once.

Both interceptors are added to the OkHttp client in order: `AuthInterceptor` → `RetryInterceptor` → logging.

---

## Tech Stack

| Layer | Library |
|---|---|
| UI | Jetpack Compose |
| State | ViewModel + StateFlow |
| HTTP | Retrofit 2 + OkHttp |
| Serialization | kotlinx.serialization |
| Auth browser | AndroidX Browser (Custom Tabs) |
| Token storage | EncryptedSharedPreferences |

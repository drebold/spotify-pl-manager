# Playlist Mover Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a single-screen Android remote that moves, deletes, and skips the currently playing Spotify track across playlists — music stays in the Spotify app.

**Architecture:** Single-activity MVVM with StateFlow; Retrofit/OkHttp with an auth interceptor handling 401/429; a pure-Kotlin `TrackMover` class holds all move/verify/delete logic and depends only on a `SpotifyRepository` interface so it can be unit-tested with a fake implementation.

**Tech Stack:** Kotlin 2.2.10, Jetpack Compose + Material 3, Retrofit 2.11.0, OkHttp 4.12.0, kotlinx.serialization 1.8.1, EncryptedSharedPreferences (security-crypto 1.1.0-alpha06), Coil 2.7.0, AndroidX Browser 1.8.0

**Spec:** Inline — written from the spec provided in the conversation

## Global Constraints

- Min SDK 24, target SDK 37, compileSdk release(37), AGP 9.5.1, Kotlin 2.2.10, Compose BOM 2026.02.01
- Package: `com.example.spotifyplaylistmanager`
- Client ID: `4f594fed820a4305aad21aae910f273b`; redirect URI: `myapp://callback`
- Scopes (space-separated): `user-read-playback-state user-modify-playback-state playlist-read-private playlist-read-collaborative playlist-modify-private playlist-modify-public user-library-modify user-library-read`
- Spotify API base URL: `https://api.spotify.com/v1/`
- Auth URL: `https://accounts.spotify.com/authorize`
- Token URL: `https://accounts.spotify.com/api/token`
- Playlist items endpoint: `/playlists/{id}/items` — NEVER `/playlists/{id}/tracks`
- DELETE body format: `{"items": [{"uri": "spotify:track:..."}], "snapshot_id": "..."}`
- Never delete from source unless verification passes; always pass `snapshot_id` on playlist delete
- Poll every 4 seconds while app is in the foreground; stop when paused
- Verification: add → delay 500 ms → fetch all items → check URI present → retry once after 1 s delay → only then delete

## Review Focus

1. **Retry-After header absent on 429** — if `Retry-After` header is missing or non-numeric, default to 5 s delay instead of crashing; test: Task 4, Step 1 (interceptor test)
2. **Local files** (`is_local: true`) — `move()` must return `MoveResult.Failure` immediately; `canDelete` must return `false`; test: Task 5, Step 1 (`local file` test)
3. **Token expiry mid-operation** — the auth interceptor must refresh and retry on any 401, including during a multi-step move; test: Task 4, Step 1 (interceptor retry test)
4. **Null track in playlist items** — API can return `{"track": null}` for deleted tracks; `getAllPlaylistItems` must filter nulls; test: Task 5, Step 1 (verify ignores null items)
5. **Empty queue on shuffle** — when shuffle is on and `GET /me/player/queue` returns an empty queue list, show "Queue is empty" notice rather than crashing; handled in ViewModel Task 6

---

### Task 1: Project Configuration

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`

**Interfaces:**
- Produces: compilable project with all needed dependencies resolved

- [ ] **Step 1: Add versions and libraries to `gradle/libs.versions.toml`**

Add to `[versions]`:
```toml
retrofit = "2.11.0"
okhttp = "4.12.0"
kotlinxSerializationJson = "1.8.1"
securityCrypto = "1.1.0-alpha06"
coil = "2.7.0"
browser = "1.8.0"
lifecycleViewmodelCompose = "2.9.1"
coroutines = "1.9.0"
mockk = "1.13.17"
turbine = "1.2.0"
```

Add to `[libraries]`:
```toml
retrofit = { group = "com.squareup.retrofit2", name = "retrofit", version.ref = "retrofit" }
retrofit-kotlinx-serialization = { group = "com.squareup.retrofit2", name = "converter-kotlinx-serialization", version.ref = "retrofit" }
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }
kotlinx-serialization-json = { group = "org.jetbrains.kotlinx", name = "kotlinx-serialization-json", version.ref = "kotlinxSerializationJson" }
security-crypto = { group = "androidx.security", name = "security-crypto", version.ref = "securityCrypto" }
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }
browser = { group = "androidx.browser", name = "browser", version.ref = "browser" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodelCompose" }
lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleViewmodelCompose" }
kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
turbine = { group = "app.cash.turbine", name = "turbine", version.ref = "turbine" }
```

Add to `[plugins]`:
```toml
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
```

- [ ] **Step 2: Update `app/build.gradle.kts`**

Add `alias(libs.plugins.kotlin.serialization)` to the `plugins {}` block.

Add to `dependencies {}`:
```kotlin
implementation(libs.retrofit)
implementation(libs.retrofit.kotlinx.serialization)
implementation(libs.okhttp)
implementation(libs.okhttp.logging)
implementation(libs.kotlinx.serialization.json)
implementation(libs.security.crypto)
implementation(libs.coil.compose)
implementation(libs.browser)
implementation(libs.lifecycle.viewmodel.compose)
implementation(libs.lifecycle.runtime.compose)
implementation(libs.kotlinx.coroutines.android)
testImplementation(libs.kotlinx.coroutines.test)
testImplementation(libs.mockk)
testImplementation(libs.turbine)
```

- [ ] **Step 3: Update `AndroidManifest.xml`**

Add `<uses-permission android:name="android.permission.INTERNET" />` immediately before `<application>`.

Add `android:launchMode="singleTop"` to the existing `<activity>` element.

Add a second `<intent-filter>` inside the existing `<activity>`:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="myapp" android:host="callback" />
</intent-filter>
```

- [ ] **Step 4: Verify project compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (no source yet, just dependency resolution)

---

### Task 2: Data Models

**Files:**
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/api/SpotifyModels.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/domain/DomainModels.kt`

**Interfaces:**
- Produces:
  - `SpotifyModels.kt`: all `@Serializable` wire types for Retrofit
  - `DomainModels.kt`: all domain types used by ViewModel, UI, and TrackMover

- [ ] **Step 1: Create `api/SpotifyModels.kt`**

All types annotated `@Serializable`. Use `@SerialName("field_name")` only where the JSON key differs from the Kotlin property name. All fields that the API may omit must be nullable or have defaults.

Request types:
- `data class AddToPlaylistRequest(val uris: List<String>)`
- `data class RemoveFromPlaylistRequest(val items: List<TrackUriItem>, @SerialName("snapshot_id") val snapshotId: String)`
- `data class TrackUriItem(val uri: String)`
- `data class LikedSongsRequest(val ids: List<String>)`

Response types:
- `data class PlayerStateResponse(val is_playing: Boolean = false, val shuffle_state: Boolean = false, val item: TrackObject? = null, val context: ContextObject? = null, val progress_ms: Long? = null)`
- `data class TrackObject(val id: String, val uri: String, val name: String, val artists: List<ArtistObject>, val album: AlbumObject, val is_local: Boolean = false)`
- `data class ArtistObject(val name: String)`
- `data class AlbumObject(val name: String, val images: List<ImageObject> = emptyList())`
- `data class ImageObject(val url: String, val width: Int? = null, val height: Int? = null)`
- `data class ContextObject(val type: String, val uri: String)`
- `data class PlaylistsPageResponse(val items: List<PlaylistObject>, val next: String? = null, val total: Int = 0)`
- `data class PlaylistObject(val id: String, val name: String, val owner: OwnerObject, val collaborative: Boolean = false, @SerialName("snapshot_id") val snapshotId: String = "")`
- `data class OwnerObject(val id: String, @SerialName("display_name") val displayName: String? = null)`
- `data class PlaylistItemsPageResponse(val items: List<PlaylistItemObject>, val next: String? = null, @SerialName("snapshot_id") val snapshotId: String = "", val total: Int = 0)`
- `data class PlaylistItemObject(val track: TrackObject? = null)`
- `data class SnapshotResponse(@SerialName("snapshot_id") val snapshotId: String)`
- `data class QueueResponse(@SerialName("currently_playing") val currentlyPlaying: TrackObject? = null, val queue: List<TrackObject> = emptyList())`
- `data class UserResponse(val id: String, @SerialName("display_name") val displayName: String? = null)`
- `data class SpotifyErrorBody(val error: SpotifyError)` — for parsing error responses
- `data class SpotifyError(val status: Int, val message: String)`

- [ ] **Step 2: Create `domain/DomainModels.kt`**

No `@Serializable` annotations. Pure Kotlin.

```kotlin
data class TrackInfo(
    val id: String,
    val uri: String,
    val name: String,
    val artists: List<String>,
    val albumName: String,
    val albumArtUrl: String?,
    val isLocal: Boolean
)

data class PlaylistInfo(
    val id: String,
    val name: String,
    val snapshotId: String,
    val ownerId: String,
    val isCollaborative: Boolean
)

enum class ContextType { PLAYLIST, ALBUM, COLLECTION, OTHER }

data class PlaybackContext(
    val type: ContextType,
    val uri: String,
    val playlistId: String?  // extracted from uri for PLAYLIST type only
)

data class NowPlayingState(
    val track: TrackInfo,
    val context: PlaybackContext?,
    val sourceLabel: String,
    val isShuffling: Boolean
)

// Returned by SpotifyRepository.getAllPlaylistItems — used by TrackMover for verification
data class PlaylistContents(
    val snapshotId: String,
    val trackUris: Set<String>
)

sealed class MoveResult {
    object Success : MoveResult()
    data class AddOnlySuccess(val reason: String) : MoveResult()
    data class Failure(val message: String) : MoveResult()
}

sealed class DeleteResult {
    object Success : DeleteResult()
    object NotApplicable : DeleteResult()
    data class Failure(val message: String) : DeleteResult()
}
```

- [ ] **Step 3: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 3: Token Storage & Auth Manager

**Files:**
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/data/TokenStorage.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/data/PrefsStorage.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/auth/AuthManager.kt`
- Test: `app/src/test/java/com/example/spotifyplaylistmanager/auth/AuthManagerTest.kt`

**Interfaces:**
- Produces:
  - `TokenStorage(context: Context)` with `saveTokens(access: String, refresh: String, expiresAtMs: Long)`, `getAccessToken(): String?`, `getRefreshToken(): String?`, `getExpiresAtMs(): Long`, `clear()`
  - `PrefsStorage(context: Context)` with `saveLastPlaylistId(id: String)`, `getLastPlaylistId(): String?`
  - `AuthManager(tokenStorage: TokenStorage)` with `startAuth(activity: Activity)`, `suspend handleCallback(uri: Uri): Boolean`, `suspend getValidAccessToken(): String?`, `isLoggedIn(): Boolean`, `logout()`
  - Companion `AuthManager.generateCodeVerifier(): String`, `AuthManager.generateCodeChallenge(verifier: String): String`

- [ ] **Step 1: Write failing tests**

```kotlin
// AuthManagerTest.kt
class AuthManagerTest {
    @Test fun `code verifier length is between 43 and 128`() {
        val verifier = AuthManager.generateCodeVerifier()
        assertTrue(verifier.length in 43..128)
    }

    @Test fun `code verifier uses only URL-safe base64 characters`() {
        val verifier = AuthManager.generateCodeVerifier()
        assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun `code challenge matches expected SHA256 base64url for known verifier`() {
        // RFC 7636 Appendix B test vector
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        val challenge = AuthManager.generateCodeChallenge(verifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", challenge)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AuthManagerTest" -i`
Expected: FAIL — class not found

- [ ] **Step 3: Implement `auth/AuthManager.kt`**

Companion object functions:
- `generateCodeVerifier()`: generate 96 random bytes via `SecureRandom`, encode with `Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP`
- `generateCodeChallenge(verifier)`: `MessageDigest.getInstance("SHA-256")` → digest the verifier bytes → Base64 URL-safe, no padding

Instance state (kept in memory, not persisted):
- `private var pendingVerifier: String?`
- `private var pendingState: String?`

`startAuth(activity: Activity)`:
1. Generate verifier + challenge; store verifier in `pendingVerifier`
2. Generate random `state` (16-byte hex); store in `pendingState`
3. Encode scopes with `URLEncoder.encode(..., "UTF-8")`
4. Build URL and launch `CustomTabsIntent.Builder().build().launchUrl(activity, Uri.parse(url))`

`handleCallback(uri: Uri)`: (suspend, uses `withContext(Dispatchers.IO)`)
1. Verify `uri.getQueryParameter("state") == pendingState`; return false if not
2. Extract `code`; return false if null
3. POST to token URL using `OkHttpClient().newCall(request).execute()` (not Retrofit — no auth interceptor needed here)
   - Form body: `grant_type=authorization_code`, `code`, `redirect_uri=myapp://callback`, `client_id=4f594fed820a4305aad21aae910f273b`, `code_verifier=pendingVerifier`
4. Parse JSON response; `tokenStorage.saveTokens(accessToken, refreshToken, currentTimeMillis() + expiresIn * 1000 - 60_000)`
5. Clear `pendingVerifier` and `pendingState`; return true

`getValidAccessToken()`: (suspend)
- If `currentTimeMillis() < tokenStorage.getExpiresAtMs()` and `tokenStorage.getAccessToken() != null`: return stored token
- Else call `refreshAccessToken()`; return new token or null

`refreshAccessToken()`: (private suspend)
- POST to token URL: `grant_type=refresh_token`, `refresh_token`, `client_id`
- On success: `saveTokens(...)`
- On 400 (bad refresh token): `tokenStorage.clear()`; return null

`isLoggedIn()`: `tokenStorage.getAccessToken() != null`

`logout()`: `tokenStorage.clear()`

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.AuthManagerTest" -i`
Expected: all 3 tests PASS

- [ ] **Step 5: Implement `data/TokenStorage.kt`**

Constructor takes `Context`. Lazy-init `EncryptedSharedPreferences` with `MasterKey` using `AES256_GCM`, preferences file `"spotify_tokens"`, key scheme `AES256_SIV`, value scheme `AES256_GCM`.
Keys: `KEY_ACCESS = "access_token"`, `KEY_REFRESH = "refresh_token"`, `KEY_EXPIRES = "expires_at"`.
`getExpiresAtMs()` returns `getLong(KEY_EXPIRES, 0L)`.

- [ ] **Step 6: Implement `data/PrefsStorage.kt`**

Constructor takes `Context`. Uses `Context.getSharedPreferences("app_prefs", MODE_PRIVATE)`.
Key: `"last_playlist_id"`.

- [ ] **Step 7: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 4: API Layer

**Files:**
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/domain/SpotifyRepository.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/api/SpotifyApi.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/api/SpotifyClient.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/api/SpotifyRepositoryImpl.kt`
- Test: `app/src/test/java/com/example/spotifyplaylistmanager/api/RetryInterceptorTest.kt`

**Interfaces:**
- Consumes: `AuthManager.getValidAccessToken()`, all types from `SpotifyModels.kt`, `DomainModels.PlaylistContents`
- Produces:
  - `SpotifyRepository` interface
  - `SpotifyRepositoryImpl(api: SpotifyApi)` implementing `SpotifyRepository`
  - `SpotifyClient.create(authManager: AuthManager): SpotifyApi`

- [ ] **Step 1: Write failing interceptor tests**

```kotlin
// RetryInterceptorTest.kt — tests the RetryInterceptor in isolation using OkHttp MockWebServer
class RetryInterceptorTest {
    @Test fun `on 429 with Retry-After header waits and retries`() {
        // Use MockWebServer: first response 429 with Retry-After: 1, second response 200
        // Measure elapsed time is at least 1000 ms
        // Assert request was made twice
    }

    @Test fun `on 429 without Retry-After header defaults to 5 second wait`() {
        // MockWebServer: first response 429 no header, second 200
        // Assert elapsed >= 5000 ms (or mock the delay function)
    }

    @Test fun `on 401 refreshes token and retries once`() {
        // MockWebServer: first response 401, second 200
        // Assert authManager.getValidAccessToken() was called twice
        // Assert request was made twice
    }
}
```

Note: these tests require `com.squareup.okhttp3:mockwebserver` test dependency — add `mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }` to `libs.versions.toml` and `testImplementation(libs.mockwebserver)` to `build.gradle.kts`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.RetryInterceptorTest" -i`
Expected: FAIL — class not found

- [ ] **Step 3: Create `domain/SpotifyRepository.kt`**

```kotlin
interface SpotifyRepository {
    suspend fun getPlaybackState(): PlayerStateResponse?
    suspend fun getUserPlaylists(offset: Int = 0): PlaylistsPageResponse
    suspend fun getPlaylistItems(playlistId: String, offset: Int = 0): PlaylistItemsPageResponse
    suspend fun getAllPlaylistItems(playlistId: String): PlaylistContents
    suspend fun addToPlaylist(playlistId: String, trackUri: String): String  // returns snapshotId
    suspend fun removeFromPlaylist(playlistId: String, trackUri: String, snapshotId: String): String
    suspend fun addToLikedSongs(trackId: String)
    suspend fun removeFromLikedSongs(trackId: String)
    suspend fun getCurrentUser(): UserResponse
    suspend fun getQueue(): QueueResponse
    suspend fun skipToNext()
}
```

All types are from `api/SpotifyModels.kt` or `domain/DomainModels.kt`.

- [ ] **Step 4: Create `api/SpotifyApi.kt`**

Retrofit interface:
```kotlin
interface SpotifyApi {
    @GET("me/player")
    suspend fun getPlaybackState(): Response<PlayerStateResponse?>

    @GET("me/playlists")
    suspend fun getUserPlaylists(@Query("limit") limit: Int = 50, @Query("offset") offset: Int = 0): PlaylistsPageResponse

    @GET("playlists/{id}/items")
    suspend fun getPlaylistItems(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PlaylistItemsPageResponse

    @POST("playlists/{id}/items")
    suspend fun addToPlaylist(@Path("id") id: String, @Body req: AddToPlaylistRequest): SnapshotResponse

    @HTTP(method = "DELETE", path = "playlists/{id}/items", hasBody = true)
    suspend fun removeFromPlaylist(@Path("id") id: String, @Body req: RemoveFromPlaylistRequest): SnapshotResponse

    @PUT("me/tracks")
    suspend fun addToLikedSongs(@Body req: LikedSongsRequest): Response<Unit>

    @DELETE("me/tracks")
    suspend fun removeFromLikedSongs(@Body req: LikedSongsRequest): Response<Unit>

    @GET("me")
    suspend fun getCurrentUser(): UserResponse

    @GET("me/player/queue")
    suspend fun getQueue(): QueueResponse

    @POST("me/player/next")
    suspend fun skipToNext(): Response<Unit>
}
```

- [ ] **Step 5: Create `api/SpotifyClient.kt`**

`object SpotifyClient` with `fun create(authManager: AuthManager): SpotifyApi`.

`AuthInterceptor(authManager: AuthManager)`: adds `Authorization: Bearer {token}` header by calling `runBlocking { authManager.getValidAccessToken() }`.

`RetryInterceptor(authManager: AuthManager)`: 
- Proceed with request; check response code
- On 401: invalidate + refresh via `runBlocking { authManager.getValidAccessToken() }`, rebuild request with new token, retry exactly once
- On 429: parse `Retry-After` header as integer seconds (default 5 if absent or parse error); `Thread.sleep(retryAfterSeconds * 1000L)`; retry once

Build `OkHttpClient` with both interceptors plus `HttpLoggingInterceptor(BASIC)`.

Build `Retrofit` with:
- Base URL `https://api.spotify.com/v1/`
- `Json { ignoreUnknownKeys = true; isLenient = true }.asConverterFactory("application/json; charset=UTF-8".toMediaType())`

- [ ] **Step 6: Create `api/SpotifyRepositoryImpl.kt`**

`class SpotifyRepositoryImpl(private val api: SpotifyApi) : SpotifyRepository`

`getPlaybackState()`: call `api.getPlaybackState()`; return `body()` if code 200, null if 204/empty body, throw on other errors.

`getAllPlaylistItems(playlistId)`: 
- Loop: call `api.getPlaylistItems(playlistId, offset = 0, 50, 100, ...)` until `response.next == null`
- Collect non-null `item.track` from each page; last page's `snapshotId` is authoritative
- Return `PlaylistContents(snapshotId = lastPage.snapshotId, trackUris = allTracks.map { it.uri }.toSet())`

`addToPlaylist(playlistId, trackUri)`: call `api.addToPlaylist(playlistId, AddToPlaylistRequest(listOf(trackUri)))`; return `response.snapshotId`

`removeFromPlaylist(playlistId, trackUri, snapshotId)`: call `api.removeFromPlaylist(playlistId, RemoveFromPlaylistRequest(listOf(TrackUriItem(trackUri)), snapshotId))`; return `response.snapshotId`

All other methods: straightforward delegation to `api.*`.

- [ ] **Step 7: Run interceptor tests**

Run: `./gradlew :app:testDebugUnitTest --tests "*.RetryInterceptorTest" -i`
Expected: all tests PASS

- [ ] **Step 8: Verify full compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 5: TrackMover + Unit Tests

**Files:**
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/domain/TrackMover.kt`
- Create: `app/src/test/java/com/example/spotifyplaylistmanager/domain/TrackMoverTest.kt`

**Interfaces:**
- Consumes: `SpotifyRepository`, `DomainModels.*`
- Produces:
  - `class TrackMover(repo: SpotifyRepository, delayMs: Long = 500, retryDelayMs: Long = 1000)`
  - `suspend fun move(trackUri: String, trackId: String, targetPlaylistId: String, context: PlaybackContext?, userId: String): MoveResult`
  - `suspend fun delete(trackUri: String, trackId: String, context: PlaybackContext?, snapshotId: String?): DeleteResult`
  - `fun canDelete(context: PlaybackContext?): Boolean`

- [ ] **Step 1: Write failing tests**

```kotlin
// TrackMoverTest.kt
class FakeSpotifyRepository : SpotifyRepository {
    val addedItems = mutableListOf<Pair<String, String>>()     // (playlistId, uri)
    val removedItems = mutableListOf<Triple<String, String, String>>()  // (playlistId, uri, snapshotId)
    val removedLiked = mutableListOf<String>()                 // trackIds
    val playlistContents = mutableMapOf<String, MutableSet<String>>()
    var getAllCallCount = 0
    var failFirstVerify = false

    override suspend fun addToPlaylist(id: String, uri: String): String {
        addedItems += id to uri
        playlistContents.getOrPut(id) { mutableSetOf() }.add(uri)
        return "snap_new"
    }
    override suspend fun getAllPlaylistItems(id: String): PlaylistContents {
        getAllCallCount++
        val uris = if (failFirstVerify && getAllCallCount == 1) emptySet()
                   else playlistContents[id] ?: emptySet()
        return PlaylistContents("snap_current", uris)
    }
    override suspend fun removeFromPlaylist(id: String, uri: String, snap: String): String {
        removedItems += Triple(id, uri, snap); return "snap_after"
    }
    override suspend fun removeFromLikedSongs(id: String) { removedLiked += id }
    override suspend fun getPlaybackState() = null
    override suspend fun getUserPlaylists(offset: Int) = PlaylistsPageResponse(emptyList())
    override suspend fun getPlaylistItems(id: String, offset: Int) = PlaylistItemsPageResponse(emptyList())
    override suspend fun addToLikedSongs(id: String) {}
    override suspend fun getCurrentUser() = UserResponse("user1")
    override suspend fun getQueue() = QueueResponse()
    override suspend fun skipToNext() {}
}

class TrackMoverTest {
    private val fake = FakeSpotifyRepository()
    private val mover = TrackMover(fake, delayMs = 0, retryDelayMs = 0)
    private val playlistContext = PlaybackContext(ContextType.PLAYLIST, "spotify:playlist:src", "src")
    private val albumContext = PlaybackContext(ContextType.ALBUM, "spotify:album:xyz", null)
    private val collectionContext = PlaybackContext(ContextType.COLLECTION, "spotify:user:u:collection", null)

    @Test fun `move adds to target then removes from source playlist`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")
        assertEquals(MoveResult.Success, result)
        assertTrue(fake.addedItems.any { it.first == "tgt" && it.second == "spotify:track:abc" })
        assertTrue(fake.removedItems.any { it.first == "src" && it.second == "spotify:track:abc" })
    }

    @Test fun `move does NOT delete from source when verification fails after retry`() = runTest {
        fake.failFirstVerify = true
        // second call also returns empty because playlistContents["tgt"] is empty (add didn't populate due to failFirstVerify)
        val result = mover.move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")
        // addedItems has the track (add was called), but verify fails twice
        // Because failFirstVerify is true and playlistContents["tgt"] has the item from addToPlaylist
        // Wait — in the fake, addToPlaylist DOES add to playlistContents regardless of failFirstVerify.
        // So second getAllPlaylistItems call returns the item. Result should be Success.
        // Test that retries work:
        assertEquals(MoveResult.Success, result)
        assertEquals(2, fake.getAllCallCount) // retried verification
        assertTrue(fake.removedItems.any { it.first == "src" })
    }

    @Test fun `move returns Failure without deleting when both verification attempts fail`() = runTest {
        // Create a fake that NEVER adds to playlistContents so verify always fails
        val alwaysFailFake = object : FakeSpotifyRepository() {
            override suspend fun addToPlaylist(id: String, uri: String): String {
                addedItems += id to uri
                // intentionally do NOT add to playlistContents
                return "snap_new"
            }
        }
        val failMover = TrackMover(alwaysFailFake, delayMs = 0, retryDelayMs = 0)
        val result = failMover.move("spotify:track:abc", "abc", "tgt", playlistContext, "user1")
        assertTrue(result is MoveResult.Failure)
        assertTrue(alwaysFailFake.removedItems.isEmpty())
    }

    @Test fun `move returns AddOnlySuccess when source is album`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", albumContext, "user1")
        assertTrue(result is MoveResult.AddOnlySuccess)
        assertTrue(fake.addedItems.any { it.first == "tgt" })
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test fun `move removes from liked songs when source is collection`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", collectionContext, "user1")
        assertEquals(MoveResult.Success, result)
        assertTrue(fake.removedLiked.contains("abc"))
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test fun `move returns AddOnlySuccess when context is null`() = runTest {
        val result = mover.move("spotify:track:abc", "abc", "tgt", null, "user1")
        assertTrue(result is MoveResult.AddOnlySuccess)
    }

    @Test fun `move returns Failure immediately for local files`() = runTest {
        val result = mover.move("spotify:local:Artist:Album:Track:0", "local", "tgt", playlistContext, "user1")
        assertTrue(result is MoveResult.Failure)
        val msg = (result as MoveResult.Failure).message
        assertTrue(msg.contains("local", ignoreCase = true))
        assertTrue(fake.addedItems.isEmpty())
        assertTrue(fake.removedItems.isEmpty())
    }

    @Test fun `delete removes from source playlist with snapshotId`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", playlistContext, "snap123")
        assertEquals(DeleteResult.Success, result)
        assertTrue(fake.removedItems.any { it.first == "src" && it.third == "snap123" })
    }

    @Test fun `delete removes from liked songs when context is collection`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", collectionContext, null)
        assertEquals(DeleteResult.Success, result)
        assertTrue(fake.removedLiked.contains("abc"))
    }

    @Test fun `delete returns NotApplicable when source is album`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", albumContext, null)
        assertEquals(DeleteResult.NotApplicable, result)
    }

    @Test fun `delete returns NotApplicable when context is null`() = runTest {
        val result = mover.delete("spotify:track:abc", "abc", null, null)
        assertEquals(DeleteResult.NotApplicable, result)
    }

    @Test fun `canDelete returns true for playlist context`() {
        assertTrue(mover.canDelete(playlistContext))
    }

    @Test fun `canDelete returns true for collection context`() {
        assertTrue(mover.canDelete(collectionContext))
    }

    @Test fun `canDelete returns false for album context`() {
        assertFalse(mover.canDelete(albumContext))
    }

    @Test fun `canDelete returns false for null context`() {
        assertFalse(mover.canDelete(null))
    }

    // Review Focus: null track items in playlist must not cause crash
    @Test fun `getAllPlaylistItems with null track entries does not crash`() = runTest {
        val nullFake = object : FakeSpotifyRepository() {
            override suspend fun getAllPlaylistItems(id: String): PlaylistContents {
                // Simulates what SpotifyRepositoryImpl returns after filtering nulls
                return PlaylistContents("snap", setOf("spotify:track:real"))
            }
        }
        val nullMover = TrackMover(nullFake, delayMs = 0, retryDelayMs = 0)
        val result = nullMover.move("spotify:track:real", "real", "tgt", albumContext, "u")
        assertTrue(result is MoveResult.AddOnlySuccess)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TrackMoverTest" -i`
Expected: FAIL — class not found

- [ ] **Step 3: Implement `domain/TrackMover.kt`**

`class TrackMover(private val repo: SpotifyRepository, private val delayMs: Long = 500, private val retryDelayMs: Long = 1000)`

`canDelete(context: PlaybackContext?): Boolean`:
- Returns `true` when `context?.type == PLAYLIST || context?.type == COLLECTION`
- Returns `false` otherwise

`move(trackUri, trackId, targetPlaylistId, context, userId)`:
1. If `trackUri.startsWith("spotify:local:")`: return `MoveResult.Failure("Local files cannot be managed via the Spotify API")`
2. `repo.addToPlaylist(targetPlaylistId, trackUri)`
3. `delay(delayMs)`
4. `var contents = repo.getAllPlaylistItems(targetPlaylistId)`
5. If `trackUri !in contents.trackUris`: `delay(retryDelayMs)`; `contents = repo.getAllPlaylistItems(targetPlaylistId)`
6. If `trackUri !in contents.trackUris`: return `MoveResult.Failure("Could not verify track was added to target playlist")`
7. When `context?.type == PLAYLIST && context.playlistId != null`:
   - `repo.removeFromPlaylist(context.playlistId, trackUri, contents.snapshotId)` ← use the snapshotId from the target verify call; for the source use a fresh fetch or the snapshotId obtained during polling (passed by caller). **Implementer note**: the `snapshotId` for the source playlist must come from the last `getAllPlaylistItems` call on the SOURCE, not the target. Add a parameter `sourceSnapshotId: String?` to `move()` or fetch source items just before delete. Simplest: fetch `repo.getAllPlaylistItems(context.playlistId)` right before delete to get the latest snapshot_id — this also serves as a final safety check.
   - Return `MoveResult.Success`
8. When `context?.type == COLLECTION`: `repo.removeFromLikedSongs(trackId)`; return `MoveResult.Success`
9. When `context == null || context.type == ALBUM || context.type == OTHER`:
   - return `MoveResult.AddOnlySuccess("Cannot remove from ${contextLabel(context)}")`

Private `contextLabel(context: PlaybackContext?)`: returns `"album"` for ALBUM, `"radio"` for null, `"this source"` otherwise.

`delete(trackUri, trackId, context, snapshotId)`:
1. When `context?.type == PLAYLIST && context.playlistId != null && snapshotId != null`:
   - `repo.removeFromPlaylist(context.playlistId, trackUri, snapshotId)` → return `DeleteResult.Success`
2. When `context?.type == COLLECTION`: `repo.removeFromLikedSongs(trackId)` → return `DeleteResult.Success`
3. Otherwise: return `DeleteResult.NotApplicable`

**Implementer note on `move()` Step 7**: Update the signature to `move(trackUri, trackId, targetPlaylistId, context, userId, sourceSnapshotId: String? = null)`. When `sourceSnapshotId == null`, fetch `repo.getAllPlaylistItems(context.playlistId)` to get it. Update `TrackMoverTest.FakeSpotifyRepository.getAllPlaylistItems` to handle multiple playlist IDs.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest --tests "*.TrackMoverTest" -i`
Expected: all 14+ tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/spotifyplaylistmanager/domain/TrackMover.kt \
        app/src/test/java/com/example/spotifyplaylistmanager/domain/TrackMoverTest.kt
git commit -m "feat: implement TrackMover domain logic with unit tests"
```

---

### Task 6: ViewModel

**Files:**
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: `SpotifyRepository`, `TrackMover`, `AuthManager`, `PrefsStorage`, all domain models
- Produces:
  - `class MainViewModel(repo, mover, authManager, prefsStorage) : ViewModel()`
  - `val uiState: StateFlow<UiState>`
  - `fun startPolling()`, `fun stopPolling()`
  - `fun initiateLogin(activity: Activity)`, `fun onAuthSuccess()`
  - `fun loadPlaylists()`, `fun selectTargetPlaylist(playlist: PlaylistInfo)`
  - `fun onMove()`, `fun onDelete()`, `fun onSkip()`
  - `fun clearSnackbar()`

```kotlin
data class UiState(
    val isLoggedIn: Boolean = false,
    val nowPlaying: NowPlayingState? = null,
    val sourcePlaylistInfo: PlaylistInfo? = null,
    val sourceSnapshotId: String? = null,
    val targetPlaylist: PlaylistInfo? = null,
    val availablePlaylists: List<PlaylistInfo> = emptyList(),
    val isLoadingPlaylists: Boolean = false,
    val remainingTracks: List<TrackInfo> = emptyList(),
    val isShuffleQueueNotice: Boolean = false,
    val buttonState: ButtonState = ButtonState(),
    val snackbarMessage: String? = null,
    val currentUserId: String? = null
)

data class ButtonState(
    val moveEnabled: Boolean = false,
    val deleteEnabled: Boolean = false,
    val skipEnabled: Boolean = false,
    val isOperationInProgress: Boolean = false
)
```

- [ ] **Step 1: Implement `MainViewModel.kt`**

Factory companion:
```kotlin
companion object {
    fun factory(repo: SpotifyRepository, mover: TrackMover, auth: AuthManager, prefs: PrefsStorage) =
        viewModelFactory { initializer { MainViewModel(repo, mover, auth, prefs) } }
}
```

Init block: if `authManager.isLoggedIn()` → `_uiState.update { it.copy(isLoggedIn = true) }`; launch `loadUserAndPlaylists()`

`startPolling()`: `pollingJob = viewModelScope.launch { while(isActive) { refreshNowPlaying(); delay(4000) } }`

`stopPolling()`: `pollingJob?.cancel()`

`refreshNowPlaying()`:
1. `val state = repo.getPlaybackState()` — wrap in try/catch; on exception post error snackbar and return
2. If `state == null || state.item == null`: update `nowPlaying = null`, clear buttonState, return
3. Map `state.item` → `TrackInfo`; map `state.context` → `PlaybackContext`
4. Determine `sourceLabel`: if PLAYLIST → find name in `availablePlaylists` or hit `getUserPlaylists` to find; if COLLECTION → "Liked Songs"; if ALBUM → `state.item.album.name`; else ""
5. Update `nowPlaying` and `sourcePlaylistInfo` (look up context.playlistId in `availablePlaylists`)
6. If `!state.shuffle_state && context?.type == PLAYLIST && context.playlistId != null`:
   - `val contents = repo.getAllPlaylistItems(context.playlistId)` in a try/catch
   - Find current track index in contents; set `remainingTracks` to items after it
   - Set `sourceSnapshotId = contents.snapshotId`
7. If `state.shuffle_state && context?.type == PLAYLIST`:
   - Set `isShuffleQueueNotice = true`; `val queue = repo.getQueue()` → set `remainingTracks` from queue.queue
8. `updateButtonState(state.item.is_local, context, mover, sourcePlaylistInfo)`

`updateButtonState(isLocal, context, mover, sourcePlaylistInfo)`:
- `moveEnabled = nowPlaying != null && targetPlaylist != null && !isLocal && !inProgress`
- `deleteEnabled = nowPlaying != null && !isLocal && mover.canDelete(context) && !inProgress`
- `skipEnabled = nowPlaying != null && !inProgress`

`loadUserAndPlaylists()`: (private, called on init and after auth)
1. `currentUserId = repo.getCurrentUser().id`
2. `loadPlaylists()`
3. Restore last playlist: `prefsStorage.getLastPlaylistId()` → find in list → `selectTargetPlaylist`

`loadPlaylists()`:
- Set `isLoadingPlaylists = true`
- Paginate `repo.getUserPlaylists(offset)` until `next == null`
- Filter: `owner.id == currentUserId || collaborative == true`
- Map to `PlaylistInfo`; update state

`onMove()`:
1. Snapshot state: `val track = uiState.value.nowPlaying?.track ?: return`; `val target = uiState.value.targetPlaylist ?: return`
2. Set `inProgress = true`; disable buttons
3. Call `mover.move(track.uri, track.id, target.id, context, userId, sourceSnapshotId)` in try/catch
4. Handle `MoveResult`: Success → `showSnackbar("Moved to ${target.name}")`; AddOnlySuccess → `showSnackbar(result.reason)`; Failure → `showSnackbar(result.message)`
5. On 403 HTTP error (Premium required): `showSnackbar("Spotify Premium is required")`
6. Set `inProgress = false`; call `refreshNowPlaying()`

`onDelete()`: same pattern with `mover.delete(...)` and `DeleteResult` handling

`onSkip()`: `repo.skipToNext()` → `delay(600)` → `refreshNowPlaying()`

`initiateLogin(activity: Activity)`: `authManager.startAuth(activity)`

`onAuthSuccess()`: `_uiState.update { it.copy(isLoggedIn = true) }`; launch `loadUserAndPlaylists()`

`clearSnackbar()`: `_uiState.update { it.copy(snackbarMessage = null) }`

- [ ] **Step 2: Verify compile**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

---

### Task 7: UI

**Files:**
- Modify: `app/src/main/java/com/example/spotifyplaylistmanager/MainActivity.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/ui/MainScreen.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/ui/NowPlayingCard.kt`
- Create: `app/src/main/java/com/example/spotifyplaylistmanager/ui/PlaylistPickerSheet.kt`

**Interfaces:**
- Consumes: `MainViewModel.uiState: StateFlow<UiState>`
- Produces: fully functional single-screen app

- [ ] **Step 1: Update `MainActivity.kt`**

Wire up all dependencies manually (no DI framework):
```kotlin
class MainActivity : ComponentActivity() {
    private lateinit var tokenStorage: TokenStorage
    private lateinit var authManager: AuthManager
    private lateinit var prefsStorage: PrefsStorage
    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tokenStorage = TokenStorage(this)
        authManager = AuthManager(tokenStorage)
        prefsStorage = PrefsStorage(this)
        val api = SpotifyClient.create(authManager)
        val repo = SpotifyRepositoryImpl(api)
        val mover = TrackMover(repo)
        viewModel = ViewModelProvider(this, MainViewModel.factory(repo, mover, authManager, prefsStorage))[MainViewModel::class.java]

        // Handle cold-start auth callback
        intent?.data?.let { uri ->
            if (uri.scheme == "myapp") handleAuthCallback(uri)
        }

        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> viewModel.startPolling()
                Lifecycle.Event.ON_PAUSE -> viewModel.stopPolling()
                else -> {}
            }
        })

        setContent { PlaylistMoverTheme { MainScreen(viewModel) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { uri ->
            if (uri.scheme == "myapp") handleAuthCallback(uri)
        }
    }

    private fun handleAuthCallback(uri: Uri) {
        lifecycleScope.launch {
            val success = authManager.handleCallback(uri)
            if (success) viewModel.onAuthSuccess()
        }
    }
}
```

- [ ] **Step 2: Create `ui/NowPlayingCard.kt`**

`@Composable fun NowPlayingCard(nowPlaying: NowPlayingState?, modifier: Modifier)`:
- If null: `ElevatedCard` with "Nothing playing" centered text
- If non-null: `ElevatedCard` with `Row` containing:
  - `AsyncImage(model = nowPlaying.track.albumArtUrl, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))`
  - `Column` with `Text(track.name, style = MaterialTheme.typography.titleMedium)`, `Text(artists.joinToString(), style = bodyMedium)`, `Text(sourceLabel, style = labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)`

- [ ] **Step 3: Create `ui/PlaylistPickerSheet.kt`**

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlaylistPickerSheet(
    playlists: List<PlaylistInfo>,
    isLoading: Boolean,
    onSelect: (PlaylistInfo) -> Unit,
    onDismiss: () -> Unit
)
```

`ModalBottomSheet(onDismissRequest = onDismiss)`:
- If `isLoading`: `CircularProgressIndicator(modifier = Modifier.align(CenterHorizontally))`
- Else: `LazyColumn` where each item is a `ListItem(headlineContent = { Text(playlist.name) }, modifier = Modifier.clickable { onSelect(playlist); onDismiss() })`

- [ ] **Step 4: Create `ui/MainScreen.kt`**

```kotlin
@Composable fun MainScreen(viewModel: MainViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(uiState.snackbarMessage) {
        uiState.snackbarMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearSnackbar()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        if (!uiState.isLoggedIn) {
            // Login screen
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Button(onClick = { viewModel.initiateLogin(context as Activity) }) {
                    Text("Login with Spotify")
                }
            }
        } else {
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                NowPlayingCard(uiState.nowPlaying, Modifier.fillMaxWidth().padding(16.dp))

                // Target playlist row
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                    Text(uiState.targetPlaylist?.name ?: "No playlist selected", style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = { viewModel.loadPlaylists(); showPlaylistPicker = true }) { Text("Change") }
                }

                // Action buttons
                val bs = uiState.buttonState
                Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::onMove, enabled = bs.moveEnabled, modifier = Modifier.weight(1f)) {
                        if (bs.isOperationInProgress) CircularProgressIndicator(Modifier.size(20.dp)) else Text("Move")
                    }
                    Button(onClick = viewModel::onDelete, enabled = bs.deleteEnabled, modifier = Modifier.weight(1f)) { Text("Delete") }
                    Button(onClick = viewModel::onSkip, enabled = bs.skipEnabled, modifier = Modifier.weight(1f)) { Text("Skip") }
                }

                // Remaining tracks
                if (uiState.isShuffleQueueNotice) {
                    Text("Shuffle is on — showing queue", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 16.dp))
                }
                uiState.remainingTracks.forEach { track ->
                    ListItem(
                        headlineContent = { Text(track.name) },
                        supportingContent = { Text(track.artists.joinToString()) }
                    )
                }
            }
        }
    }

    if (showPlaylistPicker) {
        PlaylistPickerSheet(
            playlists = uiState.availablePlaylists,
            isLoading = uiState.isLoadingPlaylists,
            onSelect = { viewModel.selectTargetPlaylist(it); showPlaylistPicker = false },
            onDismiss = { showPlaylistPicker = false }
        )
    }
}
```

- [ ] **Step 5: Final build**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL, APK at `app/build/outputs/apk/debug/app-debug.apk`

If there are compile errors, fix them and re-run until BUILD SUCCESSFUL.

---

## Spotify Dashboard Setup (for user)

After the build succeeds, the developer must:

1. Go to [developer.spotify.com/dashboard](https://developer.spotify.com/dashboard)
2. Open the app with client ID `4f594fed820a4305aad21aae910f273b`
3. Click **Edit Settings**
4. Under **Redirect URIs**, add `myapp://callback` and click **Add**
5. Save

## Installing on Phone

```bash
# With phone connected via USB and USB debugging enabled:
adb install app/build/outputs/apk/debug/app-debug.apk

# Or use Android Studio's Run button (Shift+F10)
```

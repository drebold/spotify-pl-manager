package com.example.spotifyplaylistmanager.api

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

interface SpotifyApi {

    @GET("me/player")
    suspend fun getPlaybackState(): Response<PlayerStateResponse?>

    @GET("me/playlists")
    suspend fun getUserPlaylists(
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PlaylistsPageResponse

    @GET("playlists/{id}/items")
    suspend fun getPlaylistItems(
        @Path("id") id: String,
        @Query("limit") limit: Int = 50,
        @Query("offset") offset: Int = 0
    ): PlaylistItemsPageResponse

    @POST("playlists/{id}/items")
    suspend fun addToPlaylist(
        @Path("id") id: String,
        @Body req: AddToPlaylistRequest
    ): SnapshotResponse

    @HTTP(method = "DELETE", path = "playlists/{id}/items", hasBody = true)
    suspend fun removeFromPlaylist(
        @Path("id") id: String,
        @Body req: RemoveFromPlaylistRequest
    ): SnapshotResponse

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

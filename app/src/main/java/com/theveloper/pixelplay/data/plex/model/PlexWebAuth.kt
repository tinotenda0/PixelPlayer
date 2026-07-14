package com.theveloper.pixelplay.data.plex.model

/** A user inside the owner's Plex Home. */
data class PlexHomeUser(
    val uuid: String,
    val title: String,
    val isProtected: Boolean,
    val isAdmin: Boolean
)

/** One reachable address of a Plex Media Server. */
data class PlexServerConnection(
    val uri: String,
    val isLocal: Boolean,
    val isRelay: Boolean
)

/** A Plex Media Server visible to an account (from plex.tv resources). */
data class PlexServerResource(
    val name: String,
    val clientIdentifier: String,
    /** Server-scoped access token for this user (falls back to the account token). */
    val accessToken: String?,
    val connections: List<PlexServerConnection>
)

/** A signed-in Plex identity bound to one server. */
data class PlexAccount(
    val id: String,
    val username: String,
    /** Account token for plex.tv APIs (home users, resources). */
    val plexTvToken: String,
    /** Token used against the media server (may be server-scoped). */
    val serverToken: String,
    val serverUrl: String,
    val serverName: String? = null
)

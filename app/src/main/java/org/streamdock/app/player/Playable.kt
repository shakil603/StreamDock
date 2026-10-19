package org.streamdock.app.player

import java.io.Serializable

/**
 * One playable entry in the player queue. Video and (when the site serves them
 * separately) audio are merged by the playback engine; `preferAudio` starts the
 * item in audio-only mode (NewPipe style listening).
 */
data class Playable(
    val title: String,
    val subtitle: String,
    val videoUrl: String,
    val audioUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val audioHeaders: Map<String, String> = emptyMap(),
    val preferAudio: Boolean = false
) : Serializable

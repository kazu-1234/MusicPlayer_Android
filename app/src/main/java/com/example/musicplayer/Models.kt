package com.example.musicplayer

import android.net.Uri

data class Song(
    val uri: Uri, 
    val displayName: String, 
    val title: String, 
    val artist: String,
    val album: String, 
    var playCount: Int = 0, 
    val trackNumber: Int, 
    val sourceFolderUri: Uri,
    val duration: Long = 0L,
    val exists: Boolean = true
)

data class Playlist(val name: String, val songs: List<Song>)

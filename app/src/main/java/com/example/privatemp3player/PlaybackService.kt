package com.privatemp3.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.app.NotificationCompat.MediaStyle
import androidx.media.session.MediaButtonReceiver

class PlaybackService : Service() {

    private val binder = LocalBinder()
    private var mediaPlayer: MediaPlayer? = null
    private lateinit var mediaSession: MediaSessionCompat
    var currentUri: Uri? = null

    companion object {
        const val CHANNEL_ID = "private_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PREV = "com.privatemp3.player.ACTION_PREV"
        const val ACTION_NEXT = "com.privatemp3.player.ACTION_NEXT"
    }

    inner class LocalBinder : Binder() { fun getService() = this@PlaybackService }

    override fun onCreate() {
        super.onCreate()
        initializeMediaSession()
        createNotificationChannel()
    }

    private fun initializeMediaSession() {
        mediaSession = MediaSessionCompat(this, "PrivateSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resumePlayback() }
                override fun onPause() { pausePlayback() }
                override fun onStop() { stopPlayback() }
                override fun onSeekTo(pos: Long) { seekTo(pos.toInt()) }
                override fun onSkipToPrevious() { sendBroadcastAction(ACTION_PREV) }
                override fun onSkipToNext() { sendBroadcastAction(ACTION_NEXT) }
            })
            isActive = true
        }
    }

    private fun sendBroadcastAction(action: String) {
        val intent = Intent(action)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    private fun updateMetadata(duration: Long) {
        val meta = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, " ")
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, " ")
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, " ")
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .build()
        mediaSession.setMetadata(meta)
    }

    fun playUri(uri: Uri) {
        currentUri = uri
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(applicationContext, uri)
            prepare()
            start()
        }
        updateMetadata(mediaPlayer?.duration?.toLong() ?: 0L)
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        showNotification(true)
    }

    fun pausePlayback() {
        mediaPlayer?.pause()
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED)
        showNotification(false)
    }

    fun resumePlayback() {
        mediaPlayer?.start()
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING)
        showNotification(true)
    }

    fun stopPlayback() {
        mediaPlayer?.stop()
        mediaPlayer?.release()
        updatePlaybackState(PlaybackStateCompat.STATE_STOPPED)
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    fun seekTo(pos: Int) {
        mediaPlayer?.seekTo(pos)
        updatePlaybackState(if (mediaPlayer?.isPlaying == true) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
    }

    fun isPlaying() = mediaPlayer?.isPlaying == true
    fun getCurrentPosition() = mediaPlayer?.currentPosition ?: 0
    fun getDuration() = mediaPlayer?.duration ?: 0

    private fun updatePlaybackState(state: Int) {
        val position = mediaPlayer?.currentPosition?.toLong() ?: 0L
        val playbackState = PlaybackStateCompat.Builder()
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            .setState(state, position, 1.0f)
            .build()
        mediaSession.setPlaybackState(playbackState)
    }

    private fun showNotification(isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // 💡 [추가] 이전/다음 버튼용 PendingIntent (MediaButtonReceiver 이용)
        val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
        val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
        val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, if(isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Playing")
            .setContentText("")
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)
            .setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Pause", playPauseIntent
            )
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Controls"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}












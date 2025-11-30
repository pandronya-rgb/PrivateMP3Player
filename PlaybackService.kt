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
    private var isStealthMode = false

    companion object {
        const val CHANNEL_ID = "private_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PREV = "com.privatemp3.player.ACTION_PREV"
        const val ACTION_NEXT = "com.privatemp3.player.ACTION_NEXT"
    }

    inner class LocalBinder : Binder() {
        fun getService() = this@PlaybackService
    }

    override fun onCreate() {
        super.onCreate()
        initializeMediaSession()
        createNotificationChannel()
    }

    fun setStealthMode(enabled: Boolean) {
        if (isStealthMode != enabled) {
            isStealthMode = enabled
            val duration = if (enabled) -1L else getDuration().toLong()
            updateMetadata(duration)
            updatePlaybackState(if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED)
            showNotification(isPlaying())
        }
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
        val metaBuilder = MediaMetadataCompat.Builder()

        if (isStealthMode) {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "알림 메시지")
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "　")
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, null)
        } else {
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, "실행 중")
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "　")
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, null)
        }

        mediaSession.setMetadata(metaBuilder.build())
    }

    fun playUri(uri: Uri) {
        currentUri = uri
        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build())
            setDataSource(applicationContext, uri)
            prepare()
            setOnCompletionListener {
                sendBroadcastAction(ACTION_NEXT)
            }
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
        val pStateBuilder = PlaybackStateCompat.Builder()

        if (isStealthMode) {
            pStateBuilder.setActions(0)
            pStateBuilder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0f)
        } else {
            pStateBuilder.setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SEEK_TO or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            pStateBuilder.setState(state, position, 1.0f)
        }

        mediaSession.setPlaybackState(pStateBuilder.build())
    }

    private fun showNotification(isPlaying: Boolean) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentIntent(pendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(isPlaying)

        if (isStealthMode) {
            builder.setSmallIcon(android.R.drawable.ic_popup_sync)
            builder.setContentTitle("알림 메시지")
            builder.setContentText("　")
            builder.setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView())
        } else {
            val prevIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
            val nextIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT)
            val playPauseIntent = MediaButtonReceiver.buildMediaButtonPendingIntent(this, if(isPlaying) PlaybackStateCompat.ACTION_PAUSE else PlaybackStateCompat.ACTION_PLAY)

            builder.setSmallIcon(android.R.drawable.ic_media_play)
            builder.setContentTitle("실행 중")
            builder.setContentText("　")

            builder.setStyle(MediaStyle()
                .setMediaSession(mediaSession.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))

            builder.addAction(android.R.drawable.ic_media_previous, "Prev", prevIntent)
            builder.addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                "Pause", playPauseIntent
            )
            builder.addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
        }

        startForeground(NOTIFICATION_ID, builder.build())
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Service Status", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows service status"
                setShowBadge(false)
                lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
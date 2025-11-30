package com.privatemp3.player

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.privatemp3.player.databinding.ActivityMainBinding
import com.privatemp3.player.databinding.ItemMp3Binding
import java.util.Collections
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var playbackService: PlaybackService? = null
    private var isBound = false
    private var fileList = mutableListOf<DocumentFile>()
    private var playlist = mutableListOf<DocumentFile>()
    private var queueList = mutableListOf<DocumentFile>()
    private var currentQueueIndex = -1
    private var currentPlayingUri: Uri? = null
    private val prefs by lazy { getSharedPreferences("private_player_prefs", Context.MODE_PRIVATE) }
    private var isPrivacyMode: Boolean
        get() = prefs.getBoolean("privacy_mode", true)
        set(v) = prefs.edit().putBoolean("privacy_mode", v).apply()
    private var isDeleteEnabled: Boolean
        get() = prefs.getBoolean("delete_enabled", false)
        set(v) = prefs.edit().putBoolean("delete_enabled", v).apply()
    private var isDarkMode: Boolean
        get() = prefs.getBoolean("dark_mode", false)
        set(v) {
            prefs.edit().putBoolean("dark_mode", v).apply()
            applyTheme()
        }
    private var playMode: Int
        get() = prefs.getInt("play_mode", 0)
        set(v) = prefs.edit().putInt("play_mode", v).apply()
    private var isStealthMode: Boolean
        get() = prefs.getBoolean("stealth_mode", false)
        set(v) {
            prefs.edit().putBoolean("stealth_mode", v).apply()
            playbackService?.setStealthMode(v)
        }
    private var isHeadsetOnly: Boolean
        get() = prefs.getBoolean("headset_only", false)
        set(v) {
            prefs.edit().putBoolean("headset_only", v).apply()
            playbackService?.setHeadsetOnlyMode(v)
        }
    private lateinit var browserAdapter: Mp3Adapter
    private lateinit var playlistAdapter: Mp3Adapter
    private lateinit var queueAdapter: Mp3Adapter
    private val playlistTouchHelper by lazy { createDragHelper(playlist, playlistAdapter) }
    private val queueTouchHelper by lazy { createDragHelper(queueList, queueAdapter) }
    private val handler = Handler(Looper.getMainLooper())
    private val updateSeekBarRunnable = object : Runnable {
        override fun run() {
            playbackService?.let {
                if (currentPlayingUri != it.currentUri) {
                    currentPlayingUri = it.currentUri
                    notifyAllAdapters()
                }

                if (it.isPlaying()) {
                    binding.seekBar.max = it.getDuration()
                    binding.seekBar.progress = it.getCurrentPosition()
                    binding.tvCurrentTime.text = formatTime(it.getCurrentPosition())
                    binding.tvTotalTime.text = formatTime(it.getDuration())
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                } else {
                    binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_play)
                }
            }
            handler.postDelayed(this, 1000)
        }
    }
    private val mediaReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                PlaybackService.ACTION_PREV -> playPreviousTrack()
                PlaybackService.ACTION_NEXT -> playNextTrack()
            }
        }
    }
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            playbackService = (service as PlaybackService.LocalBinder).getService()
            isBound = true
            currentPlayingUri = playbackService?.currentUri
            playbackService?.setStealthMode(isStealthMode)
            playbackService?.setHeadsetOnlyMode(isHeadsetOnly)

            playbackService?.completionCallback = {
                playNextTrack()
            }
            handler.post(updateSeekBarRunnable)
        }
        override fun onServiceDisconnected(name: ComponentName?) { isBound = false }
    }
    private val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            prefs.edit().putString("root_uri", it.toString()).apply()
            loadFiles(it)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyTheme()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filter = IntentFilter().apply {
            addAction(PlaybackService.ACTION_PREV)
            addAction(PlaybackService.ACTION_NEXT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mediaReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(mediaReceiver, filter)
        }
        Intent(this, PlaybackService::class.java).also {
            startService(it)
            bindService(it, connection, Context.BIND_AUTO_CREATE)
        }
        setupUI()
        loadSavedRoot()
    }

    private fun applyTheme() {
        AppCompatDelegate.setDefaultNightMode(if (isDarkMode) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
    }

    private fun setupUI() {
        browserAdapter = Mp3Adapter(fileList, type = 0)
        playlistAdapter = Mp3Adapter(playlist, type = 1)
        queueAdapter = Mp3Adapter(queueList, type = 2)

        setupRecyclerView(binding.fragmentContainer, browserAdapter, null)
        setupRecyclerView(binding.rvPlaylist, playlistAdapter, playlistTouchHelper)
        setupRecyclerView(binding.rvQueue, queueAdapter, queueTouchHelper)

        val rvBrowser = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = browserAdapter
            addItemDecoration(DividerItemDecoration(this@MainActivity, LinearLayoutManager.VERTICAL))
        }

        binding.fragmentContainer.addView(rvBrowser)
        binding.btnNavFiles?.setOnClickListener { showView(0) }
        binding.btnNavQueue.setOnClickListener { showView(2) }
        binding.btnNavPlaylist.setOnClickListener { showView(1) }
        binding.btnNavOption.setOnClickListener { showOptionsMenu(it) }
        binding.btnPlayPause.setOnClickListener { playbackService?.let { if (it.isPlaying()) it.pausePlayback() else it.resumePlayback() } }
        binding.btnPrev.setOnClickListener { playPreviousTrack() }
        binding.btnNext.setOnClickListener { playNextTrack() }
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) { if (u) binding.tvCurrentTime.text = formatTime(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { playbackService?.seekTo(s?.progress ?: 0) }
        })
    }

    private fun setupRecyclerView(view: View, adapter: Mp3Adapter, helper: ItemTouchHelper?) {
        if (view is RecyclerView) {
            view.layoutManager = LinearLayoutManager(this)
            view.adapter = adapter
            view.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
            helper?.attachToRecyclerView(view)
        }
    }

    private fun createDragHelper(list: MutableList<DocumentFile>, adapter: Mp3Adapter): ItemTouchHelper {
        return ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder): Boolean {
                Collections.swap(list, vh.adapterPosition, t.adapterPosition)
                adapter.notifyItemMoved(vh.adapterPosition, t.adapterPosition)
                return true
            }
            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {
                val pos = vh.adapterPosition
                list.removeAt(pos)
                adapter.notifyItemRemoved(pos)
                if (list === queueList) {
                    if (pos < currentQueueIndex) currentQueueIndex--
                    else if (pos == currentQueueIndex) playbackService?.stopPlayback()
                }
            }
            override fun isLongPressDragEnabled() = false
        })
    }

    private fun showView(type: Int) {
        binding.fragmentContainer.visibility = if (type == 0) View.VISIBLE else View.GONE
        binding.playlistContainer.visibility = if (type == 1) View.VISIBLE else View.GONE
        binding.queueContainer.visibility = if (type == 2) View.VISIBLE else View.GONE
    }

    private fun showOptionsMenu(view: View) {
        val popup = PopupMenu(this, view)

        popup.menu.add(0, 7, 0, "새로 고침")
        popup.menu.add(0, 0, 1, "폴더 선택")

        val privacyTitle = if (isPrivacyMode) "파일명 감추기: 끄기" else "파일명 감추기: 켜기"
        popup.menu.add(0, 1, 2, privacyTitle)

        val modeTitle = when(playMode) {
            1 -> "반복: 한곡"
            2 -> "반복: 전체"
            else -> "반복: 없음"
        }
        popup.menu.add(0, 2, 3, modeTitle)

        val deleteTitle = if (isDeleteEnabled) "파일 삭제 허용: 끄기" else "파일 삭제 허용: 켜기"
        popup.menu.add(0, 4, 4, deleteTitle)

        val darkTitle = if (isDarkMode) "다크 모드: 끄기" else "다크 모드: 켜기"
        popup.menu.add(0, 5, 5, darkTitle)

        val stealthTitle = if (isStealthMode) "위장 모드: 끄기" else "위장 모드: 켜기"
        popup.menu.add(0, 6, 6, stealthTitle)

        val headsetTitle = if (isHeadsetOnly) "스피커 재생 금지: 켜짐" else "스피커 재생 금지: 꺼짐"
        popup.menu.add(0, 8, 7, headsetTitle)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                7 -> { loadSavedRoot(); Toast.makeText(this, "목록 갱신됨", Toast.LENGTH_SHORT).show(); true }
                0 -> { folderPicker.launch(null); true }
                1 -> { isPrivacyMode = !isPrivacyMode; notifyAllAdapters(); true }
                2 -> { playMode = (playMode + 1) % 3; true }
                4 -> { isDeleteEnabled = !isDeleteEnabled; true }
                5 -> { isDarkMode = !isDarkMode; true }
                6 -> { isStealthMode = !isStealthMode; true }
                8 -> { isHeadsetOnly = !isHeadsetOnly; true }
                else -> false
            }
        }
        popup.show()
    }

    private fun notifyAllAdapters() {
        browserAdapter.notifyDataSetChanged()
        playlistAdapter.notifyDataSetChanged()
        queueAdapter.notifyDataSetChanged()
    }

    private fun playFromSource(file: DocumentFile, sourceList: MutableList<DocumentFile>) {
        queueList.clear()
        queueList.addAll(sourceList)
        queueAdapter.notifyDataSetChanged()
        val index = queueList.indexOf(file)
        if (index != -1) playQueueIndex(index)
    }

    private fun playQueueIndex(index: Int) {
        if (index in queueList.indices) {
            currentQueueIndex = index
            val file = queueList[index]
            playbackService?.playUri(file.uri)
            currentPlayingUri = file.uri
            notifyAllAdapters()
            binding.btnPlayPause.setImageResource(android.R.drawable.ic_media_pause)
        }
    }

    private fun playNextTrack() {
        if (queueList.isEmpty()) return

        if (currentPlayingUri != null) {
            val foundIndex = queueList.indexOfFirst { it.uri == currentPlayingUri }
            if (foundIndex != -1) {
                currentQueueIndex = foundIndex
            }
        }

        var nextIndex = currentQueueIndex
        when (playMode) {
            1 -> { }
            2 -> { nextIndex = (currentQueueIndex + 1) % queueList.size }
            else -> {
                if (currentQueueIndex + 1 < queueList.size) nextIndex++
                else return
            }
        }
        playQueueIndex(nextIndex)
    }

    private fun playPreviousTrack() {
        if (queueList.isEmpty()) return

        if (currentPlayingUri != null) {
            val foundIndex = queueList.indexOfFirst { it.uri == currentPlayingUri }
            if (foundIndex != -1) {
                currentQueueIndex = foundIndex
            }
        }

        val prevIndex = if (currentQueueIndex - 1 < 0) queueList.size - 1 else currentQueueIndex - 1
        playQueueIndex(prevIndex)
    }

    private fun loadSavedRoot() {
        prefs.getString("root_uri", null)?.let { try { loadFiles(Uri.parse(it)) } catch (e: Exception) {} }
    }

    private fun loadFiles(uri: Uri) {
        val root = DocumentFile.fromTreeUri(this, uri)
        fileList.clear()
        if (root != null && root.canRead()) {
            root.listFiles().forEach { if (it.isFile && it.name?.endsWith(".mp3", true) == true) fileList.add(it) }
        }
        fileList.sortBy { it.name?.lowercase(Locale.getDefault()) }
        browserAdapter.notifyDataSetChanged()
    }

    private fun formatTime(millis: Int): String {
        val min = (millis / 1000) / 60
        val sec = (millis / 1000) % 60
        return String.format(Locale.US, "%d:%02d", min, sec)
    }

    private fun getThemeColor(resId: Int): Int {
        val typedValue = TypedValue()
        theme.resolveAttribute(resId, typedValue, true)
        return typedValue.data
    }

    inner class Mp3Adapter(private val list: MutableList<DocumentFile>, private val type: Int) : RecyclerView.Adapter<Mp3Adapter.ViewHolder>() {
        inner class ViewHolder(val b: ItemMp3Binding) : RecyclerView.ViewHolder(b.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemMp3Binding.inflate(LayoutInflater.from(parent.context), parent, false))

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val file = list[position]
            holder.b.tvTitle.text = if (isPrivacyMode) String.format("%04d", position + 1) else file.name

            if (file.uri == currentPlayingUri) {
                holder.b.tvTitle.setTextColor(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
                holder.b.tvTitle.setTypeface(null, Typeface.BOLD)
                holder.b.ivIcon.setColorFilter(getThemeColor(androidx.appcompat.R.attr.colorPrimary))
            } else {
                val typedValue = TypedValue()
                theme.resolveAttribute(androidx.appcompat.R.attr.editTextColor, typedValue, true)
                val defaultColor = if (typedValue.resourceId != 0) getColor(typedValue.resourceId) else typedValue.data

                holder.b.tvTitle.setTextColor(defaultColor)
                holder.b.tvTitle.setTypeface(null, Typeface.NORMAL)
                holder.b.ivIcon.setColorFilter(Color.parseColor("#666666"))
            }

            if (type == 1 || type == 2) {
                holder.b.ivDragHandle.visibility = View.VISIBLE
                holder.b.ivDragHandle.setOnTouchListener { _, e ->
                    if (e.action == MotionEvent.ACTION_DOWN) {
                        if (type == 1) playlistTouchHelper.startDrag(holder)
                        if (type == 2) queueTouchHelper.startDrag(holder)
                    }
                    false
                }
            } else {
                holder.b.ivDragHandle.visibility = View.GONE
            }

            holder.itemView.setOnClickListener {
                val currentPos = holder.adapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener

                when(type) {
                    0 -> playFromSource(list[currentPos], fileList)
                    1 -> playFromSource(list[currentPos], playlist)
                    2 -> playQueueIndex(currentPos)
                }
            }

            holder.b.btnMenu.setOnClickListener { v ->
                val currentPos = holder.adapterPosition
                if (currentPos == RecyclerView.NO_POSITION) return@setOnClickListener
                val currentFile = list[currentPos]

                val popup = PopupMenu(this@MainActivity, v)
                if (type == 0 || type == 2) popup.menu.add(0, 1, 0, "재생목록 추가")
                if (type == 1) popup.menu.add(0, 2, 0, "재생목록 제거")
                if (type == 2) popup.menu.add(0, 3, 0, "대기열 제거")
                if (type == 0) popup.menu.add(0, 4, 1, "파일 삭제")

                popup.setOnMenuItemClickListener { item ->
                    when (item.itemId) {
                        1 -> { if (!playlist.contains(currentFile)) { playlist.add(currentFile); playlistAdapter.notifyDataSetChanged(); Toast.makeText(this@MainActivity, "추가됨", Toast.LENGTH_SHORT).show() }; true }
                        2 -> { playlist.removeAt(currentPos); playlistAdapter.notifyDataSetChanged(); true }
                        3 -> { queueList.removeAt(currentPos); queueAdapter.notifyDataSetChanged(); true }
                        4 -> {
                            if (!isDeleteEnabled) Toast.makeText(this@MainActivity, "설정에서 파일 삭제 허용 필요", Toast.LENGTH_SHORT).show()
                            else {
                                AlertDialog.Builder(this@MainActivity).setTitle("삭제").setMessage("파일을 삭제하시겠습니까?")
                                    .setPositiveButton("예") { _, _ ->
                                        if (currentFile.delete()) {
                                            fileList.remove(currentFile); playlist.remove(currentFile); queueList.remove(currentFile)
                                            notifyAllAdapters()
                                        }
                                    }.setNegativeButton("아니오", null).show()
                            }
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
        override fun getItemCount() = list.size
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(mediaReceiver)
        if (isBound) unbindService(connection)
        handler.removeCallbacks(updateSeekBarRunnable)
    }
}




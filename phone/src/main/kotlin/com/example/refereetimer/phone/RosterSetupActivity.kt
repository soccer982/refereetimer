package com.example.refereetimer.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.refereetimer.phone.databinding.ActivityRosterSetupBinding
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.models.LeagueType
import com.example.refereetimer.shared.models.Player
import com.example.refereetimer.shared.models.Team
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class RosterSetupActivity : AppCompatActivity() {
    private lateinit var binding: ActivityRosterSetupBinding

    // FIX: shared ViewModel, so the dashboard and PDF report actually see the rosters
    private val viewModel by lazy { (application as PhoneApp).matchViewModel }

    private lateinit var ocrProcessor: OcrProcessor
    private lateinit var rosterAdapter: RosterReviewAdapter
    private var currentSide    = Side.HOME
    private var selectedLeague = LeagueType.SJSL
    private var homeRoster     = mutableListOf<Player>(); private var awayRoster  = mutableListOf<Player>()
    private var homeCoaches    = mutableListOf<String>(); private var awayCoaches = mutableListOf<String>()
    private var pendingPhotoUri: Uri? = null
    private enum class Side { HOME, AWAY }

    // FIX: TakePicture saves a FULL-resolution photo. The old ACTION_IMAGE_CAPTURE
    // approach only returned a tiny thumbnail, far too small for text recognition.
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhotoUri
        if (saved && uri != null) processCardImage(uri)
        else binding.txtOcrStatus.text = "No photo taken"
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { processCardImage(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRosterSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingPhotoUri = savedInstanceState?.getString(KEY_PHOTO_URI)?.let(Uri::parse)
        ocrProcessor = OcrProcessor(this)

        loadExistingTeams()
        setupLeagueSelector()
        setupSideToggle()
        setupCameraButtons()
        setupRosterReview()
        setupSendButton()
        updateSideIndicator()
        showRosterForCurrentSide()

        binding.btnSendToWatch.isEnabled = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoUri?.let { outState.putString(KEY_PHOTO_URI, it.toString()) }
    }

    /** Re-open Setup without losing rosters you already scanned. */
    private fun loadExistingTeams() {
        viewModel.homeTeam.value?.let {
            homeRoster = it.players.toMutableList(); homeCoaches = it.coaches.toMutableList()
            if (it.name != "Home") binding.editHomeName.setText(it.name)
        }
        viewModel.awayTeam.value?.let {
            awayRoster = it.players.toMutableList(); awayCoaches = it.coaches.toMutableList()
            if (it.name != "Away") binding.editAwayName.setText(it.name)
        }
    }

    private fun setupLeagueSelector() {
        binding.spinnerLeague.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            LeagueType.values().map { it.name })
        binding.spinnerLeague.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                selectedLeague = LeagueType.values()[pos]; ocrProcessor.setLeague(selectedLeague)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun setupSideToggle() {
        binding.toggleGroupSide.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentSide = if (checkedId == binding.btnSetHomeTeam.id) Side.HOME else Side.AWAY
            updateSideIndicator()
            showRosterForCurrentSide()
        }
    }

    private fun updateSideIndicator() {
        binding.txtCurrentSide.text = "Scanning: ${if (currentSide == Side.HOME) "HOME" else "AWAY"} team"
    }

    private fun currentRoster(): MutableList<Player> = if (currentSide == Side.HOME) homeRoster else awayRoster

    private fun showRosterForCurrentSide() {
        val list = currentRoster()
        if (list.isEmpty()) {
            binding.rosterReviewGroup.visibility = View.GONE
            rosterAdapter.submitList(emptyList())
        } else {
            showRosterReview(list, list.count { it.flagged })
        }
    }

    private fun setupCameraButtons() {
        binding.btnTakePhoto.setOnClickListener {
            val dir = File(cacheDir, "images").apply { mkdirs() }
            val file = File(dir, "roster_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            pendingPhotoUri = uri
            cameraLauncher.launch(uri)
        }
        binding.btnChooseGallery.setOnClickListener { galleryLauncher.launch("image/*") }
    }

    private fun processCardImage(uri: Uri) {
        binding.progressOcr.visibility = View.VISIBLE
        binding.txtOcrStatus.text = "Reading card…"
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { decodeBitmap(uri) }
                val result = ocrProcessor.processGameCard(bitmap, selectedLeague)
                binding.progressOcr.visibility = View.GONE

                if (result.players.isEmpty()) {
                    binding.txtOcrStatus.text = "No players found. Check the League setting matches this card, " +
                        "then try a straight-on, well-lit photo."
                    return@launch
                }

                binding.txtOcrStatus.text = "Found ${result.players.size} players" +
                    if (result.flaggedCount > 0) " (${result.flaggedCount} need review)" else " ✓"

                if (currentSide == Side.HOME) {
                    homeRoster = result.players.toMutableList(); homeCoaches = result.coaches.toMutableList()
                    if (result.teamName.isNotBlank()) binding.editHomeName.setText(result.teamName)
                } else {
                    awayRoster = result.players.toMutableList(); awayCoaches = result.coaches.toMutableList()
                    if (result.teamName.isNotBlank()) binding.editAwayName.setText(result.teamName)
                }
                showRosterReview(result.players, result.flaggedCount)
            } catch (e: Exception) {
                binding.progressOcr.visibility = View.GONE
                binding.txtOcrStatus.text = "Couldn't read that image (${e.message})"
                android.util.Log.e("ROSTER", "OCR failed", e)
            }
        }
    }

    /** Loads a photo safely: scaled down (no out-of-memory crash) and in a format ML Kit can read. */
    private fun decodeBitmap(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width; val h = info.size.height
            val longest = maxOf(w, h)
            if (longest > MAX_IMAGE_SIDE) {
                val scale = MAX_IMAGE_SIDE.toFloat() / longest
                decoder.setTargetSize((w * scale).toInt(), (h * scale).toInt())
            }
        }
    }

    private fun setupRosterReview() {
        rosterAdapter = RosterReviewAdapter { index, player ->
            val roster = currentRoster()
            if (index < roster.size) roster[index] = player
            // FIX: refresh the list so your edit actually shows up
            rosterAdapter.submitList(roster.toList())
            val stillFlagged = roster.count { it.flagged }
            binding.txtReviewHint.visibility = if (stillFlagged > 0) View.VISIBLE else View.GONE
            binding.txtReviewHint.text = "🟡 $stillFlagged entries need review. Tap to edit."
        }
        binding.recyclerRosterReview.apply {
            layoutManager = LinearLayoutManager(this@RosterSetupActivity); adapter = rosterAdapter
        }
    }

    private fun showRosterReview(players: List<Player>, flaggedCount: Int) {
        binding.rosterReviewGroup.visibility = View.VISIBLE
        rosterAdapter.submitList(players.toList())
        if (flaggedCount > 0) {
            binding.txtReviewHint.text = "🟡 $flaggedCount entries need review. Tap to edit."
            binding.txtReviewHint.visibility = View.VISIBLE
        } else {
            binding.txtReviewHint.visibility = View.GONE
        }
    }

    private fun setupSendButton() { binding.btnSendToWatch.setOnClickListener { sendRostersToWatch() } }

    private fun sendRostersToWatch() {
        val homeName = binding.editHomeName.text.toString().ifBlank { "Home" }
        val awayName = binding.editAwayName.text.toString().ifBlank { "Away" }
        val homeTeam = Team(homeName, homeRoster.toList(), homeCoaches.toList(), true)
        val awayTeam = Team(awayName, awayRoster.toList(), awayCoaches.toList(), false)

        // Saved on the phone immediately, whether or not the watch is reachable
        viewModel.setTeams(homeTeam, awayTeam)

        binding.txtOcrStatus.text = "Sending to watch…"; binding.btnSendToWatch.isEnabled = false
        lifecycleScope.launch {
            val watchConnected = try {
                withContext(Dispatchers.IO) {
                    sendRosterDataItem(homeTeam, DataLayerPaths.DATA_ROSTER_HOME)
                    sendRosterDataItem(awayTeam, DataLayerPaths.DATA_ROSTER_AWAY)
                    sendMatchConfig(homeName, awayName)
                }
            } catch (e: Exception) {
                android.util.Log.e("ROSTER", "Send to watch failed", e)
                false
            }
            binding.txtOcrStatus.text = if (watchConnected) "✓ Rosters sent to watch"
                else "Saved on phone. Watch not connected; rosters will sync when it reconnects."
            delay(1500)
            finish()
        }
    }

    private suspend fun sendRosterDataItem(team: Team, path: String) {
        val json = JSONObject().apply {
            put("name", team.name); put("isHome", team.isHome); put("coaches", JSONArray(team.coaches))
            put("players", JSONArray(team.players.map { p -> JSONObject().apply {
                put("number", p.jerseyNumber); put("name", p.name); put("flagged", p.flagged) } }))
        }.toString()
        val request = PutDataMapRequest.create(path).apply {
            dataMap.putString(DataLayerPaths.KEY_ROSTER_JSON, json)
            dataMap.putLong(DataLayerPaths.KEY_TIMESTAMP, System.currentTimeMillis())
        }
        Wearable.getDataClient(this@RosterSetupActivity).putDataItem(request.asPutDataRequest().setUrgent()).await()
    }

    /** NEW: sends team names + period/break lengths from Match Setup to the watch. Returns true if a watch got it. */
    private suspend fun sendMatchConfig(homeName: String, awayName: String): Boolean {
        val prefs = getSharedPreferences("MatchConfigPrefs", Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("homeName", homeName); put("awayName", awayName)
            put("periodMins", prefs.getInt("duration", 45))
            put("breakMins", prefs.getInt("breakDuration", 10))
            put("numPeriods", prefs.getInt("periods", 2))
        }.toString()
        val nodes = Wearable.getNodeClient(this).connectedNodes.await()
        val messages = Wearable.getMessageClient(this)
        nodes.forEach { messages.sendMessage(it.id, DataLayerPaths.DATA_MATCH_CONFIG, json.toByteArray()).await() }
        return nodes.isNotEmpty()
    }

    companion object {
        private const val KEY_PHOTO_URI = "pending_photo_uri"
        private const val MAX_IMAGE_SIDE = 2048
    }
}

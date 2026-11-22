package com.example.refereetimer.phone

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.refereetimer.phone.databinding.ActivityRosterSetupBinding
import com.example.refereetimer.shared.CardInfo
import com.example.refereetimer.shared.DataLayerPaths
import com.example.refereetimer.shared.MatchJson
import com.example.refereetimer.shared.RosterCard
import com.example.refereetimer.shared.RosterCardParser
import com.example.refereetimer.shared.RosterCheck
import com.example.refereetimer.shared.RosterSection
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
import org.json.JSONObject
import java.io.File

/**
 * Roster setup: scan the game card (then crop it into HOME and AWAY), or paste roster text.
 * Both teams are filled from one card when possible; the HOME / AWAY toggle picks which list
 * you're looking at (and where a single-team scan or paste goes).
 */
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
    private var cropSourceUri: Uri? = null
    private var cardInfo: CardInfo? = null
    private enum class Side { HOME, AWAY }

    // FIX: TakePicture saves a FULL-resolution photo. The old ACTION_IMAGE_CAPTURE
    // approach only returned a tiny thumbnail, far too small for text recognition.
    private val cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        val uri = pendingPhotoUri
        if (saved && uri != null) startCrop(uri)
        else binding.txtOcrStatus.text = "No photo taken"
    }

    private val galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { startCrop(it) }
    }

    /** NEW: after the photo, crop it into HOME and AWAY (or let the app find them). */
    private val cropLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = cropSourceUri ?: return@registerForActivityResult
        val data = result.data
        if (result.resultCode != Activity.RESULT_OK || data == null) {
            binding.txtOcrStatus.text = "Scan cancelled"
            return@registerForActivityResult
        }
        processCardImage(
            uri,
            rotation = data.getIntExtra(RosterCropActivity.EXTRA_ROTATION, 0),
            homeBox = data.getFloatArrayExtra(RosterCropActivity.EXTRA_HOME_BOX),
            awayBox = data.getFloatArrayExtra(RosterCropActivity.EXTRA_AWAY_BOX),
            auto = data.getBooleanExtra(RosterCropActivity.EXTRA_AUTO, true)
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRosterSetupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        pendingPhotoUri = savedInstanceState?.getString(KEY_PHOTO_URI)?.let(Uri::parse)
        cropSourceUri = savedInstanceState?.getString(KEY_CROP_URI)?.let(Uri::parse)
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

    override fun onDestroy() {
        super.onDestroy()
        if (::ocrProcessor.isInitialized) ocrProcessor.close()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingPhotoUri?.let { outState.putString(KEY_PHOTO_URI, it.toString()) }
        cropSourceUri?.let { outState.putString(KEY_CROP_URI, it.toString()) }
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

    private fun selectSide(side: Side) {
        binding.toggleGroupSide.check(if (side == Side.HOME) binding.btnSetHomeTeam.id else binding.btnSetAwayTeam.id)
    }

    private fun updateSideIndicator() {
        val list = currentRoster()
        val side = if (currentSide == Side.HOME) "HOME" else "AWAY"
        binding.txtCurrentSide.text = if (list.isEmpty()) "Showing: $side team (no players yet)"
            else "Showing: $side team · ${list.size} players"
    }

    private fun currentRoster(): MutableList<Player> = if (currentSide == Side.HOME) homeRoster else awayRoster

    private fun showRosterForCurrentSide() {
        val list = currentRoster()
        binding.rosterReviewGroup.visibility = if (list.isEmpty()) View.GONE else View.VISIBLE
        val issues = RosterCheck.issues(list)
        rosterAdapter.show(list, issues)
        val count = issues.size
        binding.txtReviewHint.visibility = if (count > 0) View.VISIBLE else View.GONE
        binding.txtReviewHint.text = "🟡 $count ${if (count == 1) "entry needs" else "entries need"} a look. Tap a player to fix."
        updateSideIndicator()
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
        binding.btnPasteRoster.setOnClickListener { showPasteDialog() }
        binding.btnApplyCardInfo.setOnClickListener { cardInfo?.let { applyCardInfo(it) } }
        binding.btnAddPlayer.setOnClickListener {
            RosterReviewAdapter.editPlayerDialog(this, "Add player", null, allowDelete = false) { p ->
                if (p != null) { currentRoster().add(p); showRosterForCurrentSide() }
            }
        }
    }

    private fun startCrop(uri: Uri) {
        cropSourceUri = uri
        cropLauncher.launch(RosterCropActivity.intent(this, uri))
    }

    // ── Scanning ──

    private fun processCardImage(uri: Uri, rotation: Int, homeBox: FloatArray?, awayBox: FloatArray?, auto: Boolean) {
        binding.progressOcr.visibility = View.VISIBLE
        binding.txtOcrStatus.text = "Reading card…"
        lifecycleScope.launch {
            try {
                val full = withContext(Dispatchers.IO) {
                    RosterCropActivity.rotate(RosterCropActivity.decode(this@RosterSetupActivity, uri, OCR_MAX_SIDE), rotation)
                }
                if (auto) {
                    applyCard(ocrProcessor.readCard(full, selectedLeague), "card")
                } else {
                    // Each box is read on its own and goes to its own team, whatever headings it shows
                    var info: CardInfo? = null
                    val home = homeBox?.let { box ->
                        binding.txtOcrStatus.text = "Reading HOME section…"
                        ocrProcessor.readCard(cropFor(full, box), selectedLeague).also { info = it.info }.merged()
                    }
                    val away = awayBox?.let { box ->
                        binding.txtOcrStatus.text = "Reading AWAY section…"
                        ocrProcessor.readCard(cropFor(full, box), selectedLeague).also { if (info?.isEmpty != false) info = it.info }.merged()
                    }
                    // Match info is usually above both sections, so also read the whole card for it
                    if (info?.isEmpty != false) info = ocrProcessor.readCard(full, selectedLeague).info
                    applySections(home, away, info, "scan")
                }
            } catch (e: Exception) {
                binding.txtOcrStatus.text = "Couldn't read that image (${e.message})"
                android.util.Log.e("ROSTER", "OCR failed", e)
            } finally {
                binding.progressOcr.visibility = View.GONE
            }
        }
    }

    private suspend fun cropFor(full: Bitmap, box: FloatArray): Bitmap =
        withContext(Dispatchers.Default) { RosterCropActivity.crop(full, box) }

    // ── Pasting ──

    private fun showPasteDialog() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val clipText = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics).toInt()
        val edit = EditText(this).apply {
            hint = "Paste the roster here: the game card text, or lines like\n10 Carter Cahall\n21 Joseph Ciminicione"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            gravity = Gravity.TOP; minLines = 8; maxLines = 14
            setText(clipText)
        }
        val frame = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(edit) }
        AlertDialog.Builder(this)
            .setTitle("Paste roster")
            .setMessage("If the text has Home and Away sections, both teams are filled. Otherwise it goes to the " +
                (if (currentSide == Side.HOME) "HOME" else "AWAY") + " team.")
            .setView(frame)
            .setPositiveButton("Read roster") { _, _ ->
                val text = edit.text.toString()
                if (text.isBlank()) return@setPositiveButton
                val card = RosterCardParser.parseText(text)
                applyCard(card, "pasted text")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Applying results ──

    /** A whole card (or pasted text): fills both teams if it has Home/Away sections, else the current side. */
    private fun applyCard(card: RosterCard, source: String) {
        if (card.hasSides) {
            val home = if (card.home.players.isNotEmpty()) card.home else null
            val away = if (card.away.players.isNotEmpty()) card.away else null
            applySections(home, away, card.info, source)
        } else {
            val one = card.merged()
            if (currentSide == Side.HOME) applySections(one, null, card.info, source)
            else applySections(null, one, card.info, source)
        }
    }

    private fun applySections(home: RosterSection?, away: RosterSection?, info: CardInfo?, source: String) {
        val found = listOfNotNull(home, away).sumOf { it.players.size }
        if (found == 0) {
            binding.txtOcrStatus.text = "No players found in the $source. Try a straight-on, well-lit photo, " +
                "draw the box around just one team, or paste the roster text."
            info?.let { showCardInfo(it) }
            return
        }
        val parts = mutableListOf<String>()
        home?.takeIf { it.players.isNotEmpty() }?.let {
            homeRoster = it.toPlayers().toMutableList()
            if (it.coaches.isNotEmpty()) homeCoaches = it.coachLabels().toMutableList()
            if (it.teamName.isNotBlank()) binding.editHomeName.setText(it.teamName)
            parts += "HOME ${it.players.size}"
        }
        away?.takeIf { it.players.isNotEmpty() }?.let {
            awayRoster = it.toPlayers().toMutableList()
            if (it.coaches.isNotEmpty()) awayCoaches = it.coachLabels().toMutableList()
            if (it.teamName.isNotBlank()) binding.editAwayName.setText(it.teamName)
            parts += "AWAY ${it.players.size}"
        }
        val needLook = RosterCheck.issues(homeRoster).size + RosterCheck.issues(awayRoster).size
        binding.txtOcrStatus.text = "Found ${parts.joinToString(" · ")} players" +
            if (needLook > 0) " ($needLook need a look)" else " ✓"
        // Show the team that was just filled (home first)
        when {
            home != null && home.players.isNotEmpty() && currentSide != Side.HOME && (away == null || away.players.isEmpty()) -> selectSide(Side.HOME)
            away != null && away.players.isNotEmpty() && (home == null || home.players.isEmpty()) && currentSide != Side.AWAY -> selectSide(Side.AWAY)
        }
        showRosterForCurrentSide()
        info?.let { showCardInfo(it) }
    }

    private fun showCardInfo(info: CardInfo) {
        if (info.isEmpty) { binding.cardInfoGroup.visibility = View.GONE; return }
        cardInfo = info
        binding.cardInfoGroup.visibility = View.VISIBLE
        binding.txtCardInfo.text = info.summary().joinToString("\n") { (k, v) -> "$k: $v" }
    }

    /** Copies game #, date, kickoff, field, league and age group into Match Setup (used on the report forms). */
    private fun applyCardInfo(info: CardInfo) {
        val d = MatchDetails.load(this)
        val kickoff = info.kickoff.removePrefix("0")
        d.copy(
            gameNumber = info.gameNumber.ifBlank { d.gameNumber },
            gameDate = info.date.ifBlank { d.gameDate },
            kickoff = kickoff.ifBlank { d.kickoff },
            venue = info.venue.ifBlank { d.venue },
            competition = info.league.ifBlank { d.competition },
            division = info.ageGroup.ifBlank { d.division }
        ).save(this)
        binding.txtOcrStatus.text = "✓ Match info saved to Match Setup"
        binding.btnApplyCardInfo.text = "Saved ✓"
    }

    // ── Review ──

    private fun setupRosterReview() {
        rosterAdapter = RosterReviewAdapter { index, player ->
            val roster = currentRoster()
            if (index < roster.size) {
                if (player == null) roster.removeAt(index) else roster[index] = player
            }
            showRosterForCurrentSide()
        }
        binding.recyclerRosterReview.apply {
            layoutManager = LinearLayoutManager(this@RosterSetupActivity); adapter = rosterAdapter
            isNestedScrollingEnabled = true
        }
    }

    // ── Sending ──

    private fun setupSendButton() { binding.btnSendToWatch.setOnClickListener { sendRostersToWatch() } }

    private fun sendRostersToWatch() {
        val homeName = binding.editHomeName.text.toString().ifBlank { "Home" }
        val awayName = binding.editAwayName.text.toString().ifBlank { "Away" }
        // A player still marked for review is sent as-is: the list above is what you confirmed
        val homeTeam = Team(homeName, homeRoster.map { it.copy(flagged = false) }, homeCoaches.toList(), true)
        val awayTeam = Team(awayName, awayRoster.map { it.copy(flagged = false) }, awayCoaches.toList(), false)

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
        // Same format the watch and phone use everywhere (includes player ID / DOB / CPP)
        val json = MatchJson.teamToJson(team).toString()
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
        private const val KEY_CROP_URI = "crop_source_uri"
        /** Big enough that a cropped half of the card still has readable text. */
        private const val OCR_MAX_SIDE = 3200
    }
}

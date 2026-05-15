package com.zalopilot.app.accessibility.engine

import com.zalopilot.app.accessibility.NodeFinder
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.delay
import org.json.JSONObject

@Singleton
class ZPScriptRunner @Inject constructor(
    private val nodeFinder: NodeFinder,
    private val idStore: ZaloIDStore,
    private val settingsManager: LikeSettingsManager,
    private val progressManager: LikeProgressManager,
    private val logger: Logger
) {
    private var gotoCount = 0

    suspend fun run(
        service: ZaloPilotAccessibilityService,
        script: ZPScript,
        testOneRound: Boolean = false,
        maxProfiles: Int? = null
    ): Boolean {
        val engine = ZPEngine(service, nodeFinder, idStore, settingsManager, progressManager, logger)
        gotoCount = 0
        val profilesLimit = maxProfiles ?: settingsManager.getVisitMaxProfiles()
        var profilesDone = 0
        var index = 0
        var ensureScreenStreak = 0
        val steps = script.steps

        fun stepIdAt(i: Int): String? = steps.getOrNull(i)?.id

        while (service.isRunning && index < steps.size) {
            val step = steps[index]
            val t0 = System.currentTimeMillis()
            val ok = runStepWithRetry(service, engine, step, testOneRound)
            val elapsed = System.currentTimeMillis() - t0
            logger.log(
                LogTag.STATE,
                "id=${step.id ?: "-"} action=${step.action} ok=$ok ${elapsed}ms",
                "SCRIPT_STEP"
            )

            when (step.action.lowercase()) {
                "goto" -> {
                    gotoCount++
                    if (gotoCount > MAX_GOTO) {
                        logger.log(LogTag.ERROR, "gotoCount=$gotoCount", "SCRIPT_GOTO_LIMIT")
                        return false
                    }
                    profilesDone++
                    if (profilesDone >= profilesLimit) {
                        logger.log(LogTag.STATE, "profiles=$profilesDone", "SCRIPT_PROFILE_LIMIT")
                        return true
                    }
                    if (testOneRound) return true
                    val target = step.step
                    val jump = steps.indexOfFirst { it.id == target }
                    if (jump >= 0) {
                        index = jump
                        continue
                    }
                    logger.log(LogTag.ERROR, "missing step=$target", "SCRIPT_GOTO_FAIL")
                    return false
                }
                "incrementvar" -> {
                    if (normalizeVar(step.varName) == "visitindex") {
                        profilesDone++
                    }
                }
            }

            if (!ok && step.action.lowercase() !in SKIP_ON_FAIL_ACTIONS) {
                if (step.action.lowercase() == "ensurescreen" && ensureScreenStreak < 10) {
                    ensureScreenStreak++
                    val screen = step.screen.orEmpty()
                    val fgRoot = service.rootInActiveWindow
                    val pkg = fgRoot?.packageName?.toString().orEmpty()
                    runCatching { fgRoot?.recycle() }
                    logger.log(LogTag.ERROR, "screen=$screen pkg=$pkg streak=$ensureScreenStreak", "ENSURE_SCREEN_RETRY")
                    if (!service.isZaloMainForeground()) {
                        service.showToast("⚠️ Mở Zalo → Danh bạ → Bạn bè rồi để bot chạy")
                        service.waitForZaloMainForeground(12_000L)
                    } else {
                        delay(600)
                    }
                    continue
                }
                logger.log(LogTag.ERROR, step.action, "SCRIPT_STEP_FAIL_STOP")
                return false
            }
            if (ok) ensureScreenStreak = 0

            index++
        }
        return true
    }

    fun loadScriptJson(json: JSONObject): ZPScript = ZPScriptParser.parse(json)

    private suspend fun runStepWithRetry(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine,
        step: ZPStep,
        testOneRound: Boolean
    ): Boolean {
        if (runStep(service, engine, step, testOneRound)) return true
        delay(400)
        if (runStep(service, engine, step, testOneRound)) return true
        val root = engine.acquireRoot() ?: return false
        return try {
            if (service.scriptDetectAndEscapeWrongScreen(root)) {
                delay(500)
                runStep(service, engine, step, testOneRound)
            } else {
                false
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private suspend fun runStep(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine,
        step: ZPStep,
        testOneRound: Boolean
    ): Boolean {
        return when (step.action.lowercase()) {
            "ensurescreen" -> runEnsureScreen(engine, step)
            "findcontactitems" -> runFindContactItems(engine)
            "scrollcontacts" -> {
                engine.scrollContacts()
                delay(650)
                true
            }
            "logstoreids" -> {
                logger.log(
                    LogTag.STATE,
                    "like=${idStore.getLikeButtonID()} feedRv=${idStore.getFeedRecyclerID()} " +
                        "contacts=${idStore.getContactListID()} item=${idStore.getContactItemID()}",
                    "STORE_IDS"
                )
                true
            }
            "tapcontactat" -> runTapContactAt(engine, step)
            "tapprofileentry" -> {
                val root = engine.acquireRoot() ?: return false
                try {
                    val node = nodeFinder.findProfileEntryNode(root) ?: return false
                    val target = ScriptTapTarget.fromNode(node) ?: return false
                    engine.tap(target)
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "likeprofileposts" -> {
                val max = engine.resolveVarInt(step.count ?: "\$visitLikeCount")
                val result = engine.runProfileLikeLoop(max)
                logger.log(
                    LogTag.STATE,
                    "liked=${result.likedCount} noPosts=${result.noPostsOnProfile}",
                    "PROFILE_LIKE_DONE"
                )
                true
            }
            "findlikebutton" -> {
                val root = engine.acquireRoot() ?: return false
                try {
                    val btn = if (engine.detectScreen(root, "profile")) {
                        nodeFinder.findProfileLikeButtons(root)
                            .firstOrNull { nodeFinder.shouldLike(it) }
                    } else {
                        nodeFinder.findLikeButtons(root).firstOrNull()
                    } ?: return false
                    engine.lastLikeTarget = ScriptTapTarget.fromNode(btn) ?: return false
                    true
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "taplike" -> {
                val target = engine.lastLikeTarget ?: return false
                engine.tap(target)
            }
            "verifyliked" -> {
                val root = engine.acquireRoot() ?: return false
                try {
                    engine.verifyLiked(root)
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "findcommentbutton" -> {
                val likeTarget = engine.lastLikeTarget ?: return false
                val root = engine.acquireRoot() ?: return false
                try {
                    val likeNode = nodeFinder.findLikeAreaNodeAt(
                        root,
                        likeTarget.bounds,
                        likeTarget.viewId
                    ) ?: return false
                    val btn = nodeFinder.findCommentButton(likeNode) ?: return false
                    engine.lastTapTarget = ScriptTapTarget.fromNode(btn) ?: return false
                    true
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "tap" -> {
                val target = engine.lastTapTarget ?: engine.lastLikeTarget ?: return false
                engine.tap(target)
            }
            "inputrandomcomment" -> {
                val root = engine.acquireRoot() ?: return false
                try {
                    engine.inputRandomComment(root)
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "tapsend" -> {
                val root = engine.acquireRoot() ?: return false
                try {
                    engine.tapSend(root)
                } finally {
                    runCatching { root.recycle() }
                }
            }
            "back" -> engine.back()
            "wait" -> {
                delay(step.ms ?: 500L)
                true
            }
            "repeat" -> {
                val n = engine.resolveVarInt(step.count)
                if (n <= 0) return true
                for (i in 0 until n) {
                    if (!service.isRunning) return false
                    for (child in step.doSteps) {
                        if (!runStepWithRetry(service, engine, child, testOneRound)) return false
                    }
                }
                true
            }
            "ifsetting" -> {
                if (!evaluateIfSetting(step)) return true
                for (child in step.doSteps) {
                    if (!runStepWithRetry(service, engine, child, testOneRound)) return false
                }
                true
            }
            "incrementvar" -> {
                engine.incrementVar(step.varName ?: "visitIndex")
                true
            }
            "savevar" -> {
                if (normalizeVar(step.varName) == "visitindex") {
                    progressManager.saveVisitIndex(progressManager.getVisitIndex())
                }
                true
            }
            "goto" -> true
            else -> {
                logger.log(LogTag.ERROR, step.action, "SCRIPT_UNKNOWN_ACTION")
                false
            }
        }
    }

    private suspend fun runEnsureScreen(engine: ZPEngine, step: ZPStep): Boolean {
        val screen = step.screen ?: return false
        val timeout = when {
            step.timeoutMs != null -> step.timeoutMs
            screen.equals("contacts", ignoreCase = true) -> 6_000L
            else -> 4_000L
        }
        if (screen.equals("contacts", ignoreCase = true)) {
            engine.scrollContacts()
            delay(350)
        }
        return engine.ensureScreen(
            detect = {
                val root = engine.acquireRoot() ?: return@ensureScreen false
                try {
                    engine.detectScreen(root, screen)
                } finally {
                    runCatching { root.recycle() }
                }
            },
            navigate = {
                when (screen.lowercase()) {
                    "contacts" -> {
                        engine.scrollContacts()
                        delay(400)
                    }
                    else -> engine.back()
                }
            },
            timeoutMs = timeout
        )
    }

    private fun evaluateIfSetting(step: ZPStep): Boolean {
        val key = step.key ?: return false
        val value = when (key) {
            "visitCommentCount" -> settingsManager.getVisitCommentCount()
            "visitLikeCount" -> settingsManager.getVisitLikeCount()
            "visitMaxProfiles" -> settingsManager.getVisitMaxProfiles()
            else -> 0
        }
        step.gt?.let { if (value > it) return true }
        step.gte?.let { if (value >= it) return true }
        step.lt?.let { if (value < it) return true }
        step.lte?.let { if (value <= it) return true }
        step.eq?.let { if (value == it) return true }
        return false
    }

    private suspend fun runFindContactItems(engine: ZPEngine): Boolean {
        engine.clearTapCache()
        suspend fun scanOnce(): List<ScriptTapTarget> {
            val root = engine.acquireRoot() ?: return emptyList()
            return try {
                nodeFinder.findContactListItems(root)
                    .mapNotNull { ScriptTapTarget.fromNode(it) }
            } finally {
                runCatching { root.recycle() }
            }
        }
        var targets = scanOnce()
        if (targets.isEmpty()) {
            engine.scrollContacts()
            delay(700)
            targets = scanOnce()
        }
        engine.lastContactTargets = targets
        if (targets.isNotEmpty()) {
            logger.log(LogTag.SCAN, "contacts=${targets.size}", "VISIT_CONTACTS_FOUND")
        } else {
            logger.log(LogTag.SCAN, "contacts", "VISIT_CONTACTS_EMPTY")
        }
        return targets.isNotEmpty()
    }

    /** Luôn tap hàng bạn đầu tiên trên màn hình — $visitIndex chỉ đếm đã visit, không phải index list. */
    private suspend fun runTapContactAt(engine: ZPEngine, step: ZPStep): Boolean {
        if (engine.lastContactTargets.isEmpty()) return false
        val visibleIndex = 0
        if (engine.tapNodeAt(engine.lastContactTargets, visibleIndex)) return true
        engine.scrollContacts()
        delay(700)
        if (!runFindContactItems(engine)) return false
        return engine.tapNodeAt(engine.lastContactTargets, visibleIndex)
    }

    private fun normalizeVar(raw: String?): String =
        raw?.trim()?.removePrefix("$")?.lowercase().orEmpty()

    companion object {
        private const val MAX_GOTO = 10_000
        private val SKIP_ON_FAIL_ACTIONS = setOf("goto", "savevar", "incrementvar")
    }
}

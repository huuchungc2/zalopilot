package com.zalopilot.app.accessibility.engine

import android.view.accessibility.AccessibilityNodeInfo
import com.zalopilot.app.accessibility.NodeFinder
import com.zalopilot.app.accessibility.ZaloPilotAccessibilityService
import com.zalopilot.app.data.model.ZaloIDStore
import com.zalopilot.app.util.LikeProgressManager
import com.zalopilot.app.util.LikeSettingsManager
import com.zalopilot.app.util.LogTag
import com.zalopilot.app.util.Logger
import com.zalopilot.app.util.VisitActionMode
import com.zalopilot.app.util.VisitHandledContactsManager
import com.zalopilot.app.util.VisitHandledOutcome
import com.zalopilot.app.util.hasValidScreenBounds
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
    private val visitHandledContacts: VisitHandledContactsManager,
    private val logger: Logger
) {
    private var gotoCount = 0
    /** Mỗi vòng visit: chỉ [profilesDone]++ khi nhánh goto nếu round này không bị đánh dấu incomplete (vd. profile không có bài). */
    private var visitRoundSuccess = true
    /** Index trong batch contact hiện tại (0..n-1); scroll chỉ khi hết batch. */
    private var contactBatchIndex = 0
    private var contactBatchActive = false
    private var visitRecoveryStreak = 0
    /** Kẹt chat/profile — bỏ like/comment vòng này, nhảy về danh bạ. */
    private var skipVisitProfileSteps = false
    /** Tên/key contact đang xử lý trong vòng hiện tại (ghi persist ở goto). */
    private var currentVisitDisplayName: String? = null
    private var currentVisitKey: String? = null
    /** Cuộn A–Z: hàng dưới cùng màn trước đó — trùng sau cuộn = hết list / kẹt. */
    private var alphaScrollBottomKey: String? = null

    suspend fun run(
        service: ZaloPilotAccessibilityService,
        script: ZPScript,
        testOneRound: Boolean = false,
        maxProfiles: Int? = null
    ): Boolean {
        val engine = ZPEngine(service, nodeFinder, idStore, settingsManager, progressManager, logger)
        gotoCount = 0
        visitRoundSuccess = true
        contactBatchIndex = 0
        contactBatchActive = false
        visitRecoveryStreak = 0
        skipVisitProfileSteps = false
        currentVisitDisplayName = null
        currentVisitKey = null
        alphaScrollBottomKey = null
        val profilesLimit = maxProfiles ?: settingsManager.getVisitMaxProfiles()
        logger.log(
            LogTag.STATE,
            "handled=${visitHandledContacts.count()}",
            "VISIT_HANDLED_LOADED"
        )
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
                    persistCurrentVisitContact(visitRoundSuccess)
                    gotoCount++
                    if (gotoCount > MAX_GOTO) {
                        logger.log(LogTag.ERROR, "gotoCount=$gotoCount", "SCRIPT_GOTO_LIMIT")
                        return false
                    }
                    if (visitRoundSuccess) {
                        profilesDone++
                    } else {
                        logger.log(LogTag.STATE, "profilesDone skip (round incomplete)", "PROFILES_DONE_SKIP")
                    }
                    visitRoundSuccess = true
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
                "incrementvar" -> { /* visitIndex — đếm profile ở goto, tránh +2/lượt */ }
            }

            if (!ok && step.action.lowercase() !in SKIP_ON_FAIL_ACTIONS) {
                if (shouldSkipContactAfterVisitFailure(step)) {
                    val jump = skipStuckContactAndJumpToContacts(service, engine, steps, step)
                    if (jump >= 0) {
                        index = jump
                        visitRecoveryStreak = 0
                        continue
                    }
                }
                if (visitRecoveryStreak < MAX_VISIT_RECOVERY &&
                    runVisitStepRecovery(service, engine, step)
                ) {
                    visitRecoveryStreak++
                    logger.log(
                        LogTag.STATE,
                        "action=${step.action} streak=$visitRecoveryStreak",
                        "VISIT_STEP_RECOVERY_RETRY"
                    )
                    continue
                }
                visitRecoveryStreak = 0
                if (step.action.lowercase() == "ensurescreen" && ensureScreenStreak < 10) {
                    ensureScreenStreak++
                    val screen = step.screen.orEmpty()
                    val fgRoot = service.rootInActiveWindow
                    val pkg = fgRoot?.packageName?.toString().orEmpty()
                    runCatching { fgRoot?.recycle() }
                    logger.log(LogTag.ERROR, "screen=$screen pkg=$pkg streak=$ensureScreenStreak", "ENSURE_SCREEN_RETRY")
                    if (!service.isZaloMainForeground()) {
                        val hint = when (screen.lowercase()) {
                            "profile" -> "⚠️ Về lại Zalo (đang ở màn hình ngoài) — đừng bấm Home"
                            "chat" -> "⚠️ Mở lại Zalo — đang chat/profile"
                            else -> "⚠️ Mở Zalo → Danh bạ → Bạn bè rồi để bot chạy"
                        }
                        service.showToast(hint)
                        service.ensureZaloForegroundForBot(15_000L)
                    } else {
                        delay(600)
                    }
                    continue
                }
                logger.log(LogTag.ERROR, step.action, "SCRIPT_STEP_FAIL_STOP")
                return false
            }
            if (ok) {
                ensureScreenStreak = 0
                visitRecoveryStreak = 0
            }

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
            "scrollcontacts" -> runScrollContactsWhenBatchDone(engine)
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
                if (skipVisitProfileSteps) {
                    logger.log(LogTag.STATE, "visit", "PROFILE_ENTRY_SKIPPED_FLAG")
                    true
                } else {
                    runTapProfileEntry(service, engine)
                }
            }
            "sendvisitchatmessage" -> {
                refreshCurrentVisitKeyFromChat(engine)
                val ok = engine.sendOneVisitChatMessage()
                if (ok) {
                    progressManager.incrementPostsHandledAndSave()
                    service.notifyProgressUpdate()
                }
                ok
            }
            "likeprofileposts" -> {
                if (skipVisitProfileSteps) {
                    skipVisitProfileSteps = false
                    logger.log(LogTag.STATE, "visit", "PROFILE_LIKE_SKIPPED_STUCK")
                    true
                } else {
                val mode = settingsManager.getVisitActionMode()
                if (mode == VisitActionMode.CHAT_ONLY) {
                    logger.log(LogTag.STATE, "skip likes (CHAT_ONLY)", "PROFILE_LIKE_MODE")
                    return true
                }
                val requested = engine.resolveVarInt(step.count ?: "\$visitLikeCount")
                val max = when (mode) {
                    VisitActionMode.COMMENT_ONLY -> 0
                    VisitActionMode.LIKE_ONLY, VisitActionMode.MIX -> requested
                }
                if (mode == VisitActionMode.COMMENT_ONLY) {
                    logger.log(LogTag.STATE, "skip likes (COMMENT_ONLY)", "PROFILE_LIKE_MODE")
                    service.showToast("⏭ Visit: COMMENT_ONLY — bỏ like profile")
                }
                val result = engine.runProfileLikeLoop(max)
                logger.log(
                    LogTag.STATE,
                    "liked=${result.likedCount} noPosts=${result.noPostsOnProfile}",
                    "PROFILE_LIKE_DONE"
                )
                if (result.likedCount == 0 && result.noPostsOnProfile) {
                    visitRoundSuccess = false
                    service.showToast("⚠️ Profile: không có bài để like — đánh dấu vòng lỗi")
                }
                true
                }
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
                val root = engine.acquireRoot() ?: return false
                try {
                    var likeTarget = engine.lastLikeTarget
                    if (likeTarget == null && engine.detectScreen(root, "profile")) {
                        val anchor = nodeFinder.findProfileLikeButtons(root)
                            .firstOrNull { nodeFinder.shouldLike(it) }
                            ?: nodeFinder.findProfileLikeButtons(root).firstOrNull()
                        if (anchor != null) {
                            ScriptTapTarget.fromNode(anchor)?.let {
                                engine.lastLikeTarget = it
                                likeTarget = it
                            }
                        }
                    }
                    val lt = likeTarget ?: return false
                    val likeNode = nodeFinder.findLikeAreaNodeAt(
                        root,
                        lt.bounds,
                        lt.viewId
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
            "backtocontacts" -> runBackToContactList(service, engine)
            /** Về danh sách Bạn bè bằng tab Danh bạ + sub Bạn bè — tránh nhiều GLOBAL_BACK (kẹt launcher / chặn tay.) */
            "opencontactsfriends" -> runOpenContactsFriends(service, engine)
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
        val mode = settingsManager.getVisitActionMode()
        if (key == "visitCommentCount" &&
            mode in setOf(VisitActionMode.LIKE_ONLY, VisitActionMode.CHAT_ONLY)
        ) {
            return false
        }
        when (key) {
            "visitActionChat" ->
                return mode == VisitActionMode.CHAT_ONLY && settingsManager.getVisitChatCount() > 0
            "visitActionProfile" ->
                return mode != VisitActionMode.CHAT_ONLY
        }
        val value = when (key) {
            "visitCommentCount" -> settingsManager.getVisitCommentCount()
            "visitChatCount" -> settingsManager.getVisitChatCount()
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

    private suspend fun ensureVisitFriendsListVisible(engine: ZPEngine): Boolean {
        val root = engine.acquireRoot() ?: return false
        return try {
            if (!nodeFinder.isContactsMessagePreviewListVisible(root)) {
                return nodeFinder.hasOnScreenVisitFriendRows(root)
            }
            logger.log(LogTag.SCAN, "contacts", "VISIT_SWITCH_MSG_PREVIEW_TO_DIRECTORY")
            nodeFinder.findContactsDirectoryPagerSwitch(root)?.let { tab ->
                ScriptTapTarget.fromNode(tab)?.let { engine.tap(it) }
            }
            delay(750)
            val fresh = engine.acquireRoot() ?: return false
            try {
                nodeFinder.hasOnScreenVisitFriendRows(fresh)
            } finally {
                runCatching { fresh.recycle() }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private suspend fun runFindContactItems(engine: ZPEngine): Boolean {
        ensureVisitFriendsListVisible(engine)
        if (contactBatchActive && engine.lastContactTargets.isNotEmpty() &&
            contactBatchIndex < engine.lastContactTargets.size
        ) {
            logger.log(
                LogTag.SCAN,
                "idx=$contactBatchIndex/${engine.lastContactTargets.size}",
                "VISIT_BATCH_CONTINUE"
            )
            return true
        }
        if (contactBatchActive && engine.lastContactTargets.isNotEmpty() &&
            contactBatchIndex >= engine.lastContactTargets.size
        ) {
            logger.log(LogTag.SCAN, "batch", "VISIT_BATCH_SCROLL_NEW")
            engine.scrollContacts()
            delay(700)
            contactBatchIndex = 0
        }
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
        var filtered = filterUnhandledContactTargets(targets)
        if (filtered.isEmpty() && targets.isNotEmpty()) {
            val bottomKey = bottomContactKeyFromTargets(targets)
            if (bottomKey != null && bottomKey == alphaScrollBottomKey) {
                logger.log(LogTag.SCAN, "bottom=$bottomKey", "VISIT_ALPHA_END_ALL_HANDLED")
                engine.lastContactTargets = emptyList()
                contactBatchActive = false
                return false
            }
            alphaScrollBottomKey = bottomKey
            logger.log(
                LogTag.SCAN,
                "visible=${targets.size} bottom=$bottomKey",
                "VISIT_HANDLED_SCROLL_ALPHA"
            )
            engine.scrollContacts()
            delay(700)
            targets = scanOnce()
            filtered = filterUnhandledContactTargets(targets)
        } else if (filtered.isNotEmpty()) {
            alphaScrollBottomKey = null
        }
        engine.lastContactTargets = filtered
        contactBatchActive = targets.isNotEmpty()
        if (filtered.isNotEmpty()) {
            logger.log(LogTag.SCAN, "contacts=${filtered.size}", "VISIT_CONTACTS_FOUND")
        } else {
            logger.log(LogTag.SCAN, "contacts", "VISIT_CONTACTS_EMPTY")
        }
        return filtered.isNotEmpty()
    }

    private suspend fun runScrollContactsWhenBatchDone(engine: ZPEngine): Boolean {
        val targets = engine.lastContactTargets
        if (targets.isNotEmpty() && contactBatchIndex < targets.size) {
            logger.log(
                LogTag.SCAN,
                "idx=$contactBatchIndex/${targets.size}",
                "SCROLL_SKIP_MID_BATCH"
            )
            return true
        }
        engine.scrollContacts()
        delay(650)
        contactBatchActive = false
        contactBatchIndex = 0
        engine.clearTapCache()
        return true
    }

    /** Tap contact theo thứ tự trong batch — không cuộn giữa batch. */
    private suspend fun runTapContactAt(engine: ZPEngine, step: ZPStep): Boolean {
        if (engine.lastContactTargets.isEmpty()) return false
        val targets = engine.lastContactTargets
        if (contactBatchIndex >= targets.size) {
            logger.log(LogTag.CLICK, "batchIdx=$contactBatchIndex", "TAP_CONTACT_BATCH_EXHAUSTED")
            return false
        }
        var index = contactBatchIndex
        while (index < targets.size) {
            val key = visitHandledContacts.keyFromTapLabel(targets[index].label)
            if (key == null || !visitHandledContacts.isHandled(key)) break
            logger.log(LogTag.CLICK, "key=$key", "TAP_CONTACT_SKIP_HANDLED")
            index++
        }
        if (index >= targets.size) {
            contactBatchIndex = index
            return false
        }
        contactBatchIndex = index
        val stored = progressManager.getVisitIndex()
        val tapped = targets[index]
        visitHandledContacts.firstLineDisplayName(tapped.label)?.let { name ->
            currentVisitDisplayName = name
            currentVisitKey = visitHandledContacts.keyFromTapLabel(tapped.label)
        }
        logger.log(
            LogTag.CLICK,
            "batchIdx=$contactBatchIndex visitIndex=$stored index=$index/${targets.size} " +
                "name=${currentVisitDisplayName ?: "?"}",
            "TAP_CONTACT"
        )
        if (!engine.tapNodeAt(targets, index)) {
            logger.log(LogTag.CLICK, "index=$index", "TAP_CONTACT_FAIL")
            return false
        }
        contactBatchIndex++
        return true
    }

    private fun filterUnhandledContactTargets(targets: List<ScriptTapTarget>): List<ScriptTapTarget> =
        targets.filter { t ->
            val key = visitHandledContacts.keyFromTapLabel(t.label) ?: return@filter false
            !visitHandledContacts.isHandled(key)
        }

    /** Hàng dưới cùng trên màn (danh bạ A–Z — cuộn xuống = tên lớn hơn). */
    private fun bottomContactKeyFromTargets(targets: List<ScriptTapTarget>): String? =
        targets.maxByOrNull { it.bounds.bottom }?.label?.let { visitHandledContacts.keyFromTapLabel(it) }

    private fun refreshCurrentVisitKeyFromChat(engine: ZPEngine) {
        val root = engine.acquireRoot() ?: return
        try {
            nodeFinder.findChatContactDisplayName(root)?.let { name ->
                currentVisitDisplayName = name
                currentVisitKey = visitHandledContacts.normalizeKey(name)
                logger.log(LogTag.STATE, "key=$currentVisitKey", "VISIT_CONTACT_KEY_CHAT_TITLE")
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun persistCurrentVisitContact(roundSuccess: Boolean) {
        val key = currentVisitKey
        val name = currentVisitDisplayName
        currentVisitDisplayName = null
        currentVisitKey = null
        if (!roundSuccess || key.isNullOrBlank() || name.isNullOrBlank()) return
        val outcome = when (settingsManager.getVisitActionMode()) {
            VisitActionMode.LIKE_ONLY -> VisitHandledOutcome.LIKE
            VisitActionMode.COMMENT_ONLY -> VisitHandledOutcome.COMMENT
            VisitActionMode.CHAT_ONLY -> VisitHandledOutcome.CHAT
            VisitActionMode.MIX -> VisitHandledOutcome.MIX
        }
        visitHandledContacts.record(name, outcome, key)
        logger.log(LogTag.STATE, "key=$key outcome=${outcome.name}", "VISIT_HANDLED_SAVED")
    }

    private fun shouldSkipContactAfterVisitFailure(step: ZPStep): Boolean {
        val action = step.action.lowercase()
        return visitRecoveryStreak >= MAX_VISIT_RECOVERY &&
            action in VISIT_FAIL_SKIP_CONTACT_ACTIONS
    }

    private suspend fun skipStuckContactAndJumpToContacts(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine,
        steps: List<ZPStep>,
        failedStep: ZPStep
    ): Int {
        visitRoundSuccess = false
        skipVisitProfileSteps = true
        currentVisitDisplayName = null
        currentVisitKey = null
        logger.log(LogTag.STATE, "action=${failedStep.action}", "VISIT_SKIP_STUCK_CONTACT")
        service.showToast("⏭ Kẹt màn hình — bỏ người này, về danh bạ…")
        if (!runOpenContactsFriends(service, engine)) {
            logger.log(LogTag.ERROR, "openContactsFriends", "VISIT_SKIP_OPEN_CONTACTS_FAIL")
            return -1
        }
        val scrollIdx = steps.indexOfFirst { it.action.equals("scrollcontacts", ignoreCase = true) }
        if (scrollIdx >= 0) return scrollIdx
        return steps.indexOfFirst { it.action.equals("opencontactsfriends", ignoreCase = true) }
    }

    /**
     * Mở lại màn Danh bạ → Bạn bè (tap tab + sub-tab), không dùng chuỗi BACK như [runBackToContactList].
     */
    private suspend fun runOpenContactsFriends(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine
    ): Boolean {
        for (attempt in 0 until 6) {
            val check0 = engine.acquireRoot() ?: return false
            try {
                if (nodeFinder.isContactListScreen(check0)) {
                    logger.log(LogTag.STATE, "attempt=$attempt", "OPEN_CONTACTS_LIST_OK")
                    return true
                }
            } finally {
                runCatching { check0.recycle() }
            }

            val rMain = engine.acquireRoot() ?: return false
            try {
                nodeFinder.findContactMainTabTapTarget(rMain)?.let { tab ->
                    ScriptTapTarget.fromNode(tab)?.let {
                        engine.tap(it)
                        logger.log(LogTag.CLICK, "attempt=$attempt", "OPEN_CONTACTS_MAIN_TAB")
                    }
                }
            } finally {
                runCatching { rMain.recycle() }
            }
            delay(750)

            val rSub = engine.acquireRoot() ?: return false
            try {
                if (nodeFinder.isContactListScreen(rSub)) {
                    logger.log(LogTag.STATE, "attempt=$attempt", "OPEN_CONTACTS_AFTER_MAIN")
                    return true
                }
                nodeFinder.findFriendsSubTabTapTarget(rSub)?.let { sub ->
                    ScriptTapTarget.fromNode(sub)?.let {
                        engine.tap(it)
                        logger.log(LogTag.CLICK, "attempt=$attempt", "OPEN_CONTACTS_FRIENDS_SUB")
                    }
                }
            } finally {
                runCatching { rSub.recycle() }
            }
            delay(650)

            val check1 = engine.acquireRoot() ?: return false
            try {
                if (nodeFinder.isContactListScreen(check1)) {
                    logger.log(LogTag.STATE, "attempt=$attempt", "OPEN_CONTACTS_AFTER_SUB")
                    return true
                }
            } finally {
                runCatching { check1.recycle() }
            }

            if (attempt >= 2) {
                logger.log(LogTag.STATE, "attempt=$attempt", "OPEN_CONTACTS_BACK_ONCE")
                engine.back()
                delay(700)
            }
        }

        if (!service.ensureZaloForegroundForBot(8_000L)) {
            logger.log(LogTag.SCAN, "openContactsFriends", "NO_ZALO")
            return false
        }
        val last = engine.acquireRoot() ?: return false
        return try {
            val ok = nodeFinder.isContactListScreen(last)
            if (!ok) logger.log(LogTag.SCAN, "openContactsFriends", "FINAL_FAIL")
            ok
        } finally {
            runCatching { last.recycle() }
        }
    }

    /** Back từ profile/chat tới Danh bạ — tránh back thừa ra launcher. */
    private suspend fun runBackToContactList(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine
    ): Boolean {
        for (attempt in 0 until 5) {
            val root = engine.acquireRoot()
            if (root == null) break
            val onList = try {
                nodeFinder.isContactListScreen(root)
            } finally {
                runCatching { root.recycle() }
            }
            if (onList) {
                logger.log(LogTag.STATE, "attempt=$attempt", "BACK_ON_CONTACTS")
                return true
            }
            engine.back()
            delay(550)
        }
        if (!service.ensureZaloForegroundForBot(10_000L)) {
            logger.log(LogTag.SCAN, "contacts", "BACK_CONTACTS_NO_ZALO")
            return false
        }
        val root = engine.acquireRoot() ?: return false
        return try {
            val ok = nodeFinder.isContactListScreen(root)
            if (!ok) logger.log(LogTag.SCAN, "contacts", "BACK_CONTACTS_FAIL")
            ok
        } finally {
            runCatching { root.recycle() }
        }
    }

    private fun normalizeVar(raw: String?): String =
        raw?.trim()?.removePrefix("$")?.lowercase().orEmpty()

    private suspend fun runTapProfileEntry(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine
    ): Boolean {
        service.showToast("👤 Mở profile từ contact…")
        val root = engine.acquireRoot()
        if (root == null) {
            service.showToast("⚠️ Không đọc UI — mở profile thất bại")
            return false
        }
        try {
            if (nodeFinder.isProfileTimelineReady(root)) {
                logger.log(LogTag.STATE, "tapProfileEntry", "ALREADY_ON_TIMELINE")
                service.showToast("✅ Đã ở timeline profile")
                return true
            }
            if (nodeFinder.isChatScreen(root)) {
                service.showToast("💬 Đang chat — tap mở profile…")
                refreshCurrentVisitKeyFromChat(engine)
                nodeFinder.findChatOpenProfileMoreInfoButton(root)?.let { more ->
                    logger.log(LogTag.CLICK, "tapProfileEntry", "CHAT_MORE_INFO")
                    if (service.scriptTapProfileEntryNode(more)) {
                        delay(1_100)
                        if (service.waitForProfileScreen(5_000L)) {
                            service.showToast("✅ Profile từ menu thông tin")
                            return true
                        }
                    }
                }
                nodeFinder.findChatIntroProfileTapTarget(root)?.let { intro ->
                    logger.log(LogTag.CLICK, "tapProfileEntry", "INTRO_CARD")
                    if (service.scriptTapProfileEntryNode(intro)) {
                        delay(1_100)
                        if (service.waitForProfileScreen(5_000L)) {
                            service.showToast("✅ Profile từ thẻ giữa chat")
                            return true
                        }
                    }
                }
                tryTapProfileTitleBar(service, root, "CHAT_TITLE")
                delay(1_000)
                if (service.waitForProfileScreen(4_000L)) {
                    service.showToast("✅ Profile từ thanh tên chat")
                    return true
                }
            }
            val node = nodeFinder.findProfileEntryNode(root)
            if (node != null && service.scriptTapProfileEntryNode(node)) {
                delay(900)
                if (service.waitForProfileScreen(6_000L)) {
                    service.showToast("✅ Đã vào profile")
                    return true
                }
            }
            val r2 = engine.acquireRoot()
            if (r2 == null) {
                service.showToast("⚠️ Mất UI sau tap profile")
                return false
            }
            try {
                if (nodeFinder.isProfileTimelineReady(r2)) {
                    service.showToast("✅ Timeline profile sẵn sàng")
                    return true
                }
                if (nodeFinder.isChatScreen(r2)) {
                    service.showToast("🔄 Vẫn chat — thử lại tên…")
                    tryTapProfileTitleBar(service, r2, "RETRY_CHAT")
                    delay(1_000)
                }
            } finally {
                runCatching { r2.recycle() }
            }
            if (service.waitForProfileScreen(4_000L)) {
                service.showToast("✅ Profile (chờ UI)")
                return true
            }
            val r3 = engine.acquireRoot()
            if (r3 == null) {
                service.showToast("⚠️ Không đọc UI lần cuối")
                return false
            }
            return try {
                when {
                    nodeFinder.isProfileTimelineReady(r3) -> {
                        service.showToast("✅ Timeline profile OK")
                        true
                    }
                    nodeFinder.isChatScreen(r3) -> {
                        logger.log(LogTag.ERROR, "tapProfileEntry", "STILL_CHAT_AFTER_TAPS")
                        service.showToast("❌ Kẹt chat — không vào profile")
                        false
                    }
                    else -> {
                        service.showToast("❌ Không nhận profile/chat — kiểm tra màn")
                        false
                    }
                }
            } finally {
                runCatching { r3.recycle() }
            }
        } finally {
            runCatching { root.recycle() }
        }
    }

    private suspend fun runVisitStepRecovery(
        service: ZaloPilotAccessibilityService,
        engine: ZPEngine,
        step: ZPStep
    ): Boolean {
        return when (step.action.lowercase()) {
            "tapprofileentry" -> {
                if (visitRecoveryStreak >= 2) {
                    logger.log(LogTag.STATE, "streak=$visitRecoveryStreak", "PROFILE_RECOVERY_OPEN_CONTACTS")
                    runOpenContactsFriends(service, engine)
                    delay(500)
                } else {
                    engine.back()
                    delay(650)
                    engine.back()
                    delay(900)
                }
                true
            }
            "tapcontactat" -> {
                engine.scrollContacts()
                delay(750)
                true
            }
            "opencontactsfriends" -> {
                service.ensureZaloForegroundForBot(12_000L)
                delay(500)
                true
            }
            else -> false
        }
    }

    private suspend fun tryTapProfileTitleBar(
        service: ZaloPilotAccessibilityService,
        r: AccessibilityNodeInfo,
        label: String
    ): Boolean {
        val mid = nodeFinder.findByViewId(r, "actionbar_middle_container")
            .firstOrNull()
            ?.takeIf { it.hasValidScreenBounds() }
        if (mid != null) {
            logger.log(LogTag.CLICK, "tapProfileEntry", "${label}_MIDDLE")
            return service.scriptTapProfileEntryNode(mid)
        }
        val title = nodeFinder.findByViewId(r, "actionbar_txtTitle").firstOrNull()
            ?: nodeFinder.findByViewId(r, "action_bar_title").firstOrNull()
        if (title != null && title.hasValidScreenBounds()) {
            logger.log(LogTag.CLICK, "tapProfileEntry", "${label}_TITLE")
            return service.scriptTapProfileEntryNode(title)
        }
        return false
    }

    companion object {
        private const val MAX_GOTO = 10_000
        private const val MAX_VISIT_RECOVERY = 5
        private val SKIP_ON_FAIL_ACTIONS = setOf("goto", "savevar", "incrementvar")
        private val VISIT_FAIL_SKIP_CONTACT_ACTIONS = setOf(
            "tapprofileentry",
            "ensurescreen",
            "sendvisitchatmessage"
        )
    }
}

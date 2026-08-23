package com.nswsys.nuviobridge

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Rect
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class RecommendationAccessibilityService : AccessibilityService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(2)
    private val requestGeneration = AtomicLong(0)
    private lateinit var resolver: MediaResolver
    private lateinit var matchCache: MatchCache
    private lateinit var windowManager: WindowManager
    private var pendingClick: Runnable? = null
    private var pendingLaunch: Runnable? = null
    private var pendingDetailScan: Runnable? = null
    private var pendingSponsoredProbe: Runnable? = null
    private var sponsoredWatchdog: Runnable? = null
    private val clickDetailProbes = ArrayList<Runnable>()
    private var overlayButton: Button? = null
    private var transitionOverlay: TextView? = null
    private var lastFingerprint = ""
    private var lastClickAt = 0L
    private var lastAcceptedClickUptime = 0L
    private var lastEntityOpenedAt = 0L
    private var lastFocusedCandidates: List<String> = emptyList()
    private var lastFocusedRawValues: List<String> = emptyList()
    private var lastFocusedAt = 0L
    private var lastMediaFocusCenterX = 0
    private var lastMediaFocusCenterY = 0
    private var lastSponsoredSkipAt = 0L
    private var lastVerticalKeyAt = 0L
    private var lastVerticalKeyCode = KeyEvent.KEYCODE_DPAD_DOWN
    private var loggedDpadCapture = false
    private var lastSponsoredWatchdogDiagnosticAt = 0L
    private var activeDetailFingerprint = ""
    private var dismissedDetailFingerprint = ""
    private var awaitingDetail = false
    private var autoOpenNextDetail = false
    private var detailRetryAttempt = 0
    private var pendingAmbiguousResolution: MediaResolution? = null
    private var resolvingDetailFingerprint = ""
    private var latestGoogleTvRoot: AccessibilityNodeInfo? = null
    private var latestGoogleTvRootAt = 0L
    private val launcherAppLabels: Set<String> by lazy { loadLauncherAppLabels() }

    override fun onCreate() {
        super.onCreate()
        resolver = MediaResolver(this)
        matchCache = MatchCache(this)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            // Focus events only remember a title; they never launch anything.
            // A remembered title is consumed exclusively when Google TV opens
            // EntityActivity without first emitting a click.
            eventTypes = AccessibilityEvent.TYPE_VIEW_CLICKED or
                AccessibilityEvent.TYPE_VIEW_FOCUSED or
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            notificationTimeout = 40
            packageNames = arrayOf(GOOGLE_TV_PACKAGE)
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        Log.d(
            TAG,
            "Bridge ${BuildConfig.VERSION_NAME} connected; " +
                "skipSponsored=${AppSettings.skipSponsoredSections(this)}; " +
                "keyFilter=true; watchdog=true"
        )
        scheduleSponsoredWatchdog(SPONSORED_WATCHDOG_INITIAL_DELAY_MS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.packageName?.toString() != GOOGLE_TV_PACKAGE) return
        if (AppSettings.skipSponsoredSections(this) &&
            event.eventType in GOOGLE_TV_TREE_EVENT_TYPES
        ) {
            rememberGoogleTvTree(event.source)
        }
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> handleRecommendationClick(event)
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED -> rememberFocusedRecommendation(event)
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (SystemClock.uptimeMillis() - lastVerticalKeyAt <= SPONSORED_KEY_WINDOW_MS) {
                    scheduleSponsoredRootProbe(SPONSORED_CONTENT_PROBE_DELAY_MS)
                }
            }
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> handleWindowStateChanged(event)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (AppSettings.skipSponsoredSections(this) &&
            isGoogleTvActiveWindow() &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0 &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            lastVerticalKeyAt = SystemClock.uptimeMillis()
            lastVerticalKeyCode = event.keyCode
            if (!loggedDpadCapture) {
                loggedDpadCapture = true
                Log.d(TAG, "DPAD monitoring active")
            }
            scheduleSponsoredRootProbe(SPONSORED_KEY_PROBE_DELAY_MS, replacePending = true)
        }
        // Never consume the key. Google TV performs its normal movement first;
        // the delayed probe only corrects focus if it landed inside an ad.
        return false
    }

    private fun handleWindowStateChanged(event: AccessibilityEvent) {
        val className = event.className?.toString().orEmpty()
        if (!className.endsWith(ENTITY_ACTIVITY)) return

        val now = SystemClock.uptimeMillis()
        val newDetail = now - lastEntityOpenedAt > ENTITY_DUPLICATE_WINDOW_MS
        lastEntityOpenedAt = now
        if (!newDetail) return
        if (now - lastAcceptedClickUptime <= DIRECT_CLICK_OWNS_WINDOW_MS) {
            clearFocusedRecommendation()
            Log.d(TAG, "Google TV detail opened after a handled click")
            return
        }
        if (lastFocusedCandidates.isEmpty() ||
            now - lastFocusedAt > FOCUSED_ENTITY_WINDOW_MS
        ) {
            if (lastFocusedCandidates.isNotEmpty()) clearFocusedRecommendation()
            Log.d(TAG, "Google TV detail opened without recent focused title")
            return
        }
        val candidates = lastFocusedCandidates
        val rawValues = lastFocusedRawValues
        clearFocusedRecommendation()
        Log.d(TAG, "Using focused title for Google TV detail: ${candidates.first()}")
        lastAcceptedClickUptime = now
        resolveAndOpen(candidates, rawValues)
    }

    private fun rememberFocusedRecommendation(event: AccessibilityEvent) {
        if (AppSettings.skipSponsoredSections(this) &&
            event.source?.let { skipSponsoredSectionIfNeeded(it) } == true
        ) {
            clearFocusedRecommendation()
            return
        }
        val directText = event.text.mapNotNull { it?.toString() }
        val directDescription = event.contentDescription?.toString()
        val directValues = buildList {
            addAll(directText)
            directDescription?.let(::add)
        }
        if (directValues.any(TitleNormalizer::isProviderPlaybackAction) ||
            TitleNormalizer.isLauncherControl(directValues)
        ) {
            clearFocusedRecommendation()
            return
        }
        val candidates = TitleNormalizer.candidates(directValues)
        if (candidates.isEmpty()) {
            if (directValues.any(String::isNotBlank)) clearFocusedRecommendation()
            return
        }
        if (!TitleNormalizer.looksLikeRecommendation(
                event.className?.toString().orEmpty(), directDescription, directText,
                emptyList(), launcherAppLabels
            )
        ) return
        val previousFingerprint = lastFocusedCandidates.firstOrNull()
            ?.let(TitleNormalizer::normalized)
        lastFocusedCandidates = candidates
        lastFocusedRawValues = directValues
        lastFocusedAt = SystemClock.uptimeMillis()
        event.source?.let { source ->
            val bounds = Rect()
            source.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                lastMediaFocusCenterX = bounds.centerX()
                lastMediaFocusCenterY = bounds.centerY()
            }
        }
        if (previousFingerprint != TitleNormalizer.normalized(candidates.first())) {
            Log.d(TAG, "Focused title stored: ${candidates.first()}")
        }
    }

    private fun scheduleSponsoredRootProbe(delayMs: Long, replacePending: Boolean = false) {
        if (replacePending) {
            pendingSponsoredProbe?.let(mainHandler::removeCallbacks)
            pendingSponsoredProbe = null
        } else if (pendingSponsoredProbe != null) {
            return
        }
        lateinit var probe: Runnable
        probe = Runnable {
            if (pendingSponsoredProbe === probe) pendingSponsoredProbe = null
            if (!AppSettings.skipSponsoredSections(this)) return@Runnable
            skipSponsoredSectionIfNeeded(null, lastVerticalKeyCode)
        }
        pendingSponsoredProbe = probe
        mainHandler.postDelayed(probe, delayMs)
    }

    private fun scheduleSponsoredWatchdog(delayMs: Long) {
        sponsoredWatchdog?.let(mainHandler::removeCallbacks)
        lateinit var watchdog: Runnable
        watchdog = Runnable {
            if (sponsoredWatchdog !== watchdog) return@Runnable
            sponsoredWatchdog = null
            val active = AppSettings.skipSponsoredSections(this) && isGoogleTvActiveWindow()
            val skipped = active && skipSponsoredSectionIfNeeded(
                source = null,
                direction = null,
                allowTreeFallback = true
            )
            scheduleSponsoredWatchdog(
                when {
                    skipped -> SPONSORED_WATCHDOG_AFTER_SKIP_MS
                    active -> SPONSORED_WATCHDOG_ACTIVE_MS
                    else -> SPONSORED_WATCHDOG_IDLE_MS
                }
            )
        }
        sponsoredWatchdog = watchdog
        mainHandler.postDelayed(watchdog, delayMs)
    }

    private fun skipSponsoredSectionIfNeeded(
        source: AccessibilityNodeInfo?,
        direction: Int? = null,
        allowTreeFallback: Boolean = true
    ): Boolean {
        val sponsoredContainer = source?.let(::findSponsoredContainer)
            ?: findFocusedSponsoredContainerInVisibleRoots(allowTreeFallback)
            ?: return false
        val now = SystemClock.uptimeMillis()
        val resolvedDirection = direction ?: inferSponsoredDirection(sponsoredContainer)

        val target = findRecommendationOutside(sponsoredContainer, resolvedDirection)
        sponsoredContainer.recycle()
        if (target == null) {
            Log.d(TAG, "Sponsored section detected, but no recommendation was exposed outside it")
            return true
        }

        val moved = runCatching {
            target.node.performAction(AccessibilityNodeInfo.ACTION_FOCUS) ||
                target.node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS)
        }.getOrDefault(false)
        if (moved) {
            lastSponsoredSkipAt = now
            Log.d(TAG, "Sponsored section skipped -> ${target.label}")
        } else {
            Log.d(TAG, "Sponsored section detected, but Google TV rejected the focus move")
        }
        target.node.recycle()
        return true
    }

    @Suppress("DEPRECATION")
    private fun findFocusedSponsoredContainerInVisibleRoots(
        allowTreeFallback: Boolean
    ): AccessibilityNodeInfo? {
        val roots = ArrayList<AccessibilityNodeInfo>()
        rootInActiveWindow?.let { root ->
            if (root.packageName?.toString() == GOOGLE_TV_PACKAGE) {
                roots += root
            } else {
                root.recycle()
            }
        }
        runCatching {
            windows.sortedByDescending { it.layer }
                .mapNotNull { it.root }
                .filter { it.packageName?.toString() == GOOGLE_TV_PACKAGE }
                .forEach(roots::add)
        }
        latestGoogleTvRoot?.let { cachedRoot ->
            val refreshedRoot = AccessibilityNodeInfo.obtain(cachedRoot)
            runCatching { refreshedRoot.refresh() }
            if (refreshedRoot.packageName?.toString() == GOOGLE_TV_PACKAGE) {
                roots += refreshedRoot
            } else {
                refreshedRoot.recycle()
            }
        }

        var focusedSummary: String? = null
        roots.forEachIndexed { index, root ->
            val focused = runCatching {
                root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            }.getOrNull() ?: if (allowTreeFallback) {
                findInputFocusedNode(root, 0, intArrayOf(MAX_SPONSORED_ROOT_NODES))
            } else {
                null
            }
            if (focused != null && focusedSummary == null) {
                focusedSummary = sponsoredFocusSummary(focused)
            }
            val container = focused?.let(::findSponsoredContainer)
            focused?.recycle()
            root.recycle()
            if (container != null) {
                for (remainingIndex in index + 1 until roots.size) roots[remainingIndex].recycle()
                return container
            }
        }
        logSponsoredWatchdogDiagnostic(roots.size, focusedSummary)
        return null
    }

    private fun sponsoredFocusSummary(focused: AccessibilityNodeInfo): String {
        val focusedLabels = directLabels(focused).filter(String::isNotBlank).take(4)
        val parent = runCatching { focused.parent }.getOrNull()
        val parentLabels = parent?.let(::directLabels)
            ?.filter(String::isNotBlank)
            ?.take(6)
            .orEmpty()
        parent?.recycle()
        return "node=${focused.className}, labels=$focusedLabels, parent=$parentLabels"
    }

    private fun logSponsoredWatchdogDiagnostic(rootCount: Int, focusedSummary: String?) {
        val now = SystemClock.uptimeMillis()
        if (now - lastSponsoredWatchdogDiagnosticAt < SPONSORED_DIAGNOSTIC_INTERVAL_MS) return
        lastSponsoredWatchdogDiagnosticAt = now
        Log.d(
            TAG,
            "Sponsored watchdog scan: roots=$rootCount, focused=${focusedSummary ?: "none"}"
        )
    }

    private fun inferSponsoredDirection(sponsoredContainer: AccessibilityNodeInfo): Int {
        if (SystemClock.uptimeMillis() - lastVerticalKeyAt <= SPONSORED_KEY_WINDOW_MS) {
            return lastVerticalKeyCode
        }
        val bounds = Rect()
        sponsoredContainer.getBoundsInScreen(bounds)
        return if (lastMediaFocusCenterY > 0 && lastMediaFocusCenterY > bounds.centerY()) {
            KeyEvent.KEYCODE_DPAD_UP
        } else {
            KeyEvent.KEYCODE_DPAD_DOWN
        }
    }

    @Suppress("DEPRECATION")
    private fun isGoogleTvActiveWindow(): Boolean {
        rootInActiveWindow?.let { root ->
            val isGoogleTv = root.packageName?.toString() == GOOGLE_TV_PACKAGE
            root.recycle()
            return isGoogleTv
        }
        val visibleWindowFound = runCatching {
            windows.any { window ->
                if (!window.isActive) return@any false
                val root = window.root ?: return@any false
                val isGoogleTv = root.packageName?.toString() == GOOGLE_TV_PACKAGE
                root.recycle()
                isGoogleTv
            }
        }.getOrDefault(false)
        if (visibleWindowFound) return true

        latestGoogleTvRoot?.let { cachedRoot ->
            val refreshedRoot = AccessibilityNodeInfo.obtain(cachedRoot)
            val refreshed = runCatching { refreshedRoot.refresh() }.getOrDefault(false)
            val isGoogleTv = refreshed &&
                refreshedRoot.packageName?.toString() == GOOGLE_TV_PACKAGE
            refreshedRoot.recycle()
            if (isGoogleTv) return true
        }
        return latestGoogleTvRoot != null &&
            SystemClock.uptimeMillis() - latestGoogleTvRootAt <= GOOGLE_TV_ROOT_GRACE_MS
    }

    @Suppress("DEPRECATION")
    private fun findInputFocusedNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        remaining: IntArray
    ): AccessibilityNodeInfo? {
        if (depth > MAX_SPONSORED_ROOT_DEPTH || remaining[0] <= 0) return null
        remaining[0]--
        if (node.isFocused) return AccessibilityNodeInfo.obtain(node)
        for (index in 0 until node.childCount) {
            if (remaining[0] <= 0) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            val focused = findInputFocusedNode(child, depth + 1, remaining)
            child.recycle()
            if (focused != null) return focused
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun findSponsoredContainer(source: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
        repeat(MAX_SPONSORED_ANCESTOR_LEVELS) {
            val node = current ?: return null
            if (SponsoredSectionDetector.isSponsoredContainer(directLabels(node))) return node
            val parent = runCatching { node.parent }.getOrNull()
            node.recycle()
            current = parent
        }
        current?.recycle()
        return null
    }

    private fun directLabels(node: AccessibilityNodeInfo): List<String> = buildList {
        node.text?.toString()?.let(::add)
        node.contentDescription?.toString()?.let(::add)
        for (index in 0 until node.childCount) {
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            child.text?.toString()?.let(::add)
            child.contentDescription?.toString()?.let(::add)
            child.recycle()
        }
    }

    private fun findRecommendationOutside(
        sponsoredContainer: AccessibilityNodeInfo,
        direction: Int
    ): FocusTarget? {
        val parent = runCatching { sponsoredContainer.parent }.getOrNull() ?: return null
        var sponsoredIndex = -1
        for (index in 0 until parent.childCount) {
            val child = runCatching { parent.getChild(index) }.getOrNull() ?: continue
            if (sameNode(child, sponsoredContainer)) sponsoredIndex = index
            child.recycle()
            if (sponsoredIndex >= 0) break
        }
        if (sponsoredIndex < 0) {
            parent.recycle()
            return null
        }

        val targets = ArrayList<FocusTarget>()
        val remaining = intArrayOf(MAX_SPONSORED_TARGET_NODES)
        val siblingIndexes = if (direction == KeyEvent.KEYCODE_DPAD_UP) {
            (sponsoredIndex - 1 downTo 0)
        } else {
            (sponsoredIndex + 1 until parent.childCount)
        }
        for (index in siblingIndexes) {
            val sibling = runCatching { parent.getChild(index) }.getOrNull() ?: continue
            collectRecommendationTargets(sibling, targets, 0, remaining)
            if (targets.isEmpty()) {
                collectFocusableNavigationTargets(
                    sibling,
                    targets,
                    0,
                    intArrayOf(MAX_SPONSORED_TARGET_NODES)
                )
            }
            sibling.recycle()
            if (targets.isNotEmpty() || remaining[0] <= 0) break
        }
        parent.recycle()
        if (targets.isEmpty()) return null

        val screenWidth = resources.displayMetrics.widthPixels
        val chosen = targets.minWithOrNull(
            compareBy<FocusTarget> { target ->
                val fullyVisible = target.bounds.left >= screenWidth / 25 &&
                    target.bounds.right <= screenWidth - screenWidth / 25 &&
                    target.bounds.width() >= MIN_SPONSORED_TARGET_WIDTH_PX
                if (fullyVisible) 0 else 1
            }.thenBy { target ->
                if (lastMediaFocusCenterX > 0) {
                    abs(target.bounds.centerX() - lastMediaFocusCenterX)
                } else {
                    target.bounds.left
                }
            }
        )
        targets.forEach { target -> if (target !== chosen) target.node.recycle() }
        return chosen
    }

    @Suppress("DEPRECATION")
    private fun collectFocusableNavigationTargets(
        node: AccessibilityNodeInfo,
        output: MutableList<FocusTarget>,
        depth: Int,
        remaining: IntArray
    ) {
        if (depth > MAX_SPONSORED_TARGET_DEPTH || remaining[0] <= 0) return
        remaining[0]--
        if (node.isEnabled && node.isFocusable && node.isClickable && node.isVisibleToUser) {
            val bounds = Rect()
            node.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                val label = buildList {
                    node.contentDescription?.toString()?.let(::add)
                    node.text?.toString()?.let(::add)
                    addAll(directLabels(node))
                }.firstOrNull(String::isNotBlank)
                    ?: node.className?.toString().orEmpty()
                output += FocusTarget(AccessibilityNodeInfo.obtain(node), bounds, label)
            }
        }
        for (index in 0 until node.childCount) {
            if (remaining[0] <= 0) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            collectFocusableNavigationTargets(child, output, depth + 1, remaining)
            child.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun collectRecommendationTargets(
        node: AccessibilityNodeInfo,
        output: MutableList<FocusTarget>,
        depth: Int,
        remaining: IntArray
    ) {
        if (depth > MAX_SPONSORED_TARGET_DEPTH || remaining[0] <= 0) return
        remaining[0]--
        val text = node.text?.toString()?.let(::listOf).orEmpty()
        val description = node.contentDescription?.toString()
        val values = buildList {
            addAll(text)
            description?.let(::add)
        }
        if (node.isEnabled && node.isFocusable && node.isVisibleToUser &&
            TitleNormalizer.looksLikeRecommendation(
                node.className?.toString().orEmpty(), description, text,
                emptyList(), launcherAppLabels
            )
        ) {
            val label = TitleNormalizer.candidates(values).firstOrNull()
            if (label != null) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    output += FocusTarget(AccessibilityNodeInfo.obtain(node), bounds, label)
                }
            }
        }
        for (index in 0 until node.childCount) {
            if (remaining[0] <= 0) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            collectRecommendationTargets(child, output, depth + 1, remaining)
            child.recycle()
        }
    }

    private fun sameNode(left: AccessibilityNodeInfo, right: AccessibilityNodeInfo): Boolean {
        if (left == right) return true
        if (left.windowId != right.windowId || left.className != right.className) return false
        val leftBounds = Rect()
        val rightBounds = Rect()
        left.getBoundsInScreen(leftBounds)
        right.getBoundsInScreen(rightBounds)
        return leftBounds == rightBounds
    }

    private fun handleRecommendationClick(event: AccessibilityEvent) {
        val directText = event.text.mapNotNull { it?.toString() }
        val directDescription = event.contentDescription?.toString()
        Log.d(
            TAG,
            "Click received: class=${event.className}, text=${directText.take(2)}, " +
                "description=${directDescription.orEmpty()}"
        )
        val directValues = buildList {
            addAll(directText)
            directDescription?.let(::add)
        }
        if (directValues.any(TitleNormalizer::isProviderPlaybackAction)) {
            cancelPendingBridge("provider playback action")
            return
        }
        val clickHasOwnLabel = directText.any { it.isNotBlank() } ||
            !directDescription.isNullOrBlank()
        if (TitleNormalizer.isLauncherControl(directValues)) {
            cancelPendingBridge("launcher control")
            Log.d(TAG, "Launcher control ignored: ${directValues.take(2)}")
            return
        }
        val eventValues = rawValues(event, directText, directDescription)
        val eventCandidates = TitleNormalizer.candidates(directValues)
        // Validate only the clicked control itself. Ancestor/child text may
        // contain a movie title even when the user clicked Settings or Search.
        val eventLooksLikeRecommendation = clickHasOwnLabel &&
            TitleNormalizer.looksLikeRecommendation(
                event.className?.toString().orEmpty(), directDescription, directText,
                emptyList(), launcherAppLabels
            )
        if (!eventLooksLikeRecommendation || eventCandidates.isEmpty()) {
            cancelPendingBridge("non-media control")
            Log.d(
                TAG,
                "Click ignored: not a media recommendation, class=${event.className}, " +
                    "text=${directText.take(2)}, description=${directDescription.orEmpty()}"
            )
            return
        }
        clearFocusedRecommendation()
        lastAcceptedClickUptime = SystemClock.uptimeMillis()
        resolveAndOpen(eventCandidates, eventValues)
    }

    private fun resolveAndOpen(candidates: List<String>, rawValues: List<String>) {
        val fingerprint = candidates.joinToString("|") { TitleNormalizer.normalized(it) }
        val now = System.currentTimeMillis()
        if (fingerprint == lastFingerprint && now - lastClickAt < DUPLICATE_WINDOW_MS) {
            Log.d(TAG, "Duplicate title ignored: ${candidates.first()}")
            return
        }
        lastFingerprint = fingerprint
        lastClickAt = now
        dismissedDetailFingerprint = ""
        autoOpenNextDetail = true

        hideOverlay()
        val generation = requestGeneration.incrementAndGet()
        pendingClick?.let(mainHandler::removeCallbacks)
        pendingClick = null
        knownRecommendationMatch(candidates, rawValues)?.let { known ->
            stopDetailInspection()
            matchCache.put(candidates.first(), known)
            Log.d(TAG, "Opening verified ${candidates.first()} -> ${known.type}/${known.tmdbId}")
            openResolutionAfterSettle(
                MediaResolution(candidates.first(), listOf(known)),
                generation
            )
            return
        }
        candidates.firstNotNullOfOrNull(matchCache::get)?.let { cached ->
            stopDetailInspection()
            Log.d(TAG, "Opening cached ${candidates.first()} -> ${cached.type}/${cached.tmdbId}")
            openResolutionAfterSettle(
                MediaResolution(candidates.first(), listOf(cached)),
                generation
            )
            return
        }
        pendingClick = Runnable {
            executor.execute {
                val resolution = resolver.resolveOptions(
                    candidates = candidates,
                    isCurrent = { requestGeneration.get() == generation }
                )
                if (resolution == null || requestGeneration.get() != generation) {
                    if (requestGeneration.get() == generation) {
                        Log.d(TAG, "No TMDB match for ${candidates.take(3)}")
                        mainHandler.post {
                            if (requestGeneration.get() == generation) hideTransitionOverlay()
                        }
                    }
                    return@execute
                }
                Log.d(TAG, "Resolved ${candidates.first()} -> ${resolution.preferred.type}/${resolution.preferred.tmdbId}")
                mainHandler.post {
                    if (requestGeneration.get() != generation) {
                        Log.d(TAG, "Resolved result discarded after a newer click: ${candidates.first()}")
                        return@post
                    }
                    stopDetailInspection()
                    openResolutionAfterSettle(resolution, generation)
                }
            }
        }.also { mainHandler.postDelayed(it, CLICK_DEBOUNCE_MS) }
    }

    private fun cancelPendingBridge(reason: String) {
        requestGeneration.incrementAndGet()
        pendingClick?.let(mainHandler::removeCallbacks)
        pendingClick = null
        pendingLaunch?.let(mainHandler::removeCallbacks)
        pendingLaunch = null
        stopDetailInspection()
        pendingAmbiguousResolution = null
        hideTransitionOverlay()
        clearFocusedRecommendation()
        Log.d(TAG, "Pending bridge cancelled: $reason")
    }

    private fun clearFocusedRecommendation() {
        lastFocusedCandidates = emptyList()
        lastFocusedRawValues = emptyList()
        lastFocusedAt = 0L
    }

    private fun stopDetailInspection() {
        awaitingDetail = false
        autoOpenNextDetail = false
        pendingDetailScan?.let(mainHandler::removeCallbacks)
        pendingDetailScan = null
        clickDetailProbes.forEach(mainHandler::removeCallbacks)
        clickDetailProbes.clear()
    }

    private fun rawValues(
        event: AccessibilityEvent,
        directText: List<String>,
        directDescription: String?
    ): List<String> = buildList {
        directText.firstOrNull()?.let { add(it) }
        directDescription?.let { add(it) }
        directText.drop(1).forEach { add(it) }
        collectNodeStrings(event.source, this, 0, intArrayOf(24))
        collectAncestorStrings(event.source?.parent, this, intArrayOf(12))
    }

    private fun knownRecommendationMatch(
        candidates: List<String>,
        rawValues: List<String>
    ): MediaMatch? = VerifiedRecommendationMatches.find(candidates, rawValues)

    private fun beginWaitingForDetail() {
        awaitingDetail = true
        autoOpenNextDetail = true
        detailRetryAttempt = 0
        pendingAmbiguousResolution = null
        scheduleDetailScan(INITIAL_DETAIL_DELAY_MS)
    }

    private fun scheduleClickDetailProbes() {
        DETAIL_PROBE_DELAYS_MS.forEach { delay ->
            lateinit var probe: Runnable
            probe = Runnable {
                clickDetailProbes.remove(probe)
                scanDetailPage()
            }
            clickDetailProbes += probe
            mainHandler.postDelayed(probe, delay)
        }
    }

    private fun scheduleDetailScan(delayMs: Long = DETAIL_SCAN_DEBOUNCE_MS) {
        // Google TV emits a continuous stream of content/focus events while
        // animating into a detail page. Replacing the runnable for every event
        // turns this into a trailing debounce and can postpone the scan until
        // after the ambiguity chooser is already visible. Keep the first scan
        // scheduled and let later events schedule the next one.
        if (pendingDetailScan != null) return
        lateinit var scan: Runnable
        scan = Runnable {
            if (pendingDetailScan === scan) pendingDetailScan = null
            scanDetailPage()
        }
        pendingDetailScan = scan
        mainHandler.postDelayed(scan, delayMs)
    }

    private fun scanDetailPage() {
        // rootInActiveWindow is consistently null on some Chromecast Google
        // TV builds during launcher transitions. Try every interactive window
        // and the most recent event-tree snapshot before giving up.
        val roots = buildList {
            rootInActiveWindow?.let(::add)
            runCatching {
                windows.sortedByDescending { it.layer }
                    .mapNotNull { it.root }
                    .filter { it.packageName?.toString() == GOOGLE_TV_PACKAGE }
                    .forEach(::add)
            }
            latestGoogleTvRoot?.let(::add)
        }.distinctBy { node ->
            "${node.windowId}|${node.packageName}|${node.className}|${node.hashCode()}"
        }
        val detail = roots.firstNotNullOfOrNull { root ->
            runCatching { GoogleTvDetailDetector.detect(root) }
                .onFailure { Log.d(TAG, "Unable to inspect detail root: ${it.message}") }
                .getOrNull()
        }
        if (detail == null) {
            val maxRetries = if (pendingAmbiguousResolution != null) {
                MAX_AMBIGUITY_RETRIES
            } else {
                MAX_DETAIL_RETRIES
            }
            if (awaitingDetail && detailRetryAttempt < maxRetries) {
                detailRetryAttempt++
                if (detailRetryAttempt == 1 || detailRetryAttempt == maxRetries) {
                    Log.d(
                        TAG,
                        "Detail scan pending ($detailRetryAttempt/$maxRetries), " +
                            "roots=${roots.size}, " +
                            "last=${latestGoogleTvRoot?.packageName}/${latestGoogleTvRoot?.className}"
                    )
                }
                scheduleDetailScan(DETAIL_RETRY_MS)
                return
            }
            if (awaitingDetail) {
                Log.d(TAG, "Detail metadata unavailable; using initial TMDB result")
                awaitingDetail = false
                pendingAmbiguousResolution?.let { resolution ->
                    openResolutionAfterSettle(resolution, requestGeneration.get())
                }
                pendingAmbiguousResolution = null
            }
            if (activeDetailFingerprint.isNotEmpty()) {
                activeDetailFingerprint = ""
                resolvingDetailFingerprint = ""
                dismissedDetailFingerprint = ""
                requestGeneration.incrementAndGet()
                hideOverlay()
            }
            return
        }
        val shouldAutoOpenFromDetail = awaitingDetail || autoOpenNextDetail
        awaitingDetail = false
        autoOpenNextDetail = false
        detailRetryAttempt = 0
        pendingAmbiguousResolution = null
        Log.d(
            TAG,
            "Detail detected: ${detail.candidates.first()}, year=${detail.year}, action=${detail.primaryActionBounds}"
        )
        val fingerprint = "${TitleNormalizer.normalized(detail.candidates.first())}|${detail.year ?: 0}|${detail.typeHint?.name.orEmpty()}"
        if (fingerprint == dismissedDetailFingerprint ||
            fingerprint == resolvingDetailFingerprint ||
            (fingerprint == activeDetailFingerprint && overlayButton != null)
        ) return

        activeDetailFingerprint = fingerprint
        resolvingDetailFingerprint = fingerprint
        hideOverlay()
        val candidates = buildList {
            detail.year?.let { year -> detail.candidates.forEach { add("$it $year") } }
            addAll(detail.candidates)
        }.distinct()
        val generation = requestGeneration.incrementAndGet()
        executor.execute {
            val resolution = resolver.resolveOptions(
                candidates, { requestGeneration.get() == generation }, detail.typeHint
            )
            if (resolution == null || requestGeneration.get() != generation) {
                mainHandler.post {
                    if (resolvingDetailFingerprint == fingerprint) resolvingDetailFingerprint = ""
                }
                return@execute
            }
            mainHandler.post {
                if (resolvingDetailFingerprint == fingerprint) resolvingDetailFingerprint = ""
                if (activeDetailFingerprint == fingerprint && dismissedDetailFingerprint != fingerprint) {
                    if (requestGeneration.get() != generation) return@post
                    if (shouldAutoOpenFromDetail) {
                        Log.d(
                            TAG,
                            "Opening detail match ${detail.candidates.first()} (${detail.year}) -> " +
                                "${resolution.preferred.type}/${resolution.preferred.tmdbId}"
                        )
                        hideOverlay()
                        openResolutionAfterSettle(resolution, generation)
                    } else {
                        showOverlay(detail, resolution, fingerprint)
                    }
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun rememberGoogleTvTree(source: AccessibilityNodeInfo?) {
        if (source == null) return
        val fullWindowRoot = runCatching { source.window?.root }.getOrNull()
        var snapshot = if (fullWindowRoot != null) {
            fullWindowRoot
        } else {
            AccessibilityNodeInfo.obtain(source)
        }

        // If the platform did not expose AccessibilityWindowInfo.root, climb
        // from the event source. Content-change events frequently originate in
        // a nested grid but their ancestors still expose the complete detail.
        if (fullWindowRoot == null) {
            for (level in 0 until 30) {
                val parent = runCatching { snapshot.parent }.getOrNull() ?: break
                snapshot.recycle()
                snapshot = parent
            }
        }
        if (snapshot.packageName?.toString() != GOOGLE_TV_PACKAGE) {
            snapshot.recycle()
            return
        }
        latestGoogleTvRoot?.recycle()
        latestGoogleTvRoot = snapshot
        latestGoogleTvRootAt = SystemClock.uptimeMillis()
    }

    private fun showOverlay(
        detail: GoogleTvDetail,
        resolution: MediaResolution,
        fingerprint: String
    ) {
        hideOverlay()
        val spanish = Locale.getDefault().language.equals("es", true)
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val nativeAction = detail.primaryActionBounds
        val button = Button(this).apply {
            text = when {
                resolution.isAmbiguous && spanish -> "Elegir en Nuvio"
                resolution.isAmbiguous -> "Choose in Nuvio"
                spanish -> "Abrir en Nuvio"
                else -> "Open in Nuvio"
            }
            contentDescription = "$text, ${detail.candidates.first()}${detail.year?.let { ", $it" }.orEmpty()}"
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            backgroundTintList = ColorStateList.valueOf(Color.rgb(92, 56, 216))
            minWidth = nativeAction?.width()?.plus(70) ?: dp(230)
            minHeight = nativeAction?.height() ?: dp(64)
            setPadding(nativeAction?.height()?.div(4) ?: dp(24), 0, nativeAction?.height()?.div(4) ?: dp(24), 0)
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { hideOverlay(); openResolution(resolution) }
            setOnKeyListener { _, keyCode, keyEvent ->
                if (keyEvent.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_BACK -> {
                        dismissedDetailFingerprint = fingerprint
                        hideOverlay()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                    KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                        dismissedDetailFingerprint = fingerprint
                        hideOverlay()
                        false
                    }
                    else -> false
                }
            }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            // Use Google TV's real action coordinates when available.
            x = nativeAction?.right?.plus(nativeAction.height() / 3) ?: dp(333)
            y = nativeAction?.top ?: (resources.displayMetrics.heightPixels - dp(245))
        }
        try {
            windowManager.addView(button, params)
            overlayButton = button
            Log.d(TAG, "Nuvio detail button shown at x=${params.x}, y=${params.y}")
            mainHandler.postDelayed({ if (overlayButton === button) button.requestFocus() }, 120L)
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to show Nuvio accessibility overlay", exception)
        }
    }

    private fun openResolution(resolution: MediaResolution) {
        if (resolution.isAmbiguous) {
            hideTransitionOverlay()
            startActivity(MatchChooserActivity.intent(this, resolution))
        } else {
            matchCache.put(resolution.query, resolution.preferred)
            launchNuvio(resolution.preferred)
        }
    }

    private fun openResolutionAfterSettle(resolution: MediaResolution, generation: Long) {
        if (requestGeneration.get() != generation) return
        pendingLaunch?.let(mainHandler::removeCallbacks)
        showTransitionOverlay()

        val now = SystemClock.uptimeMillis()
        val transitionStartedAt = maxOf(lastEntityOpenedAt, lastAcceptedClickUptime)
        val delay = (transitionStartedAt + GOOGLE_TV_SETTLE_MS - now)
            .coerceAtLeast(MINIMUM_LAUNCH_DELAY_MS)
        lateinit var launch: Runnable
        launch = Runnable {
            if (pendingLaunch === launch) pendingLaunch = null
            if (requestGeneration.get() != generation) {
                hideTransitionOverlay()
                Log.d(TAG, "Pending launch discarded after a newer action: ${resolution.query}")
                return@Runnable
            }
            openResolution(resolution)
        }
        pendingLaunch = launch
        mainHandler.postDelayed(launch, delay)
        Log.d(TAG, "Launch scheduled in ${delay}ms for ${resolution.query}")
    }

    private fun showTransitionOverlay() {
        if (transitionOverlay != null) return
        val transition = TextView(this).apply {
            text = "NUVIO"
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(190, 160, 255))
            textSize = 28f
            letterSpacing = 0.18f
            setBackgroundColor(Color.rgb(11, 11, 16))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.FILL }
        try {
            windowManager.addView(transition, params)
            transitionOverlay = transition
        } catch (exception: Exception) {
            Log.w(TAG, "Unable to show Nuvio transition", exception)
        }
    }

    private fun launchNuvio(match: MediaMatch) {
        showTransitionOverlay()
        NuvioLauncher.open(this, match)
        mainHandler.postDelayed(::hideTransitionOverlay, TRANSITION_HOLD_MS)
    }

    private fun hideTransitionOverlay() {
        transitionOverlay?.let { view -> runCatching { windowManager.removeView(view) } }
        transitionOverlay = null
    }

    private fun hideOverlay() {
        overlayButton?.let { button -> runCatching { windowManager.removeView(button) } }
        overlayButton = null
    }

    private fun collectAncestorStrings(start: AccessibilityNodeInfo?, output: MutableList<String>, remaining: IntArray) {
        var node = start
        var levels = 0
        while (node != null && levels < 4 && remaining[0] > 0) {
            remaining[0]--
            node.text?.toString()?.let(output::add)
            node.contentDescription?.toString()?.let(output::add)
            node = node.parent
            levels++
        }
    }

    @Suppress("DEPRECATION")
    private fun loadLauncherAppLabels(): Set<String> {
        val labels = LinkedHashSet<String>()
        listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER).forEach { category ->
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { info -> info.loadLabel(packageManager)?.toString()?.let { labels += TitleNormalizer.normalized(it) } }
        }
        return labels
    }

    private fun collectNodeStrings(node: AccessibilityNodeInfo?, output: MutableList<String>, depth: Int, remaining: IntArray) {
        if (node == null || depth > 3 || remaining[0] <= 0) return
        remaining[0]--
        node.text?.toString()?.let(output::add)
        node.contentDescription?.toString()?.let(output::add)
        for (index in 0 until node.childCount) collectNodeStrings(node.getChild(index), output, depth + 1, remaining)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        requestGeneration.incrementAndGet()
        pendingClick?.let(mainHandler::removeCallbacks)
        pendingLaunch?.let(mainHandler::removeCallbacks)
        pendingDetailScan?.let(mainHandler::removeCallbacks)
        pendingSponsoredProbe?.let(mainHandler::removeCallbacks)
        pendingSponsoredProbe = null
        sponsoredWatchdog?.let(mainHandler::removeCallbacks)
        sponsoredWatchdog = null
        clickDetailProbes.forEach(mainHandler::removeCallbacks)
        clickDetailProbes.clear()
        pendingAmbiguousResolution = null
        latestGoogleTvRoot?.recycle()
        latestGoogleTvRoot = null
        latestGoogleTvRootAt = 0L
        hideOverlay()
        hideTransitionOverlay()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private data class FocusTarget(
            val node: AccessibilityNodeInfo,
            val bounds: Rect,
            val label: String
        )

        private const val TAG = "NuvioBridgeService"
        private const val GOOGLE_TV_PACKAGE = "com.google.android.apps.tv.launcherx"
        private const val ENTITY_ACTIVITY = ".entity.EntityActivity"
        private const val CLICK_DEBOUNCE_MS = 60L
        private const val DETAIL_SCAN_DEBOUNCE_MS = 220L
        private const val INITIAL_DETAIL_DELAY_MS = 260L
        private const val DETAIL_RETRY_MS = 240L
        private const val MAX_AMBIGUITY_RETRIES = 4
        private const val MAX_DETAIL_RETRIES = 10
        private val DETAIL_PROBE_DELAYS_MS = longArrayOf(300L, 650L, 1_000L, 1_500L)
        private const val DUPLICATE_WINDOW_MS = 15_000L
        private const val TRANSITION_HOLD_MS = 650L
        private const val GOOGLE_TV_SETTLE_MS = 700L
        private const val MINIMUM_LAUNCH_DELAY_MS = 80L
        private const val ENTITY_DUPLICATE_WINDOW_MS = 1_200L
        private const val DIRECT_CLICK_OWNS_WINDOW_MS = 1_500L
        private const val FOCUSED_ENTITY_WINDOW_MS = 2_500L
        private const val MAX_SPONSORED_ANCESTOR_LEVELS = 6
        private const val MAX_SPONSORED_TARGET_DEPTH = 5
        private const val MAX_SPONSORED_TARGET_NODES = 48
        private const val MAX_SPONSORED_ROOT_DEPTH = 10
        private const val MAX_SPONSORED_ROOT_NODES = 180
        private const val MIN_SPONSORED_TARGET_WIDTH_PX = 140
        private const val SPONSORED_KEY_PROBE_DELAY_MS = 180L
        private const val SPONSORED_CONTENT_PROBE_DELAY_MS = 80L
        private const val SPONSORED_KEY_WINDOW_MS = 900L
        private const val SPONSORED_WATCHDOG_INITIAL_DELAY_MS = 500L
        private const val SPONSORED_WATCHDOG_ACTIVE_MS = 350L
        private const val SPONSORED_WATCHDOG_AFTER_SKIP_MS = 650L
        private const val SPONSORED_WATCHDOG_IDLE_MS = 1_200L
        private const val SPONSORED_DIAGNOSTIC_INTERVAL_MS = 8_000L
        private const val GOOGLE_TV_ROOT_GRACE_MS = 3_000L
        private val GOOGLE_TV_TREE_EVENT_TYPES = setOf(
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        )
    }
}

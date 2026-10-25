package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.glasses.session.SessionEffect.SendActivityCommand
import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivityCommandMode
import com.anezium.rokidbus.shared.ActivityCommandResult
import com.anezium.rokidbus.shared.ActivityCommandStatus
import com.anezium.rokidbus.shared.MediaPlaybackState
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.QuickActionPolicy
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 028a §1, §3 and §5 on the glasses: cards, command focus, quick actions and fresh contact. */
class LauncherInteractiveTest {
    private var negotiated = true
    private var policy = QuickActionPolicy(
        1,
        mapOf("media" to setOf(QuickActionFamily.MEDIA), "todo" to setOf(QuickActionFamily.CHECKLIST)),
    )
    private val gate = LauncherQuickGate({ if (negotiated) 1 else 0 }) { policy }
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        quickGate = gate,
        extended = { negotiated },
    )
    private val reducer = SessionReducer(
        items,
        isLocalPage = PageTemplateContract::isLocalPageId,
        quickGate = gate,
        launcherActive = { negotiated },
    )
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    private val mediaCommands = listOf(
        ActivityAction("previous", "previous", "Previous", PageActionType.MediaPrevious, hubQuick = true),
        ActivityAction("play", "play", "Play", PageActionType.MediaPlay, hubQuick = true),
        ActivityAction("next", "next", "Next", PageActionType.MediaNext, hubQuick = true),
    )

    @Test
    fun `a commands-first tap enters focus on the default command and never runs it`() {
        openRoot(stop())
        shown(1_900)
        press(2_000)

        assertEquals(CommandFocus("media:activity", "play", 1), root().focus)
        assertTrue(effects.none { it is SendActivityCommand })
        assertTrue(model.pendingCommands.isEmpty())
    }

    @Test
    fun `focus steps along the commands then Open app without wrapping and back leaves focus first`() {
        focusOnPlay()

        send(SessionEvent.Step(1, 3_000))
        assertEquals("next", root().focus?.key)
        send(SessionEvent.Step(1, 3_100))
        assertEquals(CommandFocus("media:activity", ActivityCommands.OPEN_APP_KEY, 3), root().focus)
        send(SessionEvent.Step(1, 3_200))
        assertEquals(3, root().focus?.index)
        assertEquals(listOf(SessionEffect.None), effects)
        repeat(5) { send(SessionEvent.Step(-1, 3_300L + it)) }
        assertEquals(CommandFocus("media:activity", "previous", 0), root().focus)

        send(SessionEvent.Back(3_400))
        assertTrue(model.state is SessionState.Root)
        assertNull(root().focus)
        send(SessionEvent.Back(3_500))
        assertEquals(SessionState.Closed, model.state)
    }

    @Test
    fun `Open app launches directly from a card slot on one fresh press`() {
        focusOnPlay()
        send(SessionEvent.Step(1, 3_000))
        send(SessionEvent.Step(1, 3_100))
        shown(3_200)
        press(3_100)
        assertTrue(model.state is SessionState.Root)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })

        press(3_300)
        assertTrue(model.state is SessionState.Launching)
        assertEquals("media", effects.filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)
    }

    @Test
    fun `a quick command needs a press begun after its focus was drawn`() {
        focusOnPlay(ackAt = null)
        shown(3_000)

        press(2_990)
        assertTrue(effects.none { it is SendActivityCommand })
        assertTrue(model.pendingCommands.isEmpty())

        press(3_010)
        val command = effects.filterIsInstance<SendActivityCommand>().single().command
        assertEquals("media:activity", command.activityId)
        assertEquals("play", command.actionId)
        assertEquals(7L, command.seq)
        assertEquals(ActivityCommandMode.QUICK, command.mode)
        assertEquals(command.invocationId, model.pendingCommands.getValue("media").invocationId)
    }

    @Test
    fun `a ring tap that began before the focus was drawn has no effect even when it resolves later`() {
        focusOnPlay(ackAt = null)
        shown(3_000)

        // The ring's single tap resolves after its double-tap window: what counts is its down time.
        send(SessionEvent.Enter(3_400, pressStartMs = 2_950))
        assertTrue(effects.none { it is SendActivityCommand })

        send(SessionEvent.Enter(3_700, pressStartMs = 3_350))
        assertEquals(ActivityCommandMode.QUICK, effects.filterIsInstance<SendActivityCommand>().single().command.mode)
    }

    @Test
    fun `coming back to a target that was drawn before needs a new acknowledgement`() {
        focusOnPlay()
        send(SessionEvent.Step(1, 2_600))
        send(SessionEvent.Step(-1, 2_700))
        assertEquals("play", root().focus?.key)

        press(2_800)
        assertTrue(effects.none { it is SendActivityCommand })
        shown(2_900)
        press(3_000)
        assertEquals(1, effects.filterIsInstance<SendActivityCommand>().size)
    }

    @Test
    fun `a new revision of the card is a new target that needs a new fresh press`() {
        focusOnPlay()
        send(SessionEvent.ActivityUpdated(stop(seq = 8)))
        assertEquals("play", root().focus?.key)

        press(3_000)
        assertTrue(effects.none { it is SendActivityCommand })
        shown(3_100)
        press(3_200)
        assertEquals(8L, effects.filterIsInstance<SendActivityCommand>().single().command.seq)
    }

    @Test
    fun `without the policy a single fresh tap sends the focused command in confirmed mode`() {
        policy = QuickActionPolicy(2, emptyMap())
        focusOnPlay()
        press(2_000)
        assertTrue(effects.none { it is SendActivityCommand })
        press(3_000)
        val command = effects.filterIsInstance<SendActivityCommand>().single().command
        assertEquals("play", command.actionId)
        assertEquals(7L, command.seq)
        assertEquals(ActivityCommandMode.CONFIRMED, command.mode)
        assertEquals("play", root().focus?.key)
        val screen = SessionScreens(items, { null }, { "12:00" }, { it }).screen(model.state, null, model.generation)
        assertTrue(screen.blocks.any { it is ScreenBlock.Card })
        press(3_100)
        assertTrue(effects.none { it is SendActivityCommand })
    }

    @Test
    fun `an action the phone did not stamp quick is confirmed even when the policy approves`() {
        val unstamped = mediaCommands.map { it.copy(hubQuick = false) }
        openRoot(stop(commands = unstamped))
        shown(1_900)
        press(2_000)
        shown(2_100)
        press(2_200)
        assertEquals(ActivityCommandMode.CONFIRMED, effects.filterIsInstance<SendActivityCommand>().single().command.mode)
        assertEquals("play", root().focus?.key)
    }

    @Test
    fun `a confirmed command waits for the new card revision to be drawn and a fresh press`() {
        policy = QuickActionPolicy(2, emptyMap())
        focusOnPlay()
        send(SessionEvent.ActivityUpdated(stop(seq = 9)))
        press(3_100)
        assertTrue(effects.none { it is SendActivityCommand })
        shown(3_200)
        send(SessionEvent.Enter(3_250, pressStartMs = 3_150))
        assertTrue(effects.none { it is SendActivityCommand })
        press(3_300)
        val command = effects.filterIsInstance<SendActivityCommand>().single().command
        assertEquals(9L, command.seq)
        assertEquals(ActivityCommandMode.CONFIRMED, command.mode)
    }

    @Test
    fun `one command per owner at a time and a lost result is unconfirmed and never replayed`() {
        focusOnPlay()
        press(3_000)
        val sent = effects.filterIsInstance<SendActivityCommand>().single().command
        val since = model.pendingCommands.getValue("media").sinceMs

        send(SessionEvent.Step(1, 3_100))
        shown(3_200)
        press(3_300)
        assertTrue(effects.none { it is SendActivityCommand })

        send(SessionEvent.Tick(since + ActivityCommands.RESULT_DEADLINE_MS))
        assertTrue(model.pendingCommands.isEmpty())
        assertEquals(PageSurfaceContract.ERROR_UNCONFIRMED_ACTION, model.commandOutcomes.getValue("media").status)
        assertTrue(effects.contains(SessionEffect.ShowRoot))
        assertTrue(effects.none { it is SendActivityCommand })

        // A result after the deadline changes nothing, and nothing is sent again.
        send(SessionEvent.ActivityResult(ActivityCommandResult(sent.activityId, sent.invocationId, ActivityCommandStatus.DONE), 20_000))
        assertEquals(PageSurfaceContract.ERROR_UNCONFIRMED_ACTION, model.commandOutcomes.getValue("media").status)
        assertTrue(effects.none { it is SendActivityCommand })
    }

    @Test
    fun `a result settles only its own command`() {
        focusOnPlay()
        press(3_000)
        val sent = effects.filterIsInstance<SendActivityCommand>().single().command

        send(SessionEvent.ActivityResult(ActivityCommandResult(sent.activityId, "other", ActivityCommandStatus.DONE), 3_100))
        assertTrue("media" in model.pendingCommands)
        send(SessionEvent.ActivityResult(ActivityCommandResult("radio:activity", sent.invocationId, ActivityCommandStatus.DONE), 3_150))
        assertTrue("media" in model.pendingCommands)

        send(SessionEvent.ActivityResult(ActivityCommandResult(sent.activityId, sent.invocationId, ActivityCommandStatus.STALE, "Changed"), 3_200))
        assertTrue(model.pendingCommands.isEmpty())
        assertEquals(CommandOutcome("play", "stale", "Changed"), model.commandOutcomes["media"])
        assertTrue(effects.contains(SessionEffect.ShowRoot))
    }

    @Test
    fun `a destination-first card opens its page with its params and nothing else`() {
        val destination = PageDestination("media:now", JSONObject().put("track", "t1").toString())
        openRoot(stop(activation = ActivityActivation.DESTINATION, destination = destination))
        val underneath = model.underneath
        press(2_000)

        val request = effects.filterIsInstance<RequestPage>().single().request
        assertEquals("media:now", request.correlation.pageId)
        assertEquals(destination.paramsJson, request.paramsJson)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen || it is SendActivityCommand })
        assertEquals(underneath, model.underneath)
        assertEquals(OpenApp("media", "Media"), top().openApp)

        // The destination ends with the hub's own Open app row.
        respond(request, PageBody.SelectableList(listOf(PageRow("a", "Ana"))), JSONArray(), now = 2_100)
        send(SessionEvent.Step(1, 2_200))
        press(2_210)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })
        shown(2_220)
        press(2_300)
        assertTrue(model.state is SessionState.Launching)
        assertEquals("media", effects.filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)
    }

    @Test
    fun `an offline entry's commands are inert while its destination still opens`() {
        openRoot(stop(offline = true, knownAtWallMs = 5_000))
        press(2_000)
        assertTrue("an inert entry opens its summary instead of focusing commands", model.state is SessionState.InPage)
        assertEquals(LocalPages.activityPageId("media"), effects.filterIsInstance<RequestPage>().single().request.correlation.pageId)

        model = SessionModel()
        openRoot(
            stop(offline = true, activation = ActivityActivation.DESTINATION, destination = PageDestination("media:now")),
        )
        press(2_000)
        assertEquals("media:now", effects.filterIsInstance<RequestPage>().single().request.correlation.pageId)
    }

    @Test
    fun `a live entry held through a link loss is drawn last known, its commands inert, until its next update`() {
        openRoot(stop())
        // The activity tier marks the entry while the phone link is down; nothing is dropped.
        send(SessionEvent.ActivityUpdated(stop().copy(stale = true, knownAtWallMs = 5_000)))
        val screens = SessionScreens(items, pluginLabel = { null }, formatTime = { "t$it" }, wallTimeOf = { it })
        fun card() = screens.screen(model.state, null, model.generation, CommandFacts.of(model))
            .blocks.filterIsInstance<ScreenBlock.Card>().single { it.key == "media:activity" }
        assertTrue(card().offline)
        assertEquals("offline · last known t5000", card().tertiary)

        // Its commands no longer take the focus: the tap opens its summary and runs nothing.
        shown(1_900)
        press(2_000)
        assertTrue(effects.none { it is SendActivityCommand })
        assertEquals(LocalPages.activityPageId("media"), effects.filterIsInstance<RequestPage>().single().request.correlation.pageId)
        send(SessionEvent.Back(2_100))
        assertTrue(model.state is SessionState.Root)

        // Its own next update, after the link is back, restores it.
        send(SessionEvent.ActivityUpdated(stop()))
        assertFalse(card().offline)
        assertNull(card().tertiary)
        shown(2_200)
        press(2_300)
        assertEquals(CommandFocus("media:activity", "play", 1), root().focus)
    }

    @Test
    fun `an old phone keeps the v1 card and a tap invokes a page action in confirmed mode`() {
        negotiated = false
        openRoot(stop(activation = ActivityActivation.DESTINATION, destination = PageDestination("media:now"), pageId = "media:main"))
        press(2_000)

        // The v1 card opens the page it always did; nothing of the bundle acts.
        val request = effects.filterIsInstance<RequestPage>().single().request
        assertEquals("media:main", request.correlation.pageId)
        assertNull(top().openApp)
        respond(
            request,
            PageBody.SelectableList(listOf(PageRow("milk", "Milk", target = PageTarget.Action("check")))),
            JSONArray().put(typedAction("check", PageActionType.SetChecked(true))),
            now = 2_100,
        )
        shown(2_150)
        press(2_200)
        val action = effects.filterIsInstance<SendAction>().single().action
        assertEquals("check", action.actionId)
        assertEquals(PageSurfaceContract.MODE_CONFIRMED, action.mode)
        assertEquals(1L, action.revision)
        assertNull(top().panel)
    }

    @Test
    fun `a quick page row runs on one fresh tap only with the stamp and the policy`() {
        openRoot(stop(owner = "todo", activation = ActivityActivation.DESTINATION, destination = PageDestination("todo:list")))
        press(2_000)
        val rows = PageBody.SelectableList(
            listOf(
                PageRow("milk", "Milk", target = PageTarget.Action("check-milk"), checked = false),
                PageRow("eggs", "Eggs", target = PageTarget.Action("check-eggs"), checked = false),
            ),
        )
        val actions = JSONArray()
            .put(typedAction("check-milk", PageActionType.SetChecked(true)))
            .put(typedAction("check-eggs", PageActionType.SetChecked(true), stamped = false))
        respond(effects.filterIsInstance<RequestPage>().single().request, rows, actions, now = 2_100)
        assertEquals(listOf(false, false), items.rows((top().status as FrameStatus.Shown).snapshot.page).take(2).map { it.checked })

        // The stamped one runs on one tap, but only for a press begun after it was drawn.
        press(2_195)
        assertTrue(effects.none { it is SendAction })
        shown(2_200)
        press(2_300)
        val invocation = effects.filterIsInstance<SendAction>().single().action
        assertEquals("check-milk", invocation.actionId)
        assertEquals(PageSurfaceContract.MODE_QUICK, invocation.mode)
        assertEquals(1L, invocation.revision)
        assertNull(top().panel)
        assertEquals("check-milk", top().invocation?.actionId)
    }

    @Test
    fun `a typed page row without a stamp or policy runs confirmed on one fresh tap`() {
        listOf(false to true, true to false, false to false).forEach { (stamped, approved) ->
            model = SessionModel()
            policy = QuickActionPolicy(2, if (approved) mapOf("todo" to setOf(QuickActionFamily.CHECKLIST)) else emptyMap())
            openRoot(stop(owner = "todo", activation = ActivityActivation.DESTINATION, destination = PageDestination("todo:list")))
            press(2_000)
            respond(
                effects.filterIsInstance<RequestPage>().single().request,
                PageBody.SelectableList(listOf(PageRow("milk", "Milk", target = PageTarget.Action("check")))),
                JSONArray().put(typedAction("check", PageActionType.SetChecked(true), stamped)),
                now = 2_100,
            )
            shown(2_200)
            send(SessionEvent.Enter(2_300, pressStartMs = 2_150))
            assertTrue(effects.none { it is SendAction })
            press(2_400)
            val sent = effects.filterIsInstance<SendAction>().single().action
            assertEquals("check", sent.actionId)
            assertEquals(1L, sent.revision)
            assertEquals(PageSurfaceContract.MODE_CONFIRMED, sent.mode)
            assertNull(top().panel)
            press(2_500)
            assertTrue(effects.none { it is SendAction })
        }
    }

    @Test
    fun `a confirmed command is inert offline and reports NotAvailable when the link is lost`() {
        policy = QuickActionPolicy(2, emptyMap())
        focusOnPlay()
        send(SessionEvent.LinkLost)
        press(3_000)
        assertEquals(listOf(SessionEffect.ShowStatus(SessionStatus.NotAvailable)), effects)
        assertTrue(model.pendingCommands.isEmpty())

        send(SessionEvent.LinkRestored)
        send(SessionEvent.ActivityUpdated(stop().copy(offline = true)))
        assertFalse(model.linkLost)
        assertEquals("play", root().focus?.key)
        shown(3_100)
        press(3_200)
        assertEquals(listOf(SessionEffect.None), effects)
        assertTrue(model.pendingCommands.isEmpty())
    }

    @Test
    fun `native and Camera launches require a press begun after the selected row was drawn`() {
        val apps = listOf(
            AppEntry("app:dev.tele", "Teleprompter", AppKind.NATIVE),
            AppEntry("plugin:camera", "Camera", AppKind.CAMERA),
        )
        val local = LocalPages(
            history = { NotificationHistory() }, activities = { emptyList() },
            applications = { apps }, providerPagesReady = { false }, formatTime = { "12:00" },
        )
        apps.forEachIndexed { index, app ->
            model = SessionModel()
            openRoot()
            val applications = root().stops.indexOfFirst { it.kind == RootStopKind.APPLICATIONS }
            repeat(applications) { send(SessionEvent.Step(1, 1_900L + it)) }
            press(2_000)
            send(SessionEvent.PageResponse(local.answer(effects.filterIsInstance<RequestPage>().single().request), 2_100))
            if (index > 0) {
                shown(2_110)
                send(SessionEvent.Step(1, 2_150))
            }
            send(SessionEvent.Enter(2_180, pressStartMs = 2_120))
            assertTrue(effects.none { it is SessionEffect.LaunchLocal })
            shown(2_200)
            send(SessionEvent.Enter(2_500, pressStartMs = 2_120))
            assertTrue(effects.none { it is SessionEffect.LaunchLocal })
            send(SessionEvent.Enter(2_510, pressStartMs = 2_200))
            assertTrue(effects.none { it is SessionEffect.LaunchLocal })
            press(2_600)
            assertEquals(SessionEffect.LaunchLocal(app.key, app.label), effects.filterIsInstance<SessionEffect.LaunchLocal>().single())
            assertNull(top().panel)
        }
    }

    @Test
    fun `an Applications launch enters Launching on one fresh tap`() {
        val local = LocalPages(
            history = { NotificationHistory() },
            activities = { emptyList() },
            applications = { listOf(AppEntry("plugin:media", "Media", AppKind.IMMERSION, "media")) },
            providerPagesReady = { false },
            formatTime = { "12:00" },
        )
        openRoot()
        val applications = root().stops.indexOfFirst { it.kind == RootStopKind.APPLICATIONS }
        repeat(applications) { send(SessionEvent.Step(1, 1_900L + it)) }
        press(2_000)
        send(SessionEvent.PageResponse(local.answer(effects.filterIsInstance<RequestPage>().single().request), 2_100))
        shown(2_200)
        press(2_190)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })
        press(2_300)
        assertTrue(model.state is SessionState.Launching)
        assertEquals("media", effects.filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)
        press(2_400)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })
    }

    @Test
    fun `a media page focuses its default control first and steps along its controls`() {
        openRoot(stop(activation = ActivityActivation.DESTINATION, destination = PageDestination("media:now")))
        press(2_000)
        val body = PageBody.Media(
            title = "Song",
            state = MediaPlaybackState.PAUSED,
            artist = "Band",
            controls = listOf("previous", "play", "next"),
            defaultControl = "play",
        )
        val actions = JSONArray()
            .put(typedAction("previous", PageActionType.MediaPrevious))
            .put(typedAction("play", PageActionType.MediaPlay))
            .put(typedAction("next", PageActionType.MediaNext))
        respond(effects.filterIsInstance<RequestPage>().single().request, body, actions, now = 2_100)

        send(SessionEvent.Step(1, 2_150))
        assertEquals(listOf(SessionEffect.None), effects)
        shown(2_200)
        press(2_300)
        assertEquals(CommandFocus(CommandFocus.MEDIA, "action play", 1), top().commandFocus)
        assertTrue(effects.none { it is SendAction })

        send(SessionEvent.Step(1, 2_400))
        assertEquals("action next", top().commandFocus?.key)
        shown(2_500)
        press(2_600)
        val invocation = effects.filterIsInstance<SendAction>().single().action
        assertEquals("next", invocation.actionId)
        assertEquals(PageSurfaceContract.MODE_QUICK, invocation.mode)

        send(SessionEvent.Back(2_700))
        assertNull(top().commandFocus)
        assertTrue(model.state is SessionState.InPage)
    }

    @Test
    fun `the Activities page lists every entry and a row acts like its card`() {
        val local = LocalPages(
            history = { NotificationHistory() },
            activities = { model.queuedActivities + model.activities },
            applications = { emptyList() },
            providerPagesReady = { false },
            formatTime = { "12:00" },
        )
        openRoot(
            stop(),
            stop(owner = "todo", pinned = false, activation = ActivityActivation.DESTINATION, destination = PageDestination("todo:list")),
            stop(owner = "timer", pinned = false, offline = true, knownAtWallMs = 1),
        )
        val activities = root().stops.indexOfFirst { it.kind == RootStopKind.ACTIVITIES }
        repeat(activities) { send(SessionEvent.Step(1, 1_900L + it)) }
        press(2_000)
        val request = effects.filterIsInstance<RequestPage>().single().request
        send(SessionEvent.PageResponse(local.answer(request), 2_050))
        val rows = items.rows((top().status as FrameStatus.Shown).snapshot.page)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.item is PageItem.Activity })

        val media = rows.indexOfFirst { (it.item as PageItem.Activity).ownerPluginId == "media" }
        repeat(media) { send(SessionEvent.Step(1, 2_100L + it)) }
        press(2_200)
        assertEquals(CommandFocus("media:activity", "play", 1), top().commandFocus)
        shown(2_250)
        send(SessionEvent.ActivityUpdated(stop(seq = 8)))
        assertTrue(effects.contains(SessionEffect.ShowFrame))
        press(2_300)
        assertTrue(effects.none { it is SendActivityCommand })
        shown(2_350)
        press(2_400)
        assertEquals(8L, effects.filterIsInstance<SendActivityCommand>().single().command.seq)
        send(SessionEvent.Back(2_500))
        assertNull(top().commandFocus)
        assertTrue(model.state is SessionState.InPage)
    }

    @Test
    fun `the screens draw the strip with the hub slot, the offline hint and the focus hints`() {
        openRoot(stop(), stop(owner = "timer", pinned = false, offline = true, knownAtWallMs = 42))
        shown(1_900)
        press(2_000)
        val screens = SessionScreens(items, pluginLabel = { null }, formatTime = { "09:41" }, wallTimeOf = { it })
        val screen = screens.screen(model.state, null, model.generation, CommandFacts.of(model))

        val cards = screen.blocks.filterIsInstance<ScreenBlock.Card>()
        val media = cards.single { it.key == "media:activity" }
        assertEquals(1, media.commands?.focusedIndex)
        assertEquals(listOf("previous", "play", "next", ActivityCommands.OPEN_APP_KEY), media.commands?.items?.map { it.key })
        assertTrue(media.commands?.items?.last()?.hub == true)
        val timer = cards.single { it.key == "timer:activity" }
        assertTrue(timer.offline)
        assertEquals("offline · last known 09:41", timer.tertiary)
        assertEquals(ScreenHint("swipe choose · tap run", "back cards"), screen.hint)
    }

    @Test
    fun `the quick policy keeps the latest revision and forgets everything on reset`() {
        val state = QuickPolicyState()
        val approved = QuickActionPolicy(5, mapOf("media" to setOf(QuickActionFamily.MEDIA)))
        assertTrue(state.apply(approved))
        assertFalse(state.apply(QuickActionPolicy(4, emptyMap())))
        assertEquals(approved, state.policy)
        assertTrue(state.reset())
        assertEquals(QuickActionPolicy.NONE, state.policy)
        // After a reset the phone's next policy is taken whatever its revision.
        assertTrue(state.apply(QuickActionPolicy(1, emptyMap())))

        val gate = LauncherQuickGate({ 1 }) { approved }
        assertTrue(gate.allows("media", PageActionType.MediaPlay, hubQuick = true))
        assertFalse(gate.allows("media", PageActionType.MediaPlay, hubQuick = false))
        assertFalse(gate.allows("media", PageActionType.SetChecked(true), hubQuick = true))
        assertFalse(gate.allows("radio", PageActionType.MediaPlay, hubQuick = true))
        assertFalse(LauncherQuickGate({ 0 }) { approved }.allows("media", PageActionType.MediaPlay, hubQuick = true))
    }

    private fun stop(
        owner: String = "media",
        seq: Long = 7,
        activation: ActivityActivation? = ActivityActivation.COMMANDS,
        destination: PageDestination? = null,
        offline: Boolean = false,
        commands: List<ActivityAction> = mediaCommands,
        pinned: Boolean = true,
        knownAtWallMs: Long? = null,
        pageId: String = LocalPages.activityPageId(owner),
    ) = ActivityStop(
        id = "$owner:activity",
        pageId = pageId,
        text = "Song",
        pinned = pinned,
        ownerPluginId = owner,
        ownerLabel = owner.replaceFirstChar(Char::uppercase),
        offline = offline,
        knownAtWallMs = knownAtWallMs,
        seq = seq,
        destination = destination,
        activation = activation,
        commands = if (offline) emptyList() else commands,
        defaultActionId = "play",
        launchable = true,
    )

    /** Root open, the card focused on Play, and (unless [ackAt] is null) that focus drawn. */
    private fun focusOnPlay(ackAt: Long? = 2_500) {
        openRoot(stop())
        shown(1_900)
        press(2_000)
        assertEquals("play", root().focus?.key)
        ackAt?.let(::shown)
    }

    private fun openRoot(vararg stops: ActivityStop, at: Long = 1_000) {
        stops.forEach { send(SessionEvent.ActivityStarted(it)) }
        send(SessionEvent.TripleTap(at))
        send(SessionEvent.Tick(at + PageSurfaceContract.GATE_MS))
        assertTrue(model.state is SessionState.Root)
    }

    /** The host's acknowledgement that whatever is focused now was drawn at [atMs]. */
    private fun shown(atMs: Long) {
        send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    }

    /** A touchpad tap whose contact began at [at]; the firmware classifies it 40 ms later. */
    private fun press(at: Long) = send(SessionEvent.Enter(at + 40, pressStartMs = at))

    private fun respond(request: PageSurfaceRequest, body: PageBody, actions: JSONArray, now: Long) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1).put("template", body.template).put("title", "Page")
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
        assertTrue(top().status is FrameStatus.Shown)
    }

    private fun typedAction(id: String, type: PageActionType, stamped: Boolean = true) = JSONObject()
        .put("id", id).put("label", id).put("kind", "plugin").put("confirm", false)
        .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(type))
        .put(QuickActionPolicyContract.FIELD_HUB_QUICK, stamped)

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun root() = model.state as SessionState.Root
    private fun top() = (model.state as SessionState.InPage).frames.last()
}

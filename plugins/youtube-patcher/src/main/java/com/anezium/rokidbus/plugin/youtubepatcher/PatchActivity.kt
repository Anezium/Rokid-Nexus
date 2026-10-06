package com.anezium.rokidbus.plugin.youtubepatcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import com.anezium.rokidbus.client.ui.NexusUi
import com.anezium.rokidbus.shared.YoutubePatcherContract as Contract
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

class PatchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var bundleStore: BundleStore
    private lateinit var selections: SelectionStore
    private lateinit var key: SigningKey
    private var bundle: BundleStore.Loaded? = null
    private var choices = mutableMapOf<String, Boolean>()
    private var stock: File? = null
    private lateinit var work: File
    private var busy = false
    private var patching = false
    private var result: File? = null
    private var backupPassword: CharArray? = null
    private var screenLock: java.nio.channels.FileLock? = null
    private var lockFile: java.io.RandomAccessFile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lockFile = java.io.RandomAccessFile(File(filesDir, "screen.lock"), "rw")
        screenLock = try { lockFile!!.channel.tryLock() } catch (_: java.nio.channels.OverlappingFileLockException) { null }
        if (screenLock == null) {
            AlertDialog.Builder(this).setMessage("YouTube Patcher is already open. Close the other screen first.")
                .setPositiveButton("Close") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
            return
        }
        window.statusBarColor = NexusUi.BG
        window.navigationBarColor = NexusUi.BG
        bundleStore = BundleStore(this)
        selections = SelectionStore(File(filesDir, "selection.json"))
        key = SigningKey(File(filesDir, "signing/youtube.p12"))
        // Nothing in these directories is an accepted result. Clear crash leftovers.
        File(cacheDir, "jobs").deleteRecursively()
        File(filesDir, "results").listFiles()?.filter { it.name.endsWith(".partial") }?.forEach { it.delete() }
        purgeResults(null)
        work = File(cacheDir, "jobs/${UUID.randomUUID()}").apply { mkdirs() }
        render()
        perform {
            val loaded = withContext(Dispatchers.IO) { bundleStore.current() }
            adopt(loaded)
            var notice = loaded.notice
            try {
                val update = withContext(Dispatchers.IO) { bundleStore.checkForUpdate(loaded.version) }
                if (update.switched) withContext(Dispatchers.IO) { bundleStore.current() }.also { adopt(it); notice = it.notice }
                report(listOfNotNull(notice, update.message).joinToString("\n"))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(listOfNotNull(notice, "Using saved bundle. Update check: ${e.message}").joinToString("\n")) }
        }
    }
    private fun purgeResults(keep: File?) {
        val results = File(filesDir, "results").listFiles()?.filter { PatchPolicy.isResult(it.name) } ?: return
        PatchPolicy.expiredResults(results.associateWith { it.lastModified() }, System.currentTimeMillis(), keep).forEach { it.delete() }
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // The manifest keeps this activity alive across configuration changes so a running
        // patch survives; rebuild only the views so sizes follow the new configuration.
        if (::status.isInitialized) { val message = status.text.toString(); render(); report(message) }
    }
    private fun adopt(loaded: BundleStore.Loaded) {
        bundle = loaded
        choices = selections.load(loaded.version, loaded.patches.associate { it.name!! to it.default }).toMutableMap()
        selections.save(loaded.version, choices)
        render()
    }
    private fun render() {
        content = NexusUi.contentColumn(this)
        fun text(value: String) { content.addView(NexusUi.cardBody(this, value), NexusUi.block()) }
        fun button(label: String, action: () -> Unit) {
            content.addView(NexusUi.pillButton(this, label).apply { isEnabled = !busy; setOnClickListener { action() } }, NexusUi.block())
        }
        text("Adds glasses controls to YouTube. Choose Google's stock ${PatchPolicy.VERSION} APK or APKMirror split bundle. The APK stays on this phone.")
        content.addView(NexusUi.sectionRow(this, "Stock YouTube"), NexusUi.block())
        text(if (stock != null) "Validated and prepared: YouTube ${PatchPolicy.VERSION}" else "No stock APK selected.")
        button("Choose APK or bundle") { picker(REQUEST_STOCK, Intent.ACTION_OPEN_DOCUMENT, "*/*") }
        content.addView(NexusUi.sectionRow(this, "Patches", bundle?.version ?: "Loading"), NexusUi.block())
        bundle?.let { loaded ->
            var allLabel = false
            loaded.patches.forEach { patch ->
                if (!allLabel && BundleStore.priority(patch.name!!) >= 4) {
                    allLabel = true
                    content.addView(NexusUi.sectionRow(this, "All patches"), NexusUi.block())
                }
                content.addView(CheckBox(this).apply {
                    text = patch.name
                    setTextColor(NexusUi.INK)
                    isChecked = choices[patch.name] == true
                    isEnabled = !busy
                    setOnCheckedChangeListener { _, checked ->
                        choices[patch.name!!] = checked
                        try { selections.save(loaded.version, choices) } catch (e: Exception) { report(e.message ?: "Cannot save choices.") }
                    }
                }, NexusUi.block())
                if (BundleStore.priority(patch.name!!) < 4) text(patch.description ?: patch.name!!)
            }
        }
        button("Patch YouTube") { confirmPatch() }
        status = NexusUi.cardBody(this, "Keep this screen open while patching. Closing it cancels the job.")
        content.addView(status, NexusUi.block())
        content.addView(NexusUi.outlinePillButton(this, "Cancel").apply {
            setOnClickListener { cancelAndClose() }
        }, NexusUi.block())
        result?.let {
            button("Share patched APK") { shareResult(it) }
            button("Save patched APK") { picker(REQUEST_SAVE, Intent.ACTION_CREATE_DOCUMENT, "application/vnd.android.package-archive", "youtube-patched.apk") }
        }
        content.addView(NexusUi.sectionRow(this, "Signing key"), NexusUi.block())
        text("Uninstalling this plugin loses its signing key. Export it now to keep future YouTube updates compatible. A YouTube app patched with Morphe Manager uses Manager's key, which this backup format cannot import: keep updating it with Manager, or uninstall YouTube on the glasses by hand before installing a build from this plugin.")
        button("Export key") { askPassword(false) }
        button("Import key") {
            AlertDialog.Builder(this).setTitle("Replace signing key?")
                .setMessage("Future patches will use the imported key. Existing YouTube must have the same signer to update.")
                .setNegativeButton("Cancel", null).setPositiveButton("Import") { _, _ -> askPassword(true) }.show()
        }
        content.addView(NexusUi.sectionRow(this, "Details"), NexusUi.block())
        bundle?.let { text("Bundle ${it.version}\nSHA-256 ${it.hash}\nOriginal release SHA-256 ${it.sourceHash ?: "Same as bundle"}\nPatcher 1.7.0 · aapt-free resources") }
        text("Android bundle updates require publisher-prepared DEX and Patcher 1.7.0. JVM-only fork releases cannot update this plugin on the phone; install a plugin built with a supported prepared bundle instead. Incompatible updates keep the saved bundle. No on-phone conversion is available.")
        text("Uses Morphe Patcher and the Rokid Morphe Patches fork (GPLv3). No signature is published for the bundle. https://github.com/Anezium/morphe-patches")
        button("Licenses and notices") {
            val notices = assets.open("licenses/MORPHE_LICENSE_NOTICE.TXT").bufferedReader().use { it.readText() }
            val gpl = assets.open("licenses/GPL-3.0.txt").bufferedReader().use { it.readText() }
            val body = NexusUi.contentColumn(this).apply {
                addView(NexusUi.cardBody(this@PatchActivity, "YouTube Patcher modifies the executable packaging of the Rokid fork bundle by adding Android DEX. It is not an official upstream distribution.\n\n$notices\n\n$gpl"), NexusUi.block())
            }
            AlertDialog.Builder(this).setTitle("Licenses and notices").setView(NexusUi.screen(this, body)).setPositiveButton("Close", null).show()
        }
        content.addView(NexusUi.sectionRow(this, "Plugin"), NexusUi.block())
        content.addView(NexusUi.uninstallCard(this, "YouTube Patcher") {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
        }, NexusUi.block())
        val root = NexusUi.fixedRoot(this).apply {
            addView(NexusUi.pluginHeader(this@PatchActivity, com.anezium.rokidbus.client.R.drawable.ic_plugin_bolt, "YouTube Patcher", "Phone-only · v1.0.0"), NexusUi.block())
            addView(NexusUi.screen(this@PatchActivity, content), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
    }
    private fun report(message: String) { status.text = message }
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true; render()
        scope.launch {
            var message: String? = null
            try { block(); message = status.text.toString() }
            catch (e: CancellationException) { throw e }
            catch (e: OutOfMemoryError) { message = "Not enough memory to patch YouTube on this phone. No result was saved." }
            catch (e: Exception) { message = e.message ?: e.javaClass.simpleName }
            finally {
                busy = false; patching = false
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (!isFinishing && !isDestroyed) { render(); message?.let(::report) }
            }
        }
    }
    private fun confirmPatch() {
        if (stock == null || bundle == null) { report("Choose a stock APK and wait for the bundle first."); return }
        val warnings = mutableListOf<String>()
        if (choices["GmsCore support"] != true) warnings += "GmsCore support is off: sign-in will not work."
        if (choices.filterKeys { it.contains("Rokid", true) }.values.none { it }) warnings += "Rokid controls is off: the glasses cannot drive stock YouTube."
        if (warnings.isEmpty()) startPatch() else AlertDialog.Builder(this).setTitle("Continue without recommended patches?")
            .setMessage(warnings.joinToString("\n")).setNegativeButton("Review", null).setPositiveButton("Continue") { _, _ -> startPatch() }.show()
    }
    private fun startPatch() {
        val input = stock ?: return
        val loaded = bundle ?: return
        val selected = loaded.patches.filter { choices[it.name] == true }.toSet()
        result = null
        perform {
            patching = true
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val signed = withContext(Dispatchers.IO) {
                PatchRuntime().patch(input, selected, work, key) { message -> runOnUiThread { if (!isDestroyed) report(message) } }
            }
            currentCoroutineContext().ensureActive()
            val directory = File(filesDir, "results").apply { mkdirs() }
            val target = File(directory, "youtube-${UUID.randomUUID()}.apk")
            withContext(Dispatchers.IO) {
                val pending = File(directory, ".${target.name}.partial")
                try {
                    signed.inputStream().use { input -> pending.outputStream().use { output ->
                        PatchPolicy.copyBounded(input, output); output.fd.sync()
                    } }
                    currentCoroutineContext().ensureActive()
                    require(pending.renameTo(target)) { "Cannot save verified result." }
                    signed.delete()
                } finally { pending.delete() }
                purgeResults(target)
            }
            currentCoroutineContext().ensureActive()
            result = target
            if (intent.action == Contract.ACTION_PATCH && callingActivity != null) {
                val uri = uri(target)
                val outputPackage = withContext(Dispatchers.IO) {
                    com.reandroid.apk.ApkModule.loadApkFile(target).use { it.packageName }
                }
                val data = Intent().setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(Contract.EXTRA_PACKAGE_NAME, outputPackage)
                    .putExtra(Contract.EXTRA_VERSION_NAME, PatchPolicy.VERSION)
                    .putExtra(Contract.EXTRA_SHA256, withContext(Dispatchers.IO) { PatchPolicy.sha256(target) })
                data.clipData = ClipData.newRawUri("Patched YouTube", uri)
                patching = false
                setResult(RESULT_OK, data); finish()
            } else report("Patched APK signature verified. Share or save it below.")
        }
    }
    private fun uri(file: File) = FileProvider.getUriForFile(this, "$packageName.results", file)
    private fun shareResult(file: File) {
        val uri = uri(file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"; putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Patched YouTube", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "Share patched YouTube"))
    }
    private fun picker(request: Int, action: String, type: String, name: String? = null) {
        startActivityForResult(Intent(action).apply {
            addCategory(Intent.CATEGORY_OPENABLE); this.type = type
            name?.let { putExtra(Intent.EXTRA_TITLE, it) }
        }, request)
    }
    private fun askPassword(importing: Boolean) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val field = EditText(this).apply { inputType = 129; hint = "Backup password (at least 8 characters)" }
        AlertDialog.Builder(this).setTitle(if (importing) "Import key" else "Export key").setView(field)
            .setNegativeButton("Cancel", null).setPositiveButton("Choose file") { _, _ ->
                val password = field.text.toString().toCharArray(); field.text.clear()
                if (password.size < 8) { password.fill('\u0000'); report("Use at least eight characters.") }
                else {
                    backupPassword?.fill('\u0000'); backupPassword = password
                    picker(if (importing) REQUEST_IMPORT else REQUEST_EXPORT,
                        if (importing) Intent.ACTION_OPEN_DOCUMENT else Intent.ACTION_CREATE_DOCUMENT,
                        "application/octet-stream", if (importing) null else "youtube-signing-key.ypk")
                }
            }.setOnDismissListener { field.text.clear() }.show()
    }
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) {
            backupPassword?.fill('\u0000'); backupPassword = null; return
        }
        val uri = data.data!!
        when (requestCode) {
            REQUEST_STOCK -> perform {
                stock = null
                report("Reading and validating stock APK")
                stock = withContext(Dispatchers.IO) {
                    val input = File(work, "input.zip")
                    contentResolver.openInputStream(uri).use { source ->
                        requireNotNull(source) { "Cannot read selected file." }
                        input.outputStream().use { PatchPolicy.copyBounded(source, it) }
                    }
                    val prepare = File(work, "prepare").apply { deleteRecursively(); mkdirs() }
                    ApkPreparer().prepare(input, prepare)
                }
                report("Stock YouTube validated and ready.")
            }
            REQUEST_EXPORT, REQUEST_IMPORT -> {
                val password = backupPassword ?: return
                backupPassword = null
                perform {
                    try {
                        withContext(Dispatchers.IO) {
                            if (requestCode == REQUEST_EXPORT) contentResolver.openOutputStream(uri, "wt").use { key.export(requireNotNull(it), password) }
                            else contentResolver.openInputStream(uri).use { key.import(requireNotNull(it), password) }
                        }
                        report(if (requestCode == REQUEST_EXPORT) "Password-protected key exported." else "Signing key imported.")
                    } finally { password.fill('\u0000') }
                }
            }
            REQUEST_SAVE -> result?.let { file -> perform {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt").use { target ->
                        file.inputStream().use { PatchPolicy.copyBounded(it, requireNotNull(target)) }
                    }
                }
                report("Patched APK saved.")
            } }
        }
    }
    private fun cancelAndClose() {
        setResult(RESULT_CANCELED)
        scope.cancel()
        finish()
        // Some upstream patch code is synchronous and not cooperatively cancellable.
        // Only this screen lives in :patcher; process death stops it immediately.
        if (busy) android.os.Process.killProcess(android.os.Process.myPid())
    }
    @Deprecated("Platform callback") override fun onBackPressed() { cancelAndClose() }
    override fun onDestroy() {
        scope.cancel()
        backupPassword?.fill('\u0000'); backupPassword = null
        super.onDestroy()
        if (busy) android.os.Process.killProcess(android.os.Process.myPid())
        else if (::work.isInitialized) work.deleteRecursively()
        screenLock?.release(); lockFile?.close()
    }
    companion object {
        private const val REQUEST_STOCK = 1
        private const val REQUEST_EXPORT = 2
        private const val REQUEST_IMPORT = 3
        private const val REQUEST_SAVE = 4
    }
}

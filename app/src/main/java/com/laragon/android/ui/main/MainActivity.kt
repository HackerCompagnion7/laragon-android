package com.laragon.android.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.laragon.android.R
import com.laragon.android.databinding.ActivityMainBinding
import com.laragon.android.service.LaragonService
import com.laragon.android.ui.diagnostics.DiagnosticsFragment
import com.laragon.android.ui.editor.EditorActivity
import com.laragon.android.ui.preview.PreviewActivity
import com.laragon.android.util.LogRotator
import com.laragon.android.util.PhpBinaryManager
import com.laragon.android.util.ServerConfig
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val TAG = "LaragonMain"

    private var binding: ActivityMainBinding? = null
    private var prefs: SharedPreferences? = null
    private var phpBinaryManager: PhpBinaryManager? = null
    private var logRotator: LogRotator? = null
    private var projectAdapter: ProjectAdapter? = null

    private var selectedTreeUri: String? = null
    private var selectedFileUri: String? = null
    private var selectedFileName: String? = null
    private var directPath: String? = null
    private var selectionMode: String = ServerConfig.MODE_PATH
    private var serverState: LaragonService.ServerState = LaragonService.ServerState.STOPPED
    private var viewsInitialized = false
    private var permissionsRequested = false

    // SAF folder picker - registered BEFORE onCreate as required
    private val folderPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                prefs?.edit()
                    ?.putString(ServerConfig.PREF_TREE_URI, it.toString())
                    ?.putString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_FOLDER)
                    ?.remove(ServerConfig.PREF_DIRECT_PATH)
                    ?.apply()
                selectedTreeUri = it.toString()
                selectionMode = ServerConfig.MODE_FOLDER
                updateDisplay()
                refreshProjectList()
                Toast.makeText(this, "Folder selected", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e(TAG, "Folder picker error", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // SAF file picker
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                contentResolver.takePersistableUriPermission(
                    it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                val fileName = getFileNameFromUri(it)
                prefs?.edit()
                    ?.putString(ServerConfig.PREF_FILE_URI, it.toString())
                    ?.putString(ServerConfig.PREF_FILE_NAME, fileName)
                    ?.putString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_FILE)
                    ?.remove(ServerConfig.PREF_DIRECT_PATH)
                    ?.apply()
                selectedFileUri = it.toString()
                selectedFileName = fileName
                selectionMode = ServerConfig.MODE_FILE
                updateDisplay()
                Toast.makeText(this, "File: $fileName", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e(TAG, "File picker error", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // MANAGE_EXTERNAL_STORAGE launcher (Android 11+)
    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        updatePermissionCard()
    }

    // Notification permission (Android 13+)
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    // Storage permission (Android 9-)
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ -> updatePermissionCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Step 1: Inflate layout - isolated try-catch
        try {
            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding!!.root)
        } catch (e: Throwable) {
            Log.e(TAG, "FATAL: Layout inflation failed", e)
            // Fallback: use a simple layout so the app doesn't just die
            try {
                val fallbackLayout = android.widget.LinearLayout(this).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(32, 32, 32, 32)
                    val tv = android.widget.TextView(this@MainActivity).apply {
                        text = "Layout error: ${e.message}\nPlease restart the app."
                        textSize = 16f
                    }
                    addView(tv)
                }
                setContentView(fallbackLayout)
            } catch (e2: Throwable) {
                Log.e(TAG, "FATAL: Even fallback layout failed", e2)
            }
            return
        }

        // Step 2: Initialize core objects - each in its own try-catch
        try {
            prefs = getSharedPreferences(ServerConfig.PREFS_NAME, Context.MODE_PRIVATE)
        } catch (e: Throwable) {
            Log.e(TAG, "SharedPreferences init failed", e)
        }

        try {
            phpBinaryManager = PhpBinaryManager(this)
        } catch (e: Throwable) {
            Log.e(TAG, "PhpBinaryManager init failed", e)
        }

        try {
            logRotator = LogRotator(this)
        } catch (e: Throwable) {
            Log.e(TAG, "LogRotator init failed", e)
        }

        // Step 3: Set up toolbar
        try {
            binding?.toolbar?.let { setSupportActionBar(it) }
            supportActionBar?.title = "Laragon Android"
        } catch (e: Throwable) {
            Log.e(TAG, "Toolbar setup failed", e)
        }

        // Step 4: Set up views and listeners - CRITICAL, must not fail
        try {
            setupViews()
            viewsInitialized = true
        } catch (e: Throwable) {
            Log.e(TAG, "setupViews failed", e)
        }

        // Step 5: Set up RecyclerView
        try {
            setupRecyclerView()
        } catch (e: Throwable) {
            Log.e(TAG, "setupRecyclerView failed", e)
        }

        // Step 6: Load saved state
        try {
            loadSavedState()
        } catch (e: Throwable) {
            Log.e(TAG, "loadSavedState failed", e)
        }

        // Step 7: Request permissions - DEFERRED to onResume to ensure it always runs
        // (see onResume)

        // Step 8: Observe server state
        try {
            observeServerState()
        } catch (e: Throwable) {
            Log.e(TAG, "observeServerState failed", e)
        }

        Log.i(TAG, "onCreate completed successfully. viewsInitialized=$viewsInitialized")
    }

    override fun onResume() {
        super.onResume()
        try {
            updatePermissionCard()
        } catch (_: Throwable) {}

        // Always request permissions on first resume
        if (!permissionsRequested) {
            permissionsRequested = true
            try {
                requestAllPermissions()
            } catch (e: Throwable) {
                Log.e(TAG, "requestAllPermissions failed", e)
            }
        }

        if (selectionMode == ServerConfig.MODE_FOLDER && selectedTreeUri != null) {
            try {
                refreshProjectList()
            } catch (_: Throwable) {}
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return try {
            menuInflater.inflate(R.menu.menu_main, menu)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "createOptionsMenu failed", e)
            super.onCreateOptionsMenu(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            when (item.itemId) {
                R.id.action_diagnostics -> { showDiagnostics(); true }
                R.id.action_create_project -> { showCreateProjectDialog(); true }
                R.id.action_settings -> { true }
                else -> super.onOptionsItemSelected(item)
            }
        } catch (e: Throwable) {
            super.onOptionsItemSelected(item)
        }
    }

    private fun setupViews() {
        val b = binding ?: run {
            Log.e(TAG, "setupViews: binding is null!")
            return
        }

        b.btnGrantStorage.setOnClickListener {
            try { requestAllPermissions() } catch (e: Throwable) {
                Log.e(TAG, "grant storage click", e)
            }
        }

        b.btnUsePath.setOnClickListener {
            try {
                val path = b.etPath.text.toString().trim()
                if (path.isBlank()) {
                    Toast.makeText(this, "Enter a path", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val dir = File(path)
                if (!dir.exists()) {
                    val created = dir.mkdirs()
                    if (!created) {
                        Toast.makeText(this, "Cannot access or create: $path", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                }
                if (dir.isFile) {
                    prefs?.edit()
                        ?.putString(ServerConfig.PREF_DIRECT_PATH, path)
                        ?.putString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_FILE)
                        ?.putString(ServerConfig.PREF_FILE_NAME, dir.name)
                        ?.remove(ServerConfig.PREF_TREE_URI)
                        ?.remove(ServerConfig.PREF_FILE_URI)
                        ?.apply()
                    directPath = path
                    selectedFileName = dir.name
                    selectionMode = ServerConfig.MODE_FILE
                    selectedFileUri = null
                } else {
                    prefs?.edit()
                        ?.putString(ServerConfig.PREF_DIRECT_PATH, path)
                        ?.putString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_PATH)
                        ?.remove(ServerConfig.PREF_TREE_URI)
                        ?.remove(ServerConfig.PREF_FILE_URI)
                        ?.apply()
                    directPath = path
                    selectionMode = ServerConfig.MODE_PATH
                }
                updateDisplay()
                refreshProjectList()
                Toast.makeText(this, "Path set: $path", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Log.e(TAG, "use path click", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        b.btnSelectFolder.setOnClickListener {
            try {
                folderPickerLauncher.launch(null)
            } catch (e: Throwable) {
                Log.e(TAG, "folder picker", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        b.btnSelectFile.setOnClickListener {
            try {
                filePickerLauncher.launch(arrayOf("*/*"))
            } catch (e: Throwable) {
                Log.e(TAG, "file picker", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        b.btnStartStop.setOnClickListener {
            try { toggleServer() } catch (e: Throwable) {
                Log.e(TAG, "toggle server", e)
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }

        b.btnPreview.setOnClickListener {
            try { openPreview() } catch (e: Throwable) {
                Log.e(TAG, "preview click", e)
            }
        }

        b.btnCreateProject.setOnClickListener {
            try { showCreateProjectDialog() } catch (e: Throwable) {
                Log.e(TAG, "create project click", e)
            }
        }

        b.btnRefresh.setOnClickListener {
            try { refreshProjectList() } catch (e: Throwable) {
                Log.e(TAG, "refresh click", e)
            }
        }
    }

    private fun setupRecyclerView() {
        projectAdapter = ProjectAdapter { project ->
            try { showProjectOptions(project) } catch (e: Throwable) {
                Log.e(TAG, "project click", e)
            }
        }
        binding?.recyclerProjects?.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = projectAdapter
        }
    }

    private fun loadSavedState() {
        val p = prefs ?: return
        selectionMode = p.getString(ServerConfig.PREF_SELECTION_MODE, ServerConfig.MODE_PATH)
            ?: ServerConfig.MODE_PATH
        selectedTreeUri = p.getString(ServerConfig.PREF_TREE_URI, null)
        selectedFileUri = p.getString(ServerConfig.PREF_FILE_URI, null)
        selectedFileName = p.getString(ServerConfig.PREF_FILE_NAME, null)
        directPath = p.getString(ServerConfig.PREF_DIRECT_PATH, null)

        // Auto-populate path field
        if (directPath != null) {
            binding?.etPath?.setText(directPath)
        } else {
            binding?.etPath?.setText("/sdcard/")
        }

        updateDisplay()
        updateServerButton()
    }

    // ═══════════════════════════════════════════════════
    // PERMISOS
    // ═══════════════════════════════════════════════════

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestAllPermissions() {
        // Android 11+: MANAGE_EXTERNAL_STORAGE (all files access)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Throwable) {
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        manageStorageLauncher.launch(intent)
                    } catch (e2: Throwable) {
                        Toast.makeText(this, "Go to Settings > Apps > Laragon > Permissions > Allow all files", Toast.LENGTH_LONG).show()
                    }
                }
            }
        } else {
            // Android 10 and below
            val perms = mutableListOf<String>()
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
            if (perms.isNotEmpty()) {
                storagePermissionLauncher.launch(perms.toTypedArray())
            }
        }

        // Notification permission (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } catch (_: Throwable) {}
            }
        }

        try {
            updatePermissionCard()
        } catch (_: Throwable) {}
    }

    private fun updatePermissionCard() {
        val b = binding ?: return
        try {
            if (hasStorageAccess()) {
                b.cardPermission.visibility = View.GONE
            } else {
                b.cardPermission.visibility = View.VISIBLE
                b.tvPermissionStatus.text = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    "Grant 'All files access' permission to browse your storage"
                } else {
                    "Grant storage permission to access your files"
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "updatePermissionCard", e)
        }
    }

    // ═══════════════════════════════════════════════════
    // SERVER STATE
    // ═══════════════════════════════════════════════════

    private fun observeServerState() {
        lifecycleScope.launch {
            try {
                LaragonService.getServerState()?.collect { state ->
                    serverState = state
                    updateServerButton()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "observeServerState", e)
            }
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name = "Unknown"
        try {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val i = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) name = it.getString(i)
                }
            }
        } catch (_: Throwable) {}
        return name
    }

    private fun updateDisplay() {
        val b = binding ?: return
        try {
            when (selectionMode) {
                ServerConfig.MODE_PATH -> {
                    val path = directPath ?: "No path set"
                    b.tvPathStatus.text = "Path mode: $path"
                    val dir = directPath?.let { File(it) }
                    if (dir != null && dir.exists() && dir.isDirectory) {
                        b.cardProjects.visibility = View.VISIBLE
                    } else {
                        b.cardProjects.visibility = View.GONE
                    }
                }
                ServerConfig.MODE_FILE -> {
                    if (directPath != null) {
                        b.tvPathStatus.text = "File: $directPath"
                    } else {
                        b.tvPathStatus.text = "File: ${selectedFileName ?: "unknown"}"
                    }
                    b.cardProjects.visibility = View.GONE
                }
                ServerConfig.MODE_FOLDER -> {
                    val docFile = selectedTreeUri?.let { DocumentFile.fromTreeUri(this, Uri.parse(it)) }
                    b.tvPathStatus.text = "Folder: ${docFile?.name ?: "selected"}"
                    b.cardProjects.visibility = View.VISIBLE
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "updateDisplay", e)
        }
    }

    private fun updateServerButton() {
        val b = binding ?: return
        try {
            when (serverState) {
                LaragonService.ServerState.STOPPED -> {
                    b.btnStartStop.text = "Start Server"
                    b.btnStartStop.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
                    b.tvServerStatus.text = "Server stopped"
                    b.tvServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                    b.btnPreview.isEnabled = false
                    b.btnStartStop.isEnabled = true
                }
                LaragonService.ServerState.STARTING -> {
                    b.btnStartStop.text = "Starting..."
                    b.btnStartStop.isEnabled = false
                    b.tvServerStatus.text = "Starting..."
                    b.tvServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark))
                }
                LaragonService.ServerState.RUNNING -> {
                    b.btnStartStop.text = "Stop Server"
                    b.btnStartStop.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_pause, 0, 0, 0)
                    b.btnStartStop.isEnabled = true
                    b.tvServerStatus.text = "Running on port ${ServerConfig.DEFAULT_PORT}"
                    b.tvServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
                    b.btnPreview.isEnabled = true
                }
                LaragonService.ServerState.ERROR -> {
                    b.btnStartStop.text = "Start Server"
                    b.btnStartStop.setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_media_play, 0, 0, 0)
                    b.btnStartStop.isEnabled = true
                    b.tvServerStatus.text = "Error - check diagnostics"
                    b.tvServerStatus.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "updateServerButton", e)
        }
    }

    private fun hasSelection(): Boolean {
        return when (selectionMode) {
            ServerConfig.MODE_PATH -> directPath != null && File(directPath!!).exists()
            ServerConfig.MODE_FILE -> selectedFileUri != null || (directPath != null && File(directPath!!).exists())
            ServerConfig.MODE_FOLDER -> selectedTreeUri != null
            else -> false
        }
    }

    private fun toggleServer() {
        if (!hasSelection()) {
            Toast.makeText(this, "Set a path or select a folder/file first", Toast.LENGTH_LONG).show()
            return
        }
        try {
            when (serverState) {
                LaragonService.ServerState.STOPPED, LaragonService.ServerState.ERROR -> {
                    val intent = Intent(this, LaragonService::class.java).apply {
                        action = LaragonService.ACTION_START
                        putExtra(LaragonService.EXTRA_PORT, ServerConfig.DEFAULT_PORT)
                    }
                    startForegroundService(intent)
                }
                LaragonService.ServerState.RUNNING -> {
                    val intent = Intent(this, LaragonService::class.java).apply {
                        action = LaragonService.ACTION_STOP
                    }
                    startService(intent)
                }
                else -> {}
            }
        } catch (e: Throwable) {
            Log.e(TAG, "toggleServer", e)
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun refreshProjectList() {
        val b = binding ?: return

        // For direct path mode, list from filesystem
        if (selectionMode == ServerConfig.MODE_PATH && directPath != null) {
            val rootDir = File(directPath!!)
            if (!rootDir.exists() || !rootDir.isDirectory) {
                b.cardProjects.visibility = View.GONE
                return
            }
            b.cardProjects.visibility = View.VISIBLE
            try {
                val projects = mutableListOf<ProjectItem>()
                rootDir.listFiles()?.filter { it.isDirectory }?.forEach { dir ->
                    val hasIndex = dir.listFiles()?.any {
                        it.name in listOf("index.php", "index.html", "index.htm")
                    } ?: false
                    projects.add(ProjectItem(name = dir.name, uri = dir.absolutePath, hasIndex = hasIndex))
                }
                if (projects.isEmpty()) {
                    val hasIndex = rootDir.listFiles()?.any {
                        it.name in listOf("index.php", "index.html", "index.htm")
                    } ?: false
                    projects.add(ProjectItem(name = rootDir.name, uri = rootDir.absolutePath, hasIndex = hasIndex))
                }
                projectAdapter?.submitList(projects)
                b.tvProjectCount.text = "${projects.size} project(s)"
            } catch (e: Throwable) {
                Log.e(TAG, "refreshProjectList path", e)
            }
            return
        }

        // SAF folder mode
        val treeUri = selectedTreeUri ?: return
        try {
            val treeDoc = DocumentFile.fromTreeUri(this, Uri.parse(treeUri)) ?: return
            lifecycleScope.launch {
                try {
                    val projects = mutableListOf<ProjectItem>()
                    treeDoc.listFiles().filter { it.isDirectory }.forEach { dir ->
                        val hasIndex = dir.listFiles().any {
                            it.name in listOf("index.php", "index.html", "index.htm")
                        }
                        projects.add(ProjectItem(name = dir.name ?: "Unknown", uri = dir.uri.toString(), hasIndex = hasIndex))
                    }
                    if (projects.isEmpty()) {
                        val hasIndex = treeDoc.listFiles().any {
                            it.name in listOf("index.php", "index.html", "index.htm")
                        }
                        projects.add(ProjectItem(name = treeDoc.name ?: "Root", uri = treeDoc.uri.toString(), hasIndex = hasIndex))
                    }
                    projectAdapter?.submitList(projects)
                    b.tvProjectCount.text = "${projects.size} project(s)"
                } catch (e: Throwable) {
                    Log.e(TAG, "refreshProjectList SAF", e)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "refreshProjectList", e)
        }
    }

    private fun openPreview() {
        if (serverState != LaragonService.ServerState.RUNNING) {
            Toast.makeText(this, "Start the server first", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(this, PreviewActivity::class.java))
    }

    private fun showDiagnostics() {
        try {
            DiagnosticsFragment.newInstance().show(supportFragmentManager, "diagnostics")
        } catch (e: Throwable) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCreateProjectDialog() {
        if (selectionMode == ServerConfig.MODE_FILE) {
            Toast.makeText(this, "Switch to folder/path mode first", Toast.LENGTH_SHORT).show()
            return
        }
        val path = directPath ?: selectedTreeUri?.let { DocumentFile.fromTreeUri(this, Uri.parse(it))?.name }
        if (path == null && selectedTreeUri == null) {
            Toast.makeText(this, "Set a path or folder first", Toast.LENGTH_SHORT).show()
            return
        }

        val input = android.widget.EditText(this).apply {
            hint = "Project name"
            setPadding(40, 20, 40, 20)
        }

        AlertDialog.Builder(this)
            .setTitle("New Project")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) createProject(name)
                else Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun createProject(name: String) {
        if (selectionMode == ServerConfig.MODE_PATH && directPath != null) {
            try {
                val dir = File(directPath!!, name)
                if (dir.mkdirs()) {
                    val index = File(dir, "index.php")
                    index.writeText("<?php\necho '<h1>Welcome to $name</h1>';\necho '<p>PHP: ' . phpversion() . '</p>';\n?>")
                    refreshProjectList()
                    Toast.makeText(this, "Created: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Failed to create: $name", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
            return
        }

        // SAF mode
        val treeUri = selectedTreeUri ?: return
        try {
            val treeDoc = DocumentFile.fromTreeUri(this, Uri.parse(treeUri)) ?: return
            lifecycleScope.launch {
                try {
                    val projectDir = treeDoc.createDirectory(name) ?: return@launch
                    val indexFile = projectDir.createFile("application/x-php", "index.php")
                    if (indexFile != null) {
                        contentResolver.openOutputStream(indexFile.uri)?.use { out ->
                            out.write("<?php\necho '<h1>Welcome to $name</h1>';\necho '<p>PHP: ' . phpversion() . '</p>';\n?>".toByteArray())
                        }
                    }
                    refreshProjectList()
                    Toast.makeText(this@MainActivity, "Created: $name", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun showProjectOptions(project: ProjectItem) {
        val options = arrayOf("Preview", "Edit index", "Browse Files", "Delete")
        AlertDialog.Builder(this)
            .setTitle(project.name)
            .setItems(options) { _, which ->
                try {
                    when (which) {
                        0 -> openPreview()
                        1 -> editProjectIndex(project)
                        2 -> browseProjectFiles(project)
                        3 -> confirmDeleteProject(project)
                    }
                } catch (e: Throwable) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun editProjectIndex(project: ProjectItem) {
        try {
            if (selectionMode == ServerConfig.MODE_PATH || directPath != null) {
                val dir = File(project.uri)
                val indexFile = dir.listFiles()?.firstOrNull {
                    it.name in listOf("index.php", "index.html", "index.htm")
                }
                if (indexFile != null) {
                    val intent = Intent(this, EditorActivity::class.java).apply {
                        putExtra(EditorActivity.EXTRA_FILE_URI, Uri.fromFile(indexFile).toString())
                        putExtra(EditorActivity.EXTRA_FILE_NAME, indexFile.name)
                        putExtra(EditorActivity.EXTRA_IS_DIRECT_PATH, true)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "No index file", Toast.LENGTH_SHORT).show()
                }
            } else {
                val doc = DocumentFile.fromTreeUri(this, Uri.parse(project.uri)) ?: return
                val indexFile = doc.listFiles().firstOrNull {
                    it.name in listOf("index.php", "index.html", "index.htm")
                }
                if (indexFile != null) {
                    val intent = Intent(this, EditorActivity::class.java).apply {
                        putExtra(EditorActivity.EXTRA_FILE_URI, indexFile.uri.toString())
                        putExtra(EditorActivity.EXTRA_FILE_NAME, indexFile.name)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "No index file", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun browseProjectFiles(project: ProjectItem) {
        try {
            if (selectionMode == ServerConfig.MODE_PATH || directPath != null) {
                val dir = File(project.uri)
                val files = dir.listFiles()?.filter { it.isFile }?.mapNotNull { it.name } ?: emptyList()
                if (files.isEmpty()) {
                    Toast.makeText(this, "No files", Toast.LENGTH_SHORT).show()
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Select file")
                    .setItems(files.toTypedArray()) { _, which ->
                        val file = dir.listFiles()?.filter { it.isFile }?.getOrNull(which)
                        if (file != null) {
                            startActivity(Intent(this, EditorActivity::class.java).apply {
                                putExtra(EditorActivity.EXTRA_FILE_URI, Uri.fromFile(file).toString())
                                putExtra(EditorActivity.EXTRA_FILE_NAME, file.name)
                                putExtra(EditorActivity.EXTRA_IS_DIRECT_PATH, true)
                            })
                        }
                    }
                    .show()
            } else {
                val doc = DocumentFile.fromTreeUri(this, Uri.parse(project.uri)) ?: return
                val files = doc.listFiles().filter { it.isFile }.mapNotNull { it.name }
                if (files.isEmpty()) {
                    Toast.makeText(this, "No files", Toast.LENGTH_SHORT).show()
                    return
                }
                AlertDialog.Builder(this)
                    .setTitle("Select file")
                    .setItems(files.toTypedArray()) { _, which ->
                        val file = doc.listFiles().filter { it.isFile }.getOrNull(which)
                        if (file != null) {
                            startActivity(Intent(this, EditorActivity::class.java).apply {
                                putExtra(EditorActivity.EXTRA_FILE_URI, file.uri.toString())
                                putExtra(EditorActivity.EXTRA_FILE_NAME, file.name)
                            })
                        }
                    }
                    .show()
            }
        } catch (e: Throwable) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDeleteProject(project: ProjectItem) {
        AlertDialog.Builder(this)
            .setTitle("Delete")
            .setMessage("Delete '${project.name}'?")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    if (selectionMode == ServerConfig.MODE_PATH || directPath != null) {
                        File(project.uri).deleteRecursively()
                    } else {
                        DocumentFile.fromTreeUri(this, Uri.parse(project.uri))?.delete()
                    }
                    refreshProjectList()
                    Toast.makeText(this, "Deleted", Toast.LENGTH_SHORT).show()
                } catch (e: Throwable) {
                    Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

data class ProjectItem(
    val name: String,
    val uri: String,
    val hasIndex: Boolean
)

package com.laragon.android.ui.editor

import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.laragon.android.R
import com.laragon.android.service.LaragonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_FILE_URI = "extra_file_uri"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_IS_DIRECT_PATH = "extra_is_direct_path"
    }

    private lateinit var editor: EditText
    private var fileUri: Uri? = null
    private var fileName: String? = null
    private var isDirectPath = false
    private var directFilePath: String? = null
    private var isModified = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_editor)
        } catch (e: Throwable) {
            android.util.Log.e("EditorActivity", "Layout inflation failed", e)
            finish()
            return
        }

        editor = findViewById(R.id.et_editor)

        // Set up toolbar as action bar
        try {
            val toolbar = findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar_editor)
            setSupportActionBar(toolbar)
        } catch (e: Throwable) {
            android.util.Log.e("EditorActivity", "Toolbar setup failed", e)
        }

        isDirectPath = intent.getBooleanExtra(EXTRA_IS_DIRECT_PATH, false)
        fileName = intent.getStringExtra(EXTRA_FILE_NAME) ?: "Untitled"

        if (isDirectPath) {
            val uriStr = intent.getStringExtra(EXTRA_FILE_URI)
            fileUri = uriStr?.let { Uri.parse(it) }
            directFilePath = fileUri?.path
        } else {
            fileUri = intent.getStringExtra(EXTRA_FILE_URI)?.let { Uri.parse(it) }
        }

        supportActionBar?.title = fileName
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isModified) confirmSaveAndExit() else finish()
            }
        })

        loadFileContent()

        editor.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                isModified = true
                supportActionBar?.title = "* $fileName"
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        return try {
            menuInflater.inflate(R.menu.menu_editor, menu)
            true
        } catch (e: Throwable) {
            super.onCreateOptionsMenu(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            when (item.itemId) {
                android.R.id.home -> { if (isModified) confirmSaveAndExit() else finish(); true }
                R.id.action_save -> { saveFile(); true }
                R.id.action_save_and_preview -> { saveFileAndPreview(); true }
                else -> super.onOptionsItemSelected(item)
            }
        } catch (e: Throwable) {
            super.onOptionsItemSelected(item)
        }
    }

    private fun loadFileContent() {
        lifecycleScope.launch {
            try {
                val content = withContext(Dispatchers.IO) {
                    if (isDirectPath && directFilePath != null) {
                        val f = File(directFilePath!!)
                        if (f.exists()) f.readText() else ""
                    } else {
                        val uri = fileUri ?: return@withContext ""
                        contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                    }
                }
                editor.setText(content)
                isModified = false
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, "Error loading: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFile() {
        val content = editor.text.toString()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isDirectPath && directFilePath != null) {
                        File(directFilePath!!).writeText(content)
                    } else {
                        val uri = fileUri ?: throw IllegalStateException("No file URI")
                        contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(content.toByteArray())
                            out.flush()
                        } ?: throw IllegalStateException("Cannot open output stream")
                    }
                }
                isModified = false
                supportActionBar?.title = fileName
                Toast.makeText(this@EditorActivity, "Saved", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, "Error saving: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFileAndPreview() {
        val content = editor.text.toString()
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if (isDirectPath && directFilePath != null) {
                        File(directFilePath!!).writeText(content)
                    } else {
                        val uri = fileUri ?: return@withContext
                        contentResolver.openOutputStream(uri, "wt")?.use { out ->
                            out.write(content.toByteArray())
                            out.flush()
                        }
                    }
                }
                isModified = false
                supportActionBar?.title = fileName
                val running = LaragonService.getServerState()?.value == LaragonService.ServerState.RUNNING
                Toast.makeText(this@EditorActivity, if (running) "Saved! Preview updated." else "Saved. Start server to preview.", Toast.LENGTH_SHORT).show()
            } catch (e: Throwable) {
                Toast.makeText(this@EditorActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun confirmSaveAndExit() {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Unsaved Changes")
            .setMessage("Save before leaving?")
            .setPositiveButton("Save") { _, _ -> saveFile(); finish() }
            .setNegativeButton("Discard") { _, _ -> finish() }
            .setNeutralButton("Cancel", null)
            .show()
    }
}

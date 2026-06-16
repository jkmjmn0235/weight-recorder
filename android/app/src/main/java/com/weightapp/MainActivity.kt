package com.weightapp

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var saveTreeUri: Uri? = null

    private val dirPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            saveTreeUri = it
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            getPreferences(Context.MODE_PRIVATE)
                .edit()
                .putString(SAVE_URI_KEY, it.toString())
                .apply()
            val displayPath = getDisplayPath(it)
            webView.evaluateJavascript("onSavePathChanged('$displayPath')", null)
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val content = buildString {
                    contentResolver.openInputStream(it)?.use { input ->
                        BufferedReader(InputStreamReader(input, "UTF-8")).use { reader ->
                            var line: String? = reader.readLine()
                            while (line != null) {
                                append(line)
                                append('\n')
                                line = reader.readLine()
                            }
                        }
                    }
                }
                val escaped = content
                    .replace("\\", "\\\\")
                    .replace("'", "\\'")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                webView.evaluateJavascript("onFilePicked('$escaped')", null)
            } catch (e: Exception) {
                webView.evaluateJavascript(
                    "showToast('读取文件失败')", null
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val uriStr = getPreferences(Context.MODE_PRIVATE)
            .getString(SAVE_URI_KEY, null)
        saveTreeUri = uriStr?.let { Uri.parse(it) }

        webView = findViewById(R.id.webView)
        setupWebView()
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        webView.addJavascriptInterface(AndroidBridge(), "FileBridge")
        webView.webChromeClient = WebChromeClient()
        webView.webViewClient = WebViewClient()
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun getDisplayPath(uri: Uri): String {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return uri.toString()
        }
        val parts = docId.split(":")
        return if (parts.size >= 2) {
            when (parts[0]) {
                "primary" -> "内部存储 / ${parts[1]}"
                else -> "${parts[0]} / ${parts.getOrElse(1) { "" }}"
            }
        } else {
            docId
        }
    }

    inner class AndroidBridge {

        @JavascriptInterface
        fun nativeGetItem(key: String): String {
            return try {
                val file = File(this@MainActivity.filesDir, "${key}.json")
                if (file.exists()) file.readText() else ""
            } catch (e: Exception) { "" }
        }

        @JavascriptInterface
        fun nativeSetItem(key: String, value: String) {
            try {
                val file = File(this@MainActivity.filesDir, "${key}.json")
                file.writeText(value)
            } catch (_: Exception) { }
        }

        @JavascriptInterface
        fun getStoragePath(): String {
            return this@MainActivity.filesDir.absolutePath
        }

        @JavascriptInterface
        fun pickSaveDirectory() {
            runOnUiThread {
                dirPickerLauncher.launch(null)
            }
        }

        @JavascriptInterface
        fun getSavePath(): String {
            return saveTreeUri?.let { getDisplayPath(it) } ?: ""
        }

        @JavascriptInterface
        fun saveFile(filename: String, content: String, mimeType: String): Boolean {
            return try {
                val treeUri = saveTreeUri ?: return false
                val dir = DocumentFile.fromTreeUri(this@MainActivity, treeUri) ?: return false
                val existing = dir.listFiles().find { it.name == filename }
                existing?.delete()
                val file = dir.createFile(mimeType, filename) ?: return false
                contentResolver.openOutputStream(file.uri)?.use { os ->
                    os.write(content.toByteArray(Charsets.UTF_8))
                    os.flush()
                }
                true
            } catch (e: Exception) {
                false
            }
        }

        @JavascriptInterface
        fun pickFile() {
            runOnUiThread {
                filePickerLauncher.launch(arrayOf("application/json"))
            }
        }
    }

    companion object {
        private const val SAVE_URI_KEY = "save_tree_uri"
    }
}

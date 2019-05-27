/*
 * FreeOTP
 *
 * Authors: Nathaniel McCallum <npmccallum@redhat.com>
 *
 * Copyright (C) 2013  Nathaniel McCallum, Red Hat
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
 * Portions Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fedorahosted.freeotp.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.appcompat.app.AppCompatActivity
import android.widget.Toast

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager.LayoutParams
import android.widget.SearchView
import androidx.appcompat.app.AppCompatDelegate
import androidx.databinding.DataBindingUtil
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import dagger.android.AndroidInjection

import kotlinx.coroutines.launch
import org.fedorahosted.freeotp.R
import org.fedorahosted.freeotp.databinding.MainBinding
import org.fedorahosted.freeotp.util.Settings
import org.fedorahosted.freeotp.util.UiLifecycleScope
import org.fedorahosted.freeotp.token.TokenPersistence
import org.fedorahosted.freeotp.util.ImportExportUtil
import java.lang.Exception
import javax.inject.Inject

private const val CAMERA_PERMISSION_REQUEST = 10
private const val READ_JSON_REQUEST_CODE = 42
private const val WRITE_JSON_REQUEST_CODE = 43
private const val READ_KEY_URI_REQUEST_CODE = 44
private const val WRITE_KEY_URI_REQUEST_CODE = 45
private const val ADD_TOKEN_REQUEST_CODE = 46
private const val SCAN_TOKEN_REQUEST_CODE = 47

class MainActivity : AppCompatActivity() {
    val TAG = MainActivity::class.java.simpleName

    @Inject lateinit var importFromUtil: ImportExportUtil
    @Inject lateinit var settings: Settings
    @Inject lateinit var uiLifecycleScope: UiLifecycleScope
    @Inject lateinit var tokenPersistence: TokenPersistence

    private lateinit var tokenListAdapter: TokenListAdapter
    private lateinit var binding: MainBinding
    private var searchQuery = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidInjection.inject(this)
        lifecycle.addObserver(uiLifecycleScope)

        onNewIntent(intent)
        binding = DataBindingUtil.setContentView(this, R.layout.main)

        tokenListAdapter = TokenListAdapter(this, tokenPersistence)
        binding.tokenList.adapter = tokenListAdapter
        binding.tokenList.layoutManager = LinearLayoutManager(this)
        ItemTouchHelper(TokenTouchCallback(tokenListAdapter, tokenPersistence)).attachToRecyclerView(binding.tokenList)

        setSupportActionBar(binding.toolbar)

        binding.search.setOnQueryTextListener(object: SearchView.OnQueryTextListener, androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                searchQuery = query ?: ""
                refreshTokenList(searchQuery)
                return true
            }

            override fun onQueryTextChange(query: String?): Boolean {
                searchQuery = query ?: ""
                refreshTokenList(searchQuery)
                return true
            }

        })

        // Don't permit screenshots since these might contain OTP codes.
        window.setFlags(LayoutParams.FLAG_SECURE, LayoutParams.FLAG_SECURE)
    }

    override fun onResume() {
        super.onResume()
        refreshTokenList(searchQuery)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        menu.findItem(R.id.use_dark_theme).isChecked = settings.darkMode
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.action_scan -> {
                scanQRCode()
                return true
            }

            R.id.action_add -> {
                startActivityForResult(Intent(this, AddActivity::class.java), ADD_TOKEN_REQUEST_CODE)
                return true
            }

            R.id.action_import_json -> {
                performFileSearch(READ_JSON_REQUEST_CODE)
                return true
            }

            R.id.action_import_key_uri -> {
                performFileSearch(READ_KEY_URI_REQUEST_CODE)
                return true
            }

            R.id.action_export_json -> {
                createFile("application/json", "freeotp-backup.json", WRITE_JSON_REQUEST_CODE)
                return true
            }

            R.id.action_export_key_uri -> {
                createFile("application/json", "freeotp-backup.txt", WRITE_KEY_URI_REQUEST_CODE)
                return true
            }

            R.id.use_dark_theme -> {
                settings.darkMode = !settings.darkMode
                if (settings.darkMode) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                }
                recreate()
                return true
            }

            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                return true
            }
        }

        return false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data
        if (uri != null) {
            try {
                tokenPersistence.addFromUriString(uri.toString())
            } catch (e: Exception) {
                Snackbar.make(binding.root, R.string.invalid_token_uri_received, Snackbar.LENGTH_SHORT)
                        .show()
            }
        }
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int,
                                         resultData: Intent?) {

        if (resultCode != Activity.RESULT_OK) {
            return
        }


        uiLifecycleScope.launch {
            when (requestCode) {
                WRITE_JSON_REQUEST_CODE -> {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportJsonFile(uri)
                    Snackbar.make(binding.root, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }

                READ_JSON_REQUEST_CODE -> {
                    val uri = resultData?.data ?: return@launch
                    try {
                        importFromUtil.importJsonFile(uri)
                        refreshTokenList(searchQuery)
                        Snackbar.make(binding.root, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Import JSON failed", e)
                        Snackbar.make(binding.root, R.string.import_json_failed_text, Snackbar.LENGTH_SHORT)
                                .show()
                    }
                }

                WRITE_KEY_URI_REQUEST_CODE -> {
                    val uri = resultData?.data ?: return@launch
                    importFromUtil.exportKeyUriFile(uri)
                    Snackbar.make(binding.root, R.string.export_succeeded_text, Snackbar.LENGTH_SHORT)
                            .show()
                }

                READ_KEY_URI_REQUEST_CODE -> {
                    val uri = resultData?.data ?: return@launch
                    try {
                        importFromUtil.importKeyUriFile(uri)
                        refreshTokenList(searchQuery)
                        Snackbar.make(binding.root, R.string.import_succeeded_text, Snackbar.LENGTH_SHORT)
                                .show()
                    } catch (e: Exception) {
                        Log.e(TAG, "Import Key uri failed", e)
                        Snackbar.make(binding.root, R.string.import_key_uri_failed_text, Snackbar.LENGTH_SHORT)
                                .show()
                    }
                }

                ADD_TOKEN_REQUEST_CODE -> {
                    binding.tokenList.scrollToPosition(0)
                }

                SCAN_TOKEN_REQUEST_CODE -> {
                    binding.tokenList.scrollToPosition(0)
                }
            }
        }

    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        when (requestCode) {
            CAMERA_PERMISSION_REQUEST -> {
                // If request is cancelled, the result arrays are empty.
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startActivity(Intent(this, ScanActivity::class.java))
                    overridePendingTransition(R.anim.fadein, R.anim.fadeout)
                } else {
                    Toast.makeText(this, R.string.camera_permission_denied_text, Toast.LENGTH_LONG).show()
                }
                return
            }
        }
    }

    /**
     * Fires an intent to spin up the "file chooser" UI and select an image.
     */
    private fun performFileSearch(requestCode: Int) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"

        startActivityForResult(intent, requestCode)
    }

    private fun createFile(mimeType: String, fileName: String, requestCode: Int) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT)

        // Filter to only show results that can be "opened", such as
        // a file (as opposed to a list of contacts or timezones).
        intent.addCategory(Intent.CATEGORY_OPENABLE)

        // Create a file with the requested MIME type.
        intent.type = mimeType
        intent.putExtra(Intent.EXTRA_TITLE, fileName)
        startActivityForResult(intent, requestCode)
    }

    private fun scanQRCode() {
        if (ContextCompat.checkSelfPermission(this,
                        Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.CAMERA),
                    CAMERA_PERMISSION_REQUEST)
        } else {
            startActivityForResult(Intent(this, ScanActivity::class.java), SCAN_TOKEN_REQUEST_CODE)
            overridePendingTransition(R.anim.fadein, R.anim.fadeout)
        }
    }

    private fun refreshTokenList(queryString: String) {
        val tokens = if(queryString.isEmpty()) {
            tokenPersistence.getTokens()
        } else {
            tokenPersistence.getTokens().filter {token ->
                token.label.contains(queryString, true) || token.issuer.contains(queryString, true)
            }
        }

        tokenListAdapter.submitList(tokens)

        if (tokens.isEmpty()) {
            binding.empty.visibility = View.VISIBLE
            binding.tokenList.visibility = View.GONE
        } else {
            binding.empty.visibility = View.GONE
            binding.tokenList.visibility = View.VISIBLE
        }
    }
}

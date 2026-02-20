package com.techted89.gameex

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessSelectorActivity : AppCompatActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission is required.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_process_selector)

        checkOverlayPermission()

        val recycler = findViewById<RecyclerView>(R.id.recycler_processes)
        recycler.layoutManager = LinearLayoutManager(this)

        val fabRefresh = findViewById<FloatingActionButton>(R.id.fab_refresh)
        fabRefresh.setOnClickListener {
            loadProcesses(recycler)
        }

        loadProcesses(recycler)
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun loadProcesses(recycler: RecyclerView) {
        CoroutineScope(Dispatchers.IO).launch {
            // Need root to see all processes ideally, but basic ps might work for now
            // Or requesting root first
            RootUtils.requestRoot()
            val processes = RootUtils.getRunningProcesses()

            withContext(Dispatchers.Main) {
                recycler.adapter = ProcessAdapter(processes) { process ->
                    launchOverlayService(process)
                }
            }
        }
    }

    private fun launchOverlayService(process: ProcessInfo) {
        val intent = Intent(this, FloatingOverlayService::class.java)
        intent.putExtra("PID", process.pid)
        intent.putExtra("PNAME", process.name)
        startService(intent)
        // Optionally finish() or minimize app
        moveTaskToBack(true)
    }
}

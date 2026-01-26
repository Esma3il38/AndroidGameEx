package com.techted89.gameex

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import android.view.inputmethod.EditorInfo
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var iconView: View
    private lateinit var dashboardView: View

    // Layout Params storage
    private lateinit var iconParams: WindowManager.LayoutParams
    private lateinit var dashboardParams: WindowManager.LayoutParams

    private var targetPid: Int = -1
    private var isDashboardVisible = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPid = intent?.getIntExtra("PID", -1) ?: -1

        // Ensure we run as Foreground to prevent killing
        startForeground(1, NotificationCompat.Builder(this, "overlay_channel")
            .setContentTitle("Memory Editor Active")
            .setContentText("Attached to PID: $targetPid")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build())

        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // 1. Inflate Views
        val inflater = LayoutInflater.from(this)
        iconView = inflater.inflate(R.layout.overlay_icon, null)
        dashboardView = inflater.inflate(R.layout.overlay_dashboard, null)

        // 2. Initialize Layout Params (Icon State)
        // TYPE_APPLICATION_OVERLAY is required for Android 8.0+
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        iconParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            // FLAG_NOT_FOCUSABLE allows touch events to pass through to the game
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        // Initial position
        iconParams.gravity = Gravity.TOP or Gravity.START
        iconParams.x = 0
        iconParams.y = 100

        // 3. Initialize Dashboard Params
        dashboardParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            // FLAG_DIM_BEHIND darkens the game background
            WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            dimAmount = 0.5f
            gravity = Gravity.CENTER
        }

        // 4. Setup Listeners
        setupTouchDrag()
        setupDashboardLogic()

        // 5. Add Icon initially
        windowManager.addView(iconView, iconParams)
    }

    private fun setupTouchDrag() {
        // Logic to drag the icon around the screen
        iconView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = iconParams.x
                        initialY = iconParams.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        iconParams.x = initialX + (event.rawX - initialTouchX).toInt()
                        iconParams.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(iconView, iconParams)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        // Detect "Click" vs "Drag"
                        val diffX = (event.rawX - initialTouchX).toInt()
                        val diffY = (event.rawY - initialTouchY).toInt()

                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            showDashboard()
                        }
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun setupDashboardLogic() {
        val btnMinimize = dashboardView.findViewById<ImageButton>(R.id.btn_minimize)
        val btnScan = dashboardView.findViewById<Button>(R.id.btn_scan)
        val etSearchValue = dashboardView.findViewById<EditText>(R.id.et_search_value)
        val rvResults = dashboardView.findViewById<RecyclerView>(R.id.rv_results)

        // Setup RecyclerView
        rvResults.layoutManager = LinearLayoutManager(this)
        val adapter = MemoryResultAdapter()
        rvResults.adapter = adapter

        btnMinimize.setOnClickListener {
            showIcon()
        }

        fun performScan() {
            val valueStr = etSearchValue.text.toString()
            if (valueStr.isNotEmpty()) {
                Toast.makeText(this, "Scanning for $valueStr in PID $targetPid...", Toast.LENGTH_SHORT).show()
                // Mock results for now since we can't reliably scan in this env
                val mockResults = listOf(
                    MemoryResult(0x12345678, valueStr),
                    MemoryResult(0xABCDEF00, valueStr),
                    MemoryResult(0x88776655, valueStr)
                )
                adapter.updateData(mockResults)
            }
        }

        btnScan.setOnClickListener {
            performScan()
        }

        etSearchValue.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                performScan()
                true
            } else {
                false
            }
        }
    }

    private fun showDashboard() {
        if (isDashboardVisible) return

        // Remove Icon, Add Dashboard
        windowManager.removeView(iconView)

        // Important: Dashboard must be Focusable for EditText to work
        // We do NOT add FLAG_NOT_FOCUSABLE here
        windowManager.addView(dashboardView, dashboardParams)

        isDashboardVisible = true
    }

    private fun showIcon() {
        if (!isDashboardVisible) return

        // Remove Dashboard, Add Icon
        windowManager.removeView(dashboardView)
        windowManager.addView(iconView, iconParams)

        isDashboardVisible = false
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isDashboardVisible) windowManager.removeView(dashboardView)
        else windowManager.removeView(iconView)
    }
}

package com.techted89.gameex

import android.app.Service
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private var floatingView: View? = null
    private var dashboardView: View? = null
    private var isDashboardOpen = false
    private var targetPid = -1
    private var targetName = "Unknown"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            targetPid = it.getIntExtra("PID", -1)
            targetName = it.getStringExtra("PNAME") ?: "Unknown"
        }

        if (floatingView == null) {
            setupFloatingIcon()
        }

        return START_STICKY
    }

    private fun setupFloatingIcon() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 0
        params.y = 100

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_icon, null)

        val icon = floatingView!!.findViewById<ImageView>(R.id.icon_floating_head)

        icon.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0f
            private var initialTouchY = 0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = (event.rawX - initialTouchX).toInt()
                        val diffY = (event.rawY - initialTouchY).toInt()
                        if (Math.abs(diffX) < 10 && Math.abs(diffY) < 10) {
                            openDashboard()
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun openDashboard() {
        if (isDashboardOpen) return

        // Remove icon
        if (floatingView != null) windowManager.removeView(floatingView)

        // Add dashboard
        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_DIM_BEHIND, // Focusable implicitly if not FLAG_NOT_FOCUSABLE? No, we need input
            PixelFormat.TRANSLUCENT
        )
        // Ensure input is possible
        // FLAG_NOT_TOUCH_MODAL allow outside clicks to pass through?
        // We want to type in EditText, so we need focus.

        params.dimAmount = 0.5f
        params.gravity = Gravity.CENTER

        dashboardView = LayoutInflater.from(this).inflate(R.layout.layout_overlay_dashboard, null)

        val targetText = dashboardView!!.findViewById<TextView>(R.id.text_target_name)
        targetText.text = "Attached to: $targetName ($targetPid)"

        val btnHide = dashboardView!!.findViewById<Button>(R.id.btn_hide)
        btnHide.setOnClickListener {
            closeDashboard()
        }

        val btnClose = dashboardView!!.findViewById<Button>(R.id.btn_close)
        btnClose.setOnClickListener {
            stopSelf()
        }

        val btnScan = dashboardView!!.findViewById<Button>(R.id.btn_scan)
        val inputSearch = dashboardView!!.findViewById<EditText>(R.id.input_search)
        val recycler = dashboardView!!.findViewById<RecyclerView>(R.id.recycler_results)
        recycler.layoutManager = LinearLayoutManager(this)

        btnScan.setOnClickListener {
             val query = inputSearch.text.toString()
             performScan(query, recycler)
        }

        windowManager.addView(dashboardView, params)
        isDashboardOpen = true
    }

    private fun closeDashboard() {
        if (!isDashboardOpen) return

        if (dashboardView != null) windowManager.removeView(dashboardView)
        setupFloatingIcon() // Re-add icon
        isDashboardOpen = false
    }

    private fun performScan(query: String, recycler: RecyclerView) {
        // Temporary scan logic as requested
        CoroutineScope(Dispatchers.IO).launch {
             // For now, just test the Native Read function on a dummy address or existing logic
             // We'll simulate finding results

             // Real implementation would look like:
             // val results = NativeScanner.search(targetPid, query)

             // Test Native Read (Phase 2 feature)
             // Arbitrary address just to check JNI call doesn't crash
             try {
                NativeScanner.readMemory(targetPid, 0x123456, 4)
             } catch (e: Exception) {
                 e.printStackTrace()
             }

             val dummyResults = listOf(
                 Pair("0x12345678", query),
                 Pair("0x87654321", query)
             )

             withContext(Dispatchers.Main) {
                 recycler.adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
                     override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                         val view = LayoutInflater.from(parent.context).inflate(R.layout.row_memory_result, parent, false)
                         return object : RecyclerView.ViewHolder(view) {}
                     }

                     override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                         val addrView = holder.itemView.findViewById<TextView>(R.id.text_address)
                         val valView = holder.itemView.findViewById<TextView>(R.id.text_value)
                         addrView.text = dummyResults[position].first
                         valView.text = dummyResults[position].second
                     }

                     override fun getItemCount() = dummyResults.size
                 }
             }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (floatingView != null && !isDashboardOpen) windowManager.removeView(floatingView)
        if (dashboardView != null && isDashboardOpen) windowManager.removeView(dashboardView)
    }
}

package com.friends.privatevpn

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.*
import android.text.InputType
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {

    private val bg = Color.rgb(8, 12, 22)
    private val surface = Color.rgb(17, 25, 40)
    private val surface2 = Color.rgb(25, 36, 55)
    private val blue = Color.rgb(40, 125, 255)
    private val cyan = Color.rgb(65, 190, 255)
    private val white = Color.WHITE
    private val muted = Color.rgb(145, 160, 185)

    private lateinit var root: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var proxyCount: TextView
    private lateinit var connectButton: TextView
    private lateinit var proxyContainer: LinearLayout

    private data class Proxy(
        val host: String,
        val port: String,
        val username: String,
        val password: String
    )

    private val proxies = mutableListOf<Proxy>()
    private var selectedProxy = -1
    private var connected = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        loadProxies()
        buildUI()
        refreshProxies()
        animateScreen()
    }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun rounded(
        color: Int,
        radius: Int = 18,
        stroke: Int = 0,
        strokeColor: Int = Color.TRANSPARENT
    ) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun text(
        value: String,
        size: Float,
        color: Int = white,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(surface, 20, 1, 0xFF26364F.toInt())
        }

    private fun buildUI() {

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(dp(16), dp(10), dp(16), dp(24))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(12), dp(4), dp(24))
        }

        val logo = TextView(this).apply {
            text = "RO"
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            gravity = Gravity.CENTER
            background = rounded(blue, 16)
        }

        header.addView(logo, LinearLayout.LayoutParams(dp(52), dp(52)))

        val titleBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }

        titleBox.addView(text("VPN RO", 22f, white, true))
        titleBox.addView(text("PRIVATE CONNECTION", 10f, muted, true))
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))

        val badge = text("● PRIVATE", 11f, 0xFF61D99A.toInt(), true)
        header.addView(badge)
        content.addView(header)

        val statusCard = card().apply {
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(22), dp(18), dp(22))
        }

        statusCard.addView(text("YOUR CONNECTION", 11f, muted, true))

        val shield = TextView(this).apply {
            text = "◈"
            textSize = 65f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(0, dp(10), 0, dp(8))
        }
        statusCard.addView(shield, LinearLayout.LayoutParams(-1, dp(100)))

        statusText = text("NOT CONNECTED", 19f, white, true)
        statusCard.addView(statusText)

        val subtitle = text("Your private connection is waiting", 12f, muted)
        subtitle.setPadding(0, dp(8), 0, dp(18))
        statusCard.addView(subtitle)

        connectButton = TextView(this).apply {
            text = "CONNECT"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(white)
            background = rounded(blue, 16)
            elevation = dp(5).toFloat()
        }

        statusCard.addView(connectButton, LinearLayout.LayoutParams(-1, dp(56)))
        connectButton.setOnClickListener {
            animateClick(connectButton)
            showConnectionMessage()
        }

        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })

        val infoCard = card()
        infoCard.addView(text("CONNECTION INFORMATION", 12f, muted, true))

        val infoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, dp(4))
        }

        val ipBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        ipBox.addView(text("CURRENT IP", 11f, muted))
        ipText = text("Not connected", 15f, white, true)
        ipBox.addView(ipText)
        infoRow.addView(ipBox, LinearLayout.LayoutParams(0, -2, 1f))

        val locationBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        locationBox.addView(text("LOCATION", 11f, muted))
        locationBox.addView(text("Unknown", 15f, white, true))
        infoRow.addView(locationBox)
        infoCard.addView(infoRow)

        content.addView(infoCard, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(16)
        })

        val proxyHeader = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), dp(12))
        }

        val proxyTitle = text("MY PROXIES", 17f, white, true)
        proxyHeader.addView(proxyTitle, LinearLayout.LayoutParams(0, -2, 1f))
        proxyCount = text("0 PROXIES", 11f, cyan, true)
        proxyHeader.addView(proxyCount)
        content.addView(proxyHeader)

        val addButton = TextView(this).apply {
            text = "＋   ADD PRIVATE PROXY"
            gravity = Gravity.CENTER
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(white)
            background = rounded(0xFF173B70.toInt(), 15, 1, blue)
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener {
                animateClick(this)
                showAddProxyDialog()
            }
        }

        content.addView(addButton, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(14)
        })

        proxyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(proxyContainer)

        val footer = text("VPN RO  •  PRIVATE ACCESS", 10f, muted).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(28), 0, dp(12))
        }
        content.addView(footer)

        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, -1))
        setContentView(root)
    }

    private fun showAddProxyDialog() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(10), dp(20), dp(4))
        }

        fun field(hint: String): EditText {
            return EditText(this).apply {
                this.hint = hint
                setSingleLine(true)
                setTextColor(white)
                setHintTextColor(muted)
                textSize = 14f
                background = rounded(surface2, 12)
                setPadding(dp(14), 0, dp(14), 0)
                inputType = InputType.TYPE_CLASS_TEXT
            }
        }

        val host = field("IP Address")
        val port = field("Port")
        port.inputType = InputType.TYPE_CLASS_NUMBER
        val username = field("Username")
        val password = field("Password")
        password.inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_PASSWORD

        listOf(
            host to "IP Address",
            port to "Port",
            username to "Username",
            password to "Password"
        ).forEach { (edit, label) ->
            val title = text(label, 12f, muted, true)
            title.setPadding(0, dp(9), 0, dp(5))
            box.addView(title)
            box.addView(edit, LinearLayout.LayoutParams(-1, dp(48)))
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Add Private Proxy")
            .setView(box)
            .setPositiveButton("SAVE", null)
            .setNegativeButton("CANCEL", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val h = host.text.toString().trim()
                val p = port.text.toString().trim()
                val u = username.text.toString()
                val pw = password.text.toString()
                val portNumber = p.toIntOrNull()

                if (h.isBlank() || portNumber == null ||
                    portNumber !in 1..65535 || u.isBlank() || pw.isBlank()
                ) {
                    Toast.makeText(
                        this,
                        "Please enter valid proxy details",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }

                proxies.add(Proxy(h, p, u, pw))
                if (selectedProxy == -1) selectedProxy = 0
                saveProxies()
                refreshProxies()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun refreshProxies() {
        proxyContainer.removeAllViews()
        proxyCount.text = "${proxies.size} PROXIES"

        if (proxies.isEmpty()) {
            val empty = card().apply { gravity = Gravity.CENTER }
            empty.addView(text("◇", 34f, muted))
            empty.addView(text("NO PROXIES YET", 14f, white, true))
            empty.addView(text("Add your first private proxy", 12f, muted))
            proxyContainer.addView(empty, LinearLayout.LayoutParams(-1, dp(145)))
            return
        }

        proxies.forEachIndexed { index, proxy ->
            val item = card().apply {
                background = rounded(
                    if (selectedProxy == index) 0xFF142C4C.toInt() else surface,
                    17,
                    1,
                    if (selectedProxy == index) blue else 0xFF26364F.toInt()
                )
            }

            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val details = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            details.addView(text("●  ${proxy.host}:${proxy.port}", 14f, white, true))
            details.addView(text("HTTP / HTTPS • Private proxy", 11f, muted).apply {
                setPadding(0, dp(5), 0, 0)
            })
            row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

            val select = TextView(this).apply {
                text = if (selectedProxy == index) "SELECTED" else "SELECT"
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selectedProxy == index) cyan else white)
                setPadding(dp(9), dp(10), dp(9), dp(10))
                background = rounded(surface2, 10)
                setOnClickListener {
                    selectedProxy = index
                    saveProxies()
                    refreshProxies()
                }
            }
            row.addView(select)
            item.addView(row)

            val delete = TextView(this).apply {
                text = "REMOVE PROXY"
                textSize = 10f
                setTextColor(0xFFFF7777.toInt())
                gravity = Gravity.END
                setPadding(0, dp(14), 0, 0)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Remove Proxy")
                        .setMessage("Remove this proxy from your list?")
                        .setPositiveButton("REMOVE") { _, _ ->
                            proxies.removeAt(index)
                            selectedProxy = when {
                                proxies.isEmpty() -> -1
                                selectedProxy >= proxies.size -> proxies.size - 1
                                selectedProxy > index -> selectedProxy - 1
                                else -> selectedProxy
                            }
                            saveProxies()
                            refreshProxies()
                        }
                        .setNegativeButton("CANCEL", null)
                        .show()
                }
            }
            item.addView(delete)

            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(10)
            proxyContainer.addView(item, params)

            item.alpha = 0f
            item.translationY = dp(12).toFloat()
            item.animate().alpha(1f).translationY(0f)
                .setDuration(250).setStartDelay(index * 45L).start()
        }
    }

    private fun showConnectionMessage() {
        if (selectedProxy < 0 || selectedProxy >= proxies.size) {
            Toast.makeText(this, "Add and select a proxy first", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("VPN engine not installed")
            .setMessage(
                "Your proxy is saved and selected. " +
                    "The real VPN tunnel will be added in the next stage."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun saveProxies() {
        val array = JSONArray()
        proxies.forEach {
            val item = JSONObject()
            item.put("host", it.host)
            item.put("port", it.port)
            item.put("username", it.username)
            item.put("password", it.password)
            array.put(item)
        }

        getSharedPreferences("vpn_ro", MODE_PRIVATE)
            .edit()
            .putString("proxies", array.toString())
            .putInt("selected", selectedProxy)
            .apply()
    }

    private fun loadProxies() {
        try {
            val prefs = getSharedPreferences("vpn_ro", MODE_PRIVATE)
            val array = JSONArray(prefs.getString("proxies", "[]"))
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                proxies.add(
                    Proxy(
                        item.getString("host"),
                        item.getString("port"),
                        item.getString("username"),
                        item.getString("password")
                    )
                )
            }

            selectedProxy = prefs.getInt("selected", -1)
            if (selectedProxy !in proxies.indices) {
                selectedProxy = if (proxies.isEmpty()) -1 else 0
            }
        } catch (_: Exception) {
            proxies.clear()
            selectedProxy = -1
        }
    }

    private fun animateClick(view: View) {
        view.animate().scaleX(0.96f).scaleY(0.96f)
            .setDuration(90)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            .start()
    }

    private fun animateScreen() {
        root.alpha = 0f
        root.translationY = dp(15).toFloat()
        root.animate().alpha(1f).translationY(0f)
            .setDuration(450)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }
}

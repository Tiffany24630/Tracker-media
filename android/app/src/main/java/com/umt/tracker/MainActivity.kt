package com.umt.tracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
import android.content.Intent
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private lateinit var prefs: SharedPreferences
    private var token: String? = null
    private var userEmail: String? = null
    private var userName: String? = null
    private var userAvatar: String? = null
    private var notifyNewReleases: Boolean = true
    private val lastRecommendationIds = mutableSetOf<String>()

    // Navegación
    private var activeTab: String = "library" // "library", "explore", "recs", "settings"
    private var selectedCategory: String = "all" // "all", "anime", "manga", "movie", "series", "book"
    private var currentFilter: String = "all" // "all", "in_progress", "planned", "completed"

    // Filtros persistentes para evitar pérdida al cambiar de pestaña
    private var activeSearchFilters = JSONObject()
    private var activeLibraryFilters = JSONObject()
    
    // Filtros de géneros (+ incluir, - excluir)
    private val includeGenres = mutableSetOf<String>()
    private val excludeGenres = mutableSetOf<String>()

    // Mismo catálogo de filtros que la web. Se comparte para todas las
    // categorías porque los proveedores pueden clasificar una obra con más
    // de una familia (por ejemplo, musical + drama o juego + fantasía).
    private val webGenreFilters = listOf(
        "Acción", "Aventura", "Artes Marciales", "Superhéroes", "Espionaje", "Militar",
        "Wuxia", "Isekai", "Mecha", "Supervivencia", "Carreras", "Samurái",
        "Fantasía", "Alta Fantasía", "Fantasía Oscura", "Fantasía Urbana", "Ciencia Ficción",
        "Cyberpunk", "Distopía", "Viajes en el Tiempo", "Espacio", "Realidad Virtual", "Steampunk",
        "Drama", "Drama Romántico", "Tragedia", "Melodrama", "Recuentos de la vida",
        "Coming of Age", "Slice of Life", "Familiar", "Musical",
        "Misterio", "Suspense", "Thriller Psicológico", "Policial", "Detectivesco", "Crimen",
        "Noir", "Terror", "Terror Psicológico", "Gore", "Sobrenatural", "Vampiros", "Zombis",
        "Romance", "Comedia Romántica", "Romance Escolar", "Harem", "Reverse Harem",
        "Yaoi / BL", "Yuri / GL", "Triángulo Amoroso",
        "Comedia", "Comedia Negra", "Parodia", "Sátira", "Gag Humor", "Gastronomía",
        "Deportes", "Escolar", "Idols", "Ecchi",
        "Histórico", "Época", "Biográfico", "Documental", "Western", "Guerra", "Político",
        "Mitología", "Folclore", "Pop", "Rock", "Rock Alternativo", "Indie", "Metal", "Punk",
        "Hip-Hop / Rap", "Trap", "R&B / Soul", "Funk", "Jazz", "Blues", "Electrónica", "EDM",
        "House", "Techno", "Reggaetón", "Latina", "Salsa", "Bachata", "Cumbia", "K-Pop",
        "J-Pop", "Música Clásica", "Banda Sonora", "Lo-Fi", "Ambient", "Country", "Folk",
        "Reggae", "Gospel"
    )

    private val popularGenres = webGenreFilters

    private val presetAvatars = listOf(
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f98a.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f431.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f436.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f43c.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f981.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f42f.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f989.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f428.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f43a.png",
        "https://cdn.jsdelivr.net/gh/twitter/twemoji@14.0.2/assets/72x72/1f99d.png"
    )

    private fun defaultAnimalAvatar(seed: String): String = presetAvatars[(seed.hashCode() and Int.MAX_VALUE) % presetAvatars.size]

    private fun isLegacyDefaultAvatar(url: String?): Boolean =
        !url.isNullOrBlank() && url.contains("api.dicebear.com/") && (url.contains("/big-ears/") || url.contains("/shapes/"))

    private lateinit var rootContainer: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var tabLibraryBtn: Button
    private lateinit var tabExploreBtn: Button
    private lateinit var tabRecsBtn: Button
    private lateinit var tabSettingsBtn: Button
    private lateinit var categoryBar: LinearLayout
    private lateinit var mainBottomNav: LinearLayout
    private lateinit var mainCatScroll: HorizontalScrollView
    private var avatarPreview: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.parseColor("#0F0E13")

        prefs = getSharedPreferences("umt_settings", Context.MODE_PRIVATE)
        token = prefs.getString("token", null)
        userName = prefs.getString("user_name", null)
        userEmail = prefs.getString("user_email", null)
        userAvatar = prefs.getString("user_avatar", null)
        notifyNewReleases = prefs.getBoolean("notify_releases", true)

        if (token.isNullOrBlank()) {
            showAuthScreen()
        } else {
            showMainScreen()
        }
    }

    private fun getBaseUrl(): String {
        return prefs.getString("base_url", "local") ?: "local"
    }

    private fun setBaseUrl(url: String) {
        val finalUrl = if (url.trim().lowercase() == "local") "local" else {
            val clean = if (url.endsWith("/")) url.dropLast(1) else url
            if (!clean.endsWith("/api/v1")) "$clean/api/v1" else clean
        }
        prefs.edit().putString("base_url", finalUrl).apply()
    }

    private fun showServerDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Servidor API")

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 20)
        }

        val info = TextView(this).apply {
            text = "• Modo Autónomo Local: Escribe 'local'\n• Servidor Remoto o Docker: http://TU_IP_LOCAL:18000/api/v1"
            textSize = 13f
            setTextColor(Color.DKGRAY)
        }

        val input = EditText(this).apply {
            setText(getBaseUrl())
        }

        layout.addView(info)
        layout.addView(input)
        builder.setView(layout)

        builder.setPositiveButton("Guardar") { _, _ ->
            val newUrl = input.text.toString().trim()
            if (newUrl.isNotEmpty()) {
                setBaseUrl(newUrl)
                toast("Servidor: ${getBaseUrl()}")
            }
        }
        builder.setNegativeButton("Cancelar", null)
        builder.show()
    }

    private fun showAuthScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 40)
            setBackgroundColor(Color.parseColor("#0D0C11"))
            gravity = Gravity.CENTER
        }

        val title = TextView(this).apply {
            text = "Universal Media Tracker"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F2EB"))
            gravity = Gravity.CENTER
            setPadding(0, 40, 0, 10)
        }
        val subtitle = TextView(this).apply {
            text = "Tu anime, películas, series, manga y libros en un solo lugar."
            textSize = 14f
            setTextColor(Color.parseColor("#A8A5B2"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 40)
        }

        var isRegisterMode = false

        val nameField = EditText(this).apply {
            hint = "Tu nombre"
            setHintTextColor(Color.parseColor("#6C6977"))
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setPadding(30, 26, 30, 26)
            visibility = View.GONE
        }

        val emailField = EditText(this).apply {
            hint = "correo@ejemplo.com"
            setHintTextColor(Color.parseColor("#6C6977"))
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setPadding(30, 26, 30, 26)
            inputType = android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }

        val passField = EditText(this).apply {
            hint = "Contraseña (mínimo 8 caracteres)"
            setHintTextColor(Color.parseColor("#6C6977"))
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setPadding(30, 26, 30, 26)
            inputType = 129
        }

        val actionBtn = Button(this).apply {
            text = "Iniciar sesión"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#16131A"))
            background = makeRoundedDrawable("#F5F2EB", "#FFFFFF", 999)
        }

        val toggleModeBtn = Button(this).apply {
            text = "¿No tienes cuenta? Crear cuenta nueva"
            textSize = 13f
            setTextColor(Color.parseColor("#76E6D5"))
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                isRegisterMode = !isRegisterMode
                if (isRegisterMode) {
                    nameField.visibility = View.VISIBLE
                    actionBtn.text = "Crear cuenta"
                    text = "¿Ya tienes cuenta? Iniciar sesión"
                } else {
                    nameField.visibility = View.GONE
                    actionBtn.text = "Iniciar sesión"
                    text = "¿No tienes cuenta? Crear cuenta nueva"
                }
            }
        }

        actionBtn.setOnClickListener {
            val email = emailField.text.toString().trim()
            val pass = passField.text.toString()
            val name = nameField.text.toString().trim()

            if (email.isEmpty() || pass.isEmpty()) {
                toast("Por favor completa los campos requeridos")
                return@setOnClickListener
            }
            if (isRegisterMode && name.isEmpty()) {
                toast("Ingresa tu nombre para registrarte")
                return@setOnClickListener
            }
            performAuth(isRegisterMode, email, pass, name)
        }

        root.addView(title)
        root.addView(subtitle)

        val formBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(nameField, makeMarginParams(0, 10))
            addView(emailField, makeMarginParams(0, 10))
            addView(passField, makeMarginParams(0, 20))
            addView(actionBtn, makeMarginParams(0, 10))
            addView(toggleModeBtn, makeMarginParams(0, 10))
        }

        val serverBtn = Button(this).apply {
            text = "Servidor API"
            textSize = 11f
            setTextColor(Color.parseColor("#A8A5B2"))
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setOnClickListener { showServerDialog() }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(0, 60, 0, 0)
            }
        }

        root.addView(formBox)
        root.addView(serverBtn)

        setContentView(root)
    }

    private fun performAuth(register: Boolean, email: String, pass: String, name: String) {
        thread {
            try {
                val path = if (register) "/auth/register" else "/auth/login"
                val json = JSONObject().apply {
                    put("email", email)
                    put("password", pass)
                    if (register) put("display_name", name)
                }
                val (code, resp) = request("POST", path, json.toString())
                if (code in 200..299) {
                    val resObj = JSONObject(resp)
                    token = resObj.getString("access_token")
                    userEmail = email
                    userName = if (name.isNotEmpty()) name else prefs.getString("user_name", email.substringBefore("@"))
                    userAvatar = prefs.getString("user_avatar", null) ?: defaultAnimalAvatar(email)
                    if (getBaseUrl() != "local") {
                        val (profileCode, profileBody) = request("GET", "/auth/me", null)
                        if (profileCode in 200..299) {
                            val profile = JSONObject(profileBody)
                            userName = profile.optString("display_name", userName ?: email.substringBefore("@"))
                            userAvatar = profile.optString("avatar_url", userAvatar ?: defaultAnimalAvatar(email))
                        }
                    }
                    if (userAvatar.isNullOrBlank() || isLegacyDefaultAvatar(userAvatar)) {
                        userAvatar = defaultAnimalAvatar(email)
                    }

                    prefs.edit()
                        .putString("token", token)
                        .putString("user_email", userEmail)
                        .putString("user_name", userName)
                        .putString("user_avatar", userAvatar)
                        .apply()

                    runOnUiThread {
                        toast("¡Sesión iniciada!")
                        showMainScreen()
                    }
                } else {
                    val err = try {
                        val o = JSONObject(resp)
                        o.optString("detail", o.optJSONObject("error")?.optString("message") ?: resp)
                    } catch (_: Exception) { resp }
                    runOnUiThread { toast("Error: $err") }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("No se pudo conectar: ${e.localizedMessage}") }
            }
        }
    }

    private fun logout() {
        token = null
        prefs.edit().clear().apply()
        toast("Sesión cerrada")
        showAuthScreen()
    }

    private fun showMainScreen() {
        val root = RelativeLayout(this).apply {
            setBackgroundColor(Color.parseColor("#0F0E13"))
        }

        val header = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            val topPad = (48 * resources.displayMetrics.density).toInt()
            setPadding(24, topPad, 24, 20)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#15141B"))
        }

        val appTitle = TextView(this).apply {
            text = "UM Tracker"
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        header.addView(appTitle)
        
        val headerParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        headerParams.addRule(RelativeLayout.ALIGN_PARENT_TOP)
        root.addView(header, headerParams)

        mainCatScroll = HorizontalScrollView(this).apply {
            id = View.generateViewId()
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#0F0E13"))
            scrollBarSize = 0
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        categoryBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val categories = listOf(
            "all" to "Todos", 
            "anime" to "Anime", 
            "manga" to "Manga", 
            "movie" to "Cine", 
            "series" to "Series", 
            "book" to "Libros", 
            "music" to "Música",
            "comic" to "Comics",
            "game" to "Videojuegos"
        )
        for ((key, label) in categories) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                background = makeRoundedDrawable("#1A1824", "#2B283A", 999)
                setTextColor(Color.WHITE)
                setPadding(35, 0, 35, 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (36 * resources.displayMetrics.density).toInt()).apply { setMargins(8, 0, 8, 0) }
                setOnClickListener {
                    if (selectedCategory != key) lastRecommendationIds.clear()
                    selectedCategory = key
                    refreshCategoryButtons()
                    renderCurrentTab()
                }
            }
            categoryBar.addView(btn)
        }
        mainCatScroll.addView(categoryBar)
        val catParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        catParams.addRule(RelativeLayout.BELOW, header.id)
        root.addView(mainCatScroll, catParams)

        mainBottomNav = LinearLayout(this).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#15141B"))
            elevation = 20f
            val bottomPad = (50 * resources.displayMetrics.density).toInt()
            setPadding(0, 10, 0, bottomPad)
        }

        fun createNavBtn(label: String, tab: String, iconRes: Int): LinearLayout {
            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                isClickable = true
                isFocusable = true
                setPadding(0, 12, 0, 12)
                setOnClickListener { switchTab(tab) }
            }
            val icon = ImageView(this).apply {
                setImageResource(iconRes)
                layoutParams = LinearLayout.LayoutParams((24 * resources.displayMetrics.density).toInt(), (24 * resources.displayMetrics.density).toInt())
                setColorFilter(Color.parseColor("#A8A5B2"))
            }
            val txt = TextView(this).apply {
                text = label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.parseColor("#A8A5B2"))
                setPadding(0, 4, 0, 0)
            }
            container.addView(icon)
            container.addView(txt)
            container.tag = tab
            return container
        }

        mainBottomNav.addView(createNavBtn("Biblioteca", "library", android.R.drawable.ic_menu_sort_by_size))
        mainBottomNav.addView(createNavBtn("Explorar", "explore", android.R.drawable.ic_menu_search))
        mainBottomNav.addView(createNavBtn("Para ti", "recs", android.R.drawable.ic_menu_compass))
        mainBottomNav.addView(createNavBtn("Ajustes", "settings", android.R.drawable.ic_menu_preferences))

        val navParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        navParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM)
        root.addView(mainBottomNav, navParams)

        val scrollView = ScrollView(this).apply {
            id = View.generateViewId()
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 40)
        }
        scrollView.addView(contentContainer)
        
        val scrollParams = RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        scrollParams.addRule(RelativeLayout.BELOW, mainCatScroll.id)
        scrollParams.addRule(RelativeLayout.ABOVE, mainBottomNav.id)
        root.addView(scrollView, scrollParams)

        setContentView(root)
        switchTab("library")
    }

    private fun refreshCategoryButtons() {
        val categories = listOf("all", "anime", "manga", "movie", "series", "book", "music", "comic", "game")
        for (i in 0 until categoryBar.childCount) {
            val btn = categoryBar.getChildAt(i) as? Button ?: continue
            val key = categories.getOrNull(i) ?: continue
            val isSelected = selectedCategory == key
            btn.background = makeRoundedDrawable(if (isSelected) "#76E6D5" else "#1A1824", if (isSelected) "#76E6D5" else "#2B283A", 999)
            btn.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
        }
    }

    private fun switchTab(tab: String) {
        activeTab = tab
        if (::mainBottomNav.isInitialized) {
            for (i in 0 until mainBottomNav.childCount) {
                val container = mainBottomNav.getChildAt(i) as? LinearLayout ?: continue
                val isSelected = container.tag == tab
                val icon = container.getChildAt(0) as? ImageView
                val txt = container.getChildAt(1) as? TextView
                val color = if (isSelected) Color.parseColor("#A782FF") else Color.parseColor("#A8A5B2")
                icon?.setColorFilter(color)
                txt?.setTextColor(color)
            }
        }
        if (::mainCatScroll.isInitialized) {
            mainCatScroll.visibility = if (tab == "settings") View.GONE else View.VISIBLE
        }
        renderCurrentTab()
    }

    private fun renderCurrentTab() {
        contentContainer.removeAllViews()
        when (activeTab) {
            "library" -> loadLibraryTab()
            "explore" -> renderExploreTab()
            "recs" -> loadRecommendationsTab()
            "settings" -> renderSettingsTab()
        }
    }

    private fun loadLibraryTab() {
        contentContainer.removeAllViews()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val headerText = TextView(this).apply {
            text = if (selectedCategory == "all") "Mi Biblioteca" else "Mi Biblioteca · ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val advFilterBtn = TextView(this).apply {
            text = "FILTROS"
            textSize = 12f
            setTextColor(Color.parseColor("#76E6D5"))
            setPadding(20, 10, 0, 10)
            setOnClickListener {
                showAdvancedSearchFilters(activeLibraryFilters) { filters ->
                    activeLibraryFilters = filters
                    loadLibraryTabWithFilters(filters)
                }
            }
        }

        headerRow.addView(headerText)
        headerRow.addView(advFilterBtn)
        contentContainer.addView(headerRow)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val filters = listOf("all" to "Todos", "in_progress" to "En proceso", "planned" to "Pendiente", "completed" to "Terminado", "wishlist" to "Deseos")
        for ((fKey, fLabel) in filters) {
            val fBtn = Button(this).apply {
                text = fLabel
                textSize = 10f
                val isSelected = currentFilter == fKey
                background = if (isSelected) makeRoundedDrawable("#76E6D5", "#76E6D5", 10) 
                             else makeRoundedDrawable("#1C1A24", "#2B283A", 10)
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, (36 * resources.displayMetrics.density).toInt(), 1f).apply {
                    setMargins(4, 0, 4, 0)
                }
                setOnClickListener {
                    currentFilter = fKey
                    loadLibraryTab()
                }
            }
            filterRow.addView(fBtn)
        }
        contentContainer.addView(filterRow)

        loadLibraryTabWithFilters(activeLibraryFilters)
    }

    private fun loadLibraryTabWithFilters(advFilters: JSONObject?) {
        val oldLoading = contentContainer.findViewWithTag<View>("library_loading")
        if (oldLoading != null) contentContainer.removeView(oldLoading)
        
        while (contentContainer.childCount > 2) {
            contentContainer.removeViewAt(2)
        }

        val loadingLabel = TextView(this).apply {
            text = "Cargando biblioteca..."
            setTextColor(Color.parseColor("#A8A5B2"))
            textSize = 14f
            tag = "library_loading"
            setPadding(10, 30, 10, 10)
        }
        contentContainer.addView(loadingLabel)

        thread {
            try {
                var urlPath = "/library"
                val params = mutableListOf<String>()
                if (currentFilter != "all") params.add("status=$currentFilter")
                if (selectedCategory != "all") params.add("media_type=$selectedCategory")
                
                advFilters?.keys()?.forEach { key ->
                    params.add("$key=${URLEncoder.encode(advFilters.get(key).toString(), "UTF-8")}")
                }
                
                if (includeGenres.isNotEmpty()) params.add("include_genres=${URLEncoder.encode(includeGenres.joinToString(","), "UTF-8")}")
                if (excludeGenres.isNotEmpty()) params.add("exclude_genres=${URLEncoder.encode(excludeGenres.joinToString(","), "UTF-8")}")

                if (params.isNotEmpty()) urlPath += "?" + params.joinToString("&")

                val (code, resp) = request("GET", urlPath, null)
                if (code in 200..299) {
                    val array = JSONArray(resp)
                    runOnUiThread {
                        contentContainer.removeView(loadingLabel)
                        if (array.length() == 0) {
                            contentContainer.addView(TextView(this).apply {
                                text = "No hay resultados en esta lista."; setTextColor(Color.GRAY); gravity = Gravity.CENTER; setPadding(0, 80, 0, 0)
                            })
                        } else {
                            for (i in 0 until array.length()) {
                                contentContainer.addView(createLibraryEntryCard(array.getJSONObject(i)))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { loadingLabel.text = "Error: ${e.localizedMessage}" }
            }
        }
    }

    private fun addMediaCardDetails(container: LinearLayout, media: JSONObject, userRating: Double? = null, notes: String? = null) {
        val facts = mutableListOf<String>()
        val status = media.optString("airing_status", media.optString("status", ""))
        val age = media.optString("age_rating", "")
        val author = media.optString("author", media.optString("creator", "")).trim()
        val year = media.optInt("release_year", 0)
        facts.add("Autoría / estudio: ${author.ifBlank { "No disponible" }}")
        facts.add(if (year > 0) "Año: $year" else "Año no disponible")
        if (status.isNotBlank()) facts.add("Estado: $status")
        if (age.isNotBlank()) facts.add("Clasificación: ${if (age == "safe") "Todo público" else if (age == "adult") "Adultos" else age}")
        if (media.has("rating_avg") && !media.isNull("rating_avg") && media.optDouble("rating_avg", 0.0) > 0.0) {
            facts.add("Valoración: ${"%.1f".format(media.optDouble("rating_avg"))}/10")
        }
        if (userRating != null && userRating > 0.0) facts.add("Tu nota: ${"%.1f".format(userRating)}/10")
        if (facts.isNotEmpty()) {
            container.addView(TextView(this).apply {
                text = facts.joinToString(" • "); textSize = 10f; setTextColor(Color.parseColor("#A8A5B2")); setPadding(0, 2, 0, 5)
            })
        }

        val genres = media.optJSONArray("genres")?.let { array ->
            List(array.length()) { index -> array.optString(index) }.filter { it.isNotBlank() }.joinToString(", ")
        }.orEmpty()
        container.addView(TextView(this).apply {
            text = if (genres.isNotBlank()) "Géneros: $genres" else "Género no especificado"
            textSize = 10f; setTextColor(Color.parseColor("#BCAADB")); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 5)
        })

        val synopsis = media.optString("synopsis", media.optString("description", "")).trim()
        container.addView(TextView(this).apply {
            text = if (synopsis.isNotBlank()) synopsis else "Sin sinopsis disponible."
            textSize = 10f; setTextColor(Color.LTGRAY); maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 6)
        })
        if (!notes.isNullOrBlank()) {
            container.addView(TextView(this).apply { text = "Notas: $notes"; textSize = 10f; setTextColor(Color.parseColor("#E5C07B")); maxLines = 2; setPadding(0, 0, 0, 6) })
        }
    }

    private fun createLibraryEntryCard(entry: JSONObject): View {
        val media = normalizeMediaJson(entry.getJSONObject("media"))
        val mediaId = entry.getString("media_id")
        val title = media.optString("title", "Sin título")
        val mediaType = media.optString("media_type", "media")
        val imageUrl = media.optString("image_url", "")
        var progress = entry.optDouble("progress", 0.0).toInt()
        
        val total = if (!entry.isNull("total")) entry.optDouble("total").toInt() 
                    else if (media.has("total_units")) media.optInt("total_units")
                    else null
        val seasons = media.optInt("seasons", 1)
        val year = media.optInt("release_year", 0)
        val airing = media.optString("airing_status", "")
        val author = media.optString("author", "")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16)
            setPadding(12, 12, 12, 12)
            elevation = 6f
            layoutParams = makeMarginParams(0, 10)
        }

        val imageSize = (90 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply { setMargins(0, 0, 16, 0) }
            background = makeRoundedDrawable("#111016", "#1F1D29", 12)
            clipToOutline = true
        }
        val coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (imageUrl.isNotEmpty()) loadImage(imageUrl, this)
        }
        coverWrapper.addView(coverView)
        card.addView(coverWrapper)

        val infoContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val typeBadge = TextView(this).apply {
            text = mediaType.uppercase(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#1E2D2A", "#2D4541", 6); setPadding(10, 4, 10, 4)
        }
        val authorLabel = if (author.isNotEmpty()) TextView(this).apply {
            text = " • $author"; textSize = 8f; setTextColor(Color.parseColor("#BCAADB")); setPadding(4, 0, 0, 0)
        } else null
        val yearBadge = if (year > 0) TextView(this).apply {
            text = " • $year"; textSize = 8f; setTextColor(Color.GRAY); setPadding(4, 0, 0, 0)
        } else null
        topRow.addView(typeBadge); authorLabel?.let { topRow.addView(it) }; yearBadge?.let { topRow.addView(it) }
        infoContent.addView(topRow)

        val titleView = TextView(this).apply {
            text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 6, 0, 2)
        }
        infoContent.addView(titleView)
        addMediaCardDetails(
            infoContent,
            media,
            if (entry.has("rating") && !entry.isNull("rating")) entry.optDouble("rating") else null,
            entry.optString("notes", ""),
        )

        val metaView = TextView(this).apply {
            val genres = media.optJSONArray("genres")?.let { arr -> List(arr.length()) { i -> arr.getString(i) }.joinToString(", ") } ?: ""
            text = (if (airing.isNotEmpty()) "$airing • " else "") + genres
            textSize = 10f; setTextColor(Color.parseColor("#A8A5B2")); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 6)
        }
        infoContent.addView(metaView)

        if (seasons > 1 || mediaType == "series" || mediaType == "anime") {
            val seasonView = TextView(this).apply {
                text = if (mediaType == "manga" || mediaType == "book") "$seasons Volúmenes" else "$seasons Temporadas"
                textSize = 11f; setTextColor(Color.parseColor("#A782FF")); setPadding(0, 0, 0, 6)
            }
            infoContent.addView(seasonView)
        }

        val progressRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 4, 0, 8) }
        val progressText = TextView(this).apply {
            val totalStr = if (total != null && total > 0) "/$total" else ""
            text = "Visto: $progress$totalStr"; textSize = 13f; setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val plusBtn = Button(this).apply {
            text = "+1"; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 8)
            layoutParams = LinearLayout.LayoutParams((46 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt())
            setOnClickListener {
                if (total != null && total > 0 && progress >= total) { toast("¡Completado!"); return@setOnClickListener }
                progress++; updateMediaProgress(mediaId, progress, progressText, total)
            }
        }
        progressRow.addView(progressText); progressRow.addView(plusBtn)
        infoContent.addView(progressRow)

        val actionRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val editBtn = TextView(this).apply {
            text = "EDITAR"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#A782FF"))
            setPadding(0, 10, 30, 10); setOnClickListener { showManualEditDialog(entry, mediaId, title, progress, total) }
        }
        val finishBtn = TextView(this).apply {
            text = "TERMINAR"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#98C379"))
            setPadding(30, 10, 30, 10)
            setOnClickListener {
                val t = total ?: progress
                updateLibraryEntryStatus(mediaId, "completed", t, t)
            }
        }
        val deleteBtn = TextView(this).apply {
            text = "BORRAR"; textSize = 10f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FF6B6B"))
            setPadding(30, 10, 0, 10); setOnClickListener { confirmDeleteFromLibrary(mediaId, title) }
        }
        actionRow.addView(editBtn); actionRow.addView(finishBtn); actionRow.addView(deleteBtn)
        infoContent.addView(actionRow)

        card.addView(infoContent)
        return card
    }

    private fun showManualEditDialog(entry: JSONObject, mediaId: String, title: String, currentProg: Int, currentTotal: Int?) {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Editar: $title")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40); setBackgroundColor(Color.parseColor("#15141B"))
        }

        fun createInput(label: String, value: String) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; layoutParams = makeMarginParams(0, 8)
            addView(TextView(this@MainActivity).apply { text = label; setTextColor(Color.GRAY); textSize = 12f })
            addView(EditText(this@MainActivity).apply {
                setText(value); setTextColor(Color.WHITE); background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8)
                setPadding(20, 20, 20, 20); inputType = android.text.InputType.TYPE_CLASS_NUMBER
            })
        }

        val pBox = createInput("Progreso actual:", "$currentProg")
        val pInput = pBox.getChildAt(1) as EditText
        val tBox = createInput("Total disponible:", if (currentTotal != null) "$currentTotal" else "")
        val tInput = tBox.getChildAt(1) as EditText

        val sLabel = TextView(this).apply { text = "Estado actual:"; setTextColor(Color.GRAY); textSize = 12f; setPadding(0, 10, 0, 8) }
        val media = entry.optJSONObject("media")
        val mediaType = media?.let {
            it.optString("media_type", "").ifBlank { it.optString("category", "") }
        } ?: entry.optString("media_type", entry.optString("category", ""))
        val statusList = if (mediaType in setOf("game", "music", "album")) {
            listOf(
                "completed" to "Terminado",
                "in_progress" to "En proceso",
                "planned" to "Pendiente",
                "wishlist" to "Lista de deseo"
            )
        } else {
            listOf(
                "planned" to "Planificado",
                "in_progress" to "En progreso",
                "completed" to "Completado",
                "on_hold" to "En pausa",
                "dropped" to "Abandonado"
            )
        }
        val sSpinner = Spinner(this).apply {
            val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, statusList.map { it.second }) {
                override fun getView(p: Int, c: View?, parent: ViewGroup): View = (super.getView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE) }
                override fun getDropDownView(p: Int, c: View?, parent: ViewGroup): View = (super.getDropDownView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1C1A24")); setPadding(30,30,30,30) }
            }
            this.adapter = adapter
            background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8)
            val currentStatus = entry.optString("status", "planned")
            val index = statusList.indexOfFirst { it.first == currentStatus }
            if (index >= 0) setSelection(index)
        }

        box.addView(pBox); box.addView(tBox); box.addView(sLabel); box.addView(sSpinner)
        b.setView(box)

        b.setPositiveButton("Guardar") { _, _ ->
            val pVal = pInput.text.toString().toDoubleOrNull() ?: 0.0
            val tVal = tInput.text.toString().toDoubleOrNull()
            val sVal = statusList[sSpinner.selectedItemPosition].first

            thread {
                try {
                    val body = JSONObject().apply {
                        put("status", sVal); put("progress", pVal.toInt())
                        if (tVal != null) put("total", tVal.toInt())
                    }
                    val (code, _) = request("PUT", "/library/$mediaId", body.toString())
                    if (code in 200..299) runOnUiThread { toast("Actualizado"); loadLibraryTab() }
                } catch (e: Exception) { runOnUiThread { toast("Error: ${e.localizedMessage}") } }
            }
        }
        b.setNegativeButton("Cerrar", null).show()
    }

    private fun updateLibraryEntryStatus(mediaId: String, status: String, progress: Int, total: Int?) {
        thread {
            try {
                val body = JSONObject().apply {
                    put("status", status)
                    put("progress", progress)
                    if (total != null) put("total", total)
                }
                val (code, _) = request("PUT", "/library/$mediaId", body.toString())
                if (code in 200..299) {
                    runOnUiThread {
                        toast("Estado actualizado a $status")
                        loadLibraryTab()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Error: ${e.localizedMessage}") }
            }
        }
    }

    private fun updateMediaProgress(mediaId: String, newValue: Int, label: TextView, total: Int?) {
        thread {
            try {
                val json = JSONObject().apply {
                    put("value", newValue)
                    put("unit", "episode")
                }
                val (code, resp) = request("POST", "/library/$mediaId/progress", json.toString())
                if (code in 200..299) {
                    runOnUiThread {
                        label.text = "Visto: $newValue" + (if (total != null) "/$total" else "")
                        toast("Progreso: $newValue")
                    }
                } else {
                    val err = try { JSONObject(resp).optString("detail", resp) } catch (_: Exception) { resp }
                    runOnUiThread { toast(err) }
                }
            } catch (e: Exception) {
                runOnUiThread { toast("Error: ${e.localizedMessage}") }
            }
        }
    }

    private fun confirmDeleteFromLibrary(mediaId: String, title: String) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar de la lista")
            .setMessage("¿Deseas quitar \"$title\" de tu biblioteca?")
            .setPositiveButton("Eliminar") { _, _ ->
                thread {
                    try {
                        val (code, _) = request("DELETE", "/library/$mediaId", null)
                        if (code in 200..299) {
                            runOnUiThread {
                                toast("Eliminado")
                                loadLibraryTab()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Error: ${e.localizedMessage}") }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showGenreFilterDialog() {
        val b = AlertDialog.Builder(this)
        b.setTitle("Filtro de Géneros (+ / -)")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 20)
        }

        val help = TextView(this).apply {
            text = "• Verde (+): Incluir obligatoriamente\n• Rojo (-): Excluir de los resultados\n• Gris: Sin filtro"
            textSize = 12f
            setTextColor(Color.DKGRAY)
            setPadding(0, 0, 0, 16)
        }
        box.addView(help)

        for (g in popularGenres) {
            val gBtn = Button(this).apply {
                val isInc = includeGenres.contains(g)
                val isExc = excludeGenres.contains(g)

                text = (if (isInc) "✓ $g (+)" else if (isExc) "✕ $g (-)" else g)
                background = makeRoundedDrawable(
                    if (isInc) "#1F3D35" else if (isExc) "#3D1F23" else "#1F1D2B",
                    if (isInc) "#76E6D5" else if (isExc) "#FF6B6B" else "#2F2B40",
                    8
                )
                setTextColor(if (isInc) Color.parseColor("#76E6D5") else if (isExc) Color.parseColor("#FF6B6B") else Color.WHITE)
                layoutParams = makeMarginParams(0, 4)

                setOnClickListener {
                    if (includeGenres.contains(g)) {
                        includeGenres.remove(g)
                        excludeGenres.add(g)
                    } else if (excludeGenres.contains(g)) {
                        excludeGenres.remove(g)
                    } else {
                        includeGenres.add(g)
                    }
                    val nowInc = includeGenres.contains(g)
                    val nowExc = excludeGenres.contains(g)
                    text = (if (nowInc) "✓ $g (+)" else if (nowExc) "✕ $g (-)" else g)
                    background = makeRoundedDrawable(
                        if (nowInc) "#1F3D35" else if (nowExc) "#3D1F23" else "#1F1D2B",
                        if (nowInc) "#76E6D5" else if (nowExc) "#FF6B6B" else "#2F2B40",
                        8
                    )
                    setTextColor(if (nowInc) Color.parseColor("#76E6D5") else if (nowExc) Color.parseColor("#FF6B6B") else Color.WHITE)
                }
            }
            box.addView(gBtn)
        }

        val scroll = ScrollView(this).apply { addView(box) }
        b.setView(scroll)
        b.setPositiveButton("Aplicar Filtros") { _, _ ->
            loadLibraryTab()
        }
        b.setNeutralButton("Limpiar Géneros") { _, _ ->
            includeGenres.clear()
            excludeGenres.clear()
            loadLibraryTab()
        }
        b.show()
    }

    private fun renderExploreTab() {
        contentContainer.removeAllViews()

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 10)
        }

        val title = TextView(this).apply {
            text = "Descubrir ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        
        val addManualBtn = TextView(this).apply {
            text = "＋ CREAR"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#76E6D5"))
            setPadding(20, 10, 0, 10)
            setOnClickListener { showManualCreateDialog() }
        }

        headerRow.addView(title)
        headerRow.addView(addManualBtn)
        contentContainer.addView(headerRow)

        val resultsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 10)
        }

        val searchInput = EditText(this).apply {
            hint = "Buscar títulos..."
            setHintTextColor(Color.parseColor("#6C6977"))
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val searchBtn = Button(this).apply {
            text = "BUSCAR"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (54 * resources.displayMetrics.density).toInt()).apply {
                setMargins(10, 0, 0, 0)
            }
            setOnClickListener {
                val q = searchInput.text.toString().trim()
                performSearch(q, resultsBox, activeSearchFilters)
            }
        }
        
        val advFilterBtn = TextView(this).apply {
            text = "⚙️ FILTROS AVANZADOS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A782FF"))
            setPadding(0, 10, 0, 20)
            setOnClickListener {
                showAdvancedSearchFilters(activeSearchFilters) { filters ->
                    activeSearchFilters = filters
                    val q = searchInput.text.toString().trim()
                    performSearch(q, resultsBox, filters)
                }
            }
        }

        searchRow.addView(searchInput)
        searchRow.addView(searchBtn)
        contentContainer.addView(searchRow)
        contentContainer.addView(advFilterBtn)
        contentContainer.addView(resultsBox)
    }

    private fun showManualCreateDialog() {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Agregar Media Manualmente")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#15141B"))
        }

        fun createInput(hint: String) = EditText(this).apply {
            setHint(hint)
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8)
            setPadding(20, 20, 20, 20)
            layoutParams = makeMarginParams(0, 8)
        }

        val tIn = createInput("Título (Obligatorio)")
        val cIn = Spinner(this).apply {
            val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, listOf("anime", "manga", "movie", "series", "book", "music")) {
                override fun getView(p: Int, c: View?, parent: ViewGroup): View = (super.getView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE) }
                override fun getDropDownView(p: Int, c: View?, parent: ViewGroup): View = (super.getDropDownView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1C1A24")); setPadding(30,30,30,30) }
            }
            this.adapter = adapter
            layoutParams = makeMarginParams(0, 8)
        }
        val sIn = createInput("Sinopsis")
        val iIn = createInput("URL de Imagen")
        val gIn = createInput("Géneros (Separados por coma)")
        val epIn = createInput("Total episodios/tomos").apply { inputType = android.text.InputType.TYPE_CLASS_NUMBER }

        box.addView(tIn); box.addView(cIn); box.addView(sIn); box.addView(iIn); box.addView(gIn); box.addView(epIn)
        b.setView(box)
        b.setPositiveButton("Guardar") { _, _ ->
            val title = tIn.text.toString().trim()
            if (title.isEmpty()) { toast("Título requerido"); return@setPositiveButton }
            
            val item = JSONObject().apply {
                put("title", title)
                put("category", cIn.selectedItem.toString())
                put("synopsis", sIn.text.toString())
                put("image_url", iIn.text.toString())
                put("genres", JSONArray(gIn.text.toString().split(",").map { it.trim() }))
                put("total_units", epIn.text.toString().toIntOrNull() ?: 0)
                put("source", "manual")
            }
            
            thread {
                val encoded = URLEncoder.encode(title, "UTF-8")
                val (code, resp) = request("GET", "/search?query=$encoded", null)
                val exists = code == 200 && JSONArray(resp).length() > 0
                
                runOnUiThread {
                    if (exists) {
                        AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                            .setTitle("Ya existe algo similar")
                            .setMessage("Hemos encontrado obras con ese nombre. ¿Quieres guardar la tuya de todas formas?")
                            .setPositiveButton("Guardar la mía") { _, _ -> importAndAddMedia(item, Button(this@MainActivity)) }
                            .setNegativeButton("Ver resultados") { _, _ -> switchTab("explore") }
                            .show()
                    } else {
                        importAndAddMedia(item, Button(this@MainActivity))
                    }
                }
            }
        }
        b.show()
    }

    private fun showAdvancedSearchFilters(currentFilters: JSONObject, onApply: (JSONObject) -> Unit) {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Filtros de Búsqueda")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
            setBackgroundColor(Color.parseColor("#15141B"))
        }

        fun createDarkSpinner(options: List<String>, selected: String?): Spinner {
            return Spinner(this).apply {
                val adapter = object : ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_spinner_item, options) {
                    override fun getView(p: Int, c: View?, parent: ViewGroup): View = (super.getView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE); textSize = 14f }
                    override fun getDropDownView(p: Int, c: View?, parent: ViewGroup): View = (super.getDropDownView(p, c, parent) as TextView).apply { setTextColor(Color.WHITE); setBackgroundColor(Color.parseColor("#1C1A24")); setPadding(30, 30, 30, 30) }
                }
                this.adapter = adapter
                background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8)
                layoutParams = makeMarginParams(0, 8)
                val idx = options.indexOf(selected)
                if (idx >= 0) setSelection(idx)
            }
        }

        val yearLabel = TextView(this).apply { text = "Años seleccionados:"; setTextColor(Color.GRAY) }
        var selectedYears = currentFilters.optString("year", "").split(",").filter { it.isNotEmpty() }.toMutableList()
        val yearBtn = Button(this).apply {
            text = if (selectedYears.isEmpty()) "Cualquiera" else selectedYears.joinToString(", ")
            background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8); setTextColor(Color.WHITE)
            setOnClickListener {
                val years = (1900..java.time.Year.now().value).map { it.toString() }.reversed()
                val checked = BooleanArray(years.size) { years[it] in selectedYears }
                AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    .setTitle("Elegir años")
                    .setMultiChoiceItems(years.toTypedArray(), checked) { _, which, isChecked ->
                        if (isChecked) selectedYears.add(years[which]) else selectedYears.remove(years[which])
                    }
                    .setPositiveButton("Hecho") { _, _ -> text = if (selectedYears.isEmpty()) "Cualquiera" else selectedYears.joinToString(", ") }
                    .show()
            }
            layoutParams = makeMarginParams(0, 8)
        }
        
        val statusLabel = TextView(this).apply { text = "\nEstado de la obra:"; setTextColor(Color.GRAY) }
        val statuses = listOf("Cualquiera", "En emisión", "Finalizado", "En pausa", "Próximamente", "Cancelado")
        val statusValues = listOf("", "releasing", "finished", "on_hold", "upcoming", "cancelled")
        val currentStatusIndex = statusValues.indexOf(currentFilters.optString("media_status", "")).coerceAtLeast(0)
        val statusSpinner = createDarkSpinner(statuses, statuses[currentStatusIndex])

        val ageLabel = TextView(this).apply { text = "\nClasificación de edad:"; setTextColor(Color.GRAY) }
        val ages = listOf("Cualquiera", "Todo público", "Adultos (18+)")
        val ageValues = listOf("", "safe", "adult")
        val currentAgeIndex = ageValues.indexOf(currentFilters.optString("age_rating", "")).coerceAtLeast(0)
        val ageSpinner = createDarkSpinner(ages, ages[currentAgeIndex])

        val genreBtn = Button(this).apply {
            text = "Seleccionar Géneros (+/-)"
            textSize = 12f; setTextColor(Color.WHITE); background = makeRoundedDrawable("#211D36", "#3A335E", 10)
            setOnClickListener { showMultiGenreSelectorDialog() }
            layoutParams = makeMarginParams(0, 16)
        }

        box.addView(yearLabel); box.addView(yearBtn)
        box.addView(statusLabel); box.addView(statusSpinner)
        box.addView(ageLabel); box.addView(ageSpinner)
        box.addView(genreBtn)

        b.setView(box)
        b.setPositiveButton("Aplicar") { _, _ ->
            val filters = JSONObject().apply {
                if (selectedYears.isNotEmpty()) put("year", selectedYears.joinToString(","))
                if (statusSpinner.selectedItemPosition > 0) put("media_status", statusValues[statusSpinner.selectedItemPosition])
                if (ageSpinner.selectedItemPosition > 0) put("age_rating", ageValues[ageSpinner.selectedItemPosition])
            }
            onApply(filters)
        }
        b.setNegativeButton("Cerrar", null)
        b.show()
    }

    private fun showMultiGenreSelectorDialog() {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Filtrar por Géneros")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
        
        val genres = webGenreFilters
        val help = TextView(this).apply {
            text = "✓ Incluir, ✕ Excluir, Sin marca Neutral\n"
            textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.CENTER
        }
        box.addView(help)

        for (g in genres) {
            val gBtn = Button(this).apply {
                var state = if (includeGenres.contains(g)) 1 else if (excludeGenres.contains(g)) 2 else 0
                fun updateBtn() {
                    text = when(state) { 1 -> "✓ $g"; 2 -> "✕ $g"; else -> g }
                    val color = when(state) { 1 -> "#76E6D5"; 2 -> "#FF6B6B"; else -> "#1C1A24" }
                    background = makeRoundedDrawable(color, if (state == 0) "#2B283A" else color, 10)
                    setTextColor(if (state == 0) Color.WHITE else Color.BLACK)
                }
                updateBtn()
                layoutParams = makeMarginParams(0, 4)
                setOnClickListener {
                    state = (state + 1) % 3
                    when(state) {
                        1 -> { includeGenres.add(g); excludeGenres.remove(g) }
                        2 -> { includeGenres.remove(g); excludeGenres.add(g) }
                        else -> { includeGenres.remove(g); excludeGenres.remove(g) }
                    }
                    updateBtn()
                }
            }
            box.addView(gBtn)
        }

        val scroll = ScrollView(this).apply { addView(box) }
        b.setView(scroll).setPositiveButton("Hecho", null).show()
    }

    private fun performSearch(q: String, resultsBox: LinearLayout, filters: JSONObject?) {
        resultsBox.removeAllViews()
        val searchingLabel = TextView(this).apply {
            text = "Buscando..."; setTextColor(Color.parseColor("#A8A5B2")); textSize = 14f; setPadding(0, 20, 0, 20)
        }
        resultsBox.addView(searchingLabel)

        thread {
            try {
                val encoded = URLEncoder.encode(q, "UTF-8")
                var url = "/search?query=$encoded"
                if (selectedCategory != "all") url += "&media_type=$selectedCategory"
                
                filters?.keys()?.forEach { key ->
                    url += "&$key=${URLEncoder.encode(filters.get(key).toString(), "UTF-8")}"
                }
                
                if (includeGenres.isNotEmpty()) url += "&include_genres=${URLEncoder.encode(includeGenres.joinToString(","), "UTF-8")}"
                if (excludeGenres.isNotEmpty()) url += "&exclude_genres=${URLEncoder.encode(excludeGenres.joinToString(","), "UTF-8")}"

                val (code, resp) = request("GET", url, null)
                if (code in 200..299) {
                    val array = JSONArray(resp)
                    runOnUiThread {
                        resultsBox.removeAllViews()
                        if (array.length() == 0) {
                            resultsBox.addView(TextView(this).apply { text = "Sin resultados"; setTextColor(Color.GRAY); setPadding(0, 40, 0, 0); gravity = Gravity.CENTER })
                        } else {
                            val uniqueResults = mutableMapOf<String, JSONObject>()
                            for (i in 0 until array.length()) {
                                val item = normalizeMediaJson(array.getJSONObject(i))
                                val titleKey = item.optString("title").lowercase().trim()
                                val categoryKey = item.optString("category", item.optString("media_type")).lowercase().trim()
                                val key = "$titleKey-$categoryKey"
                                
                                if (!uniqueResults.containsKey(key)) {
                                    uniqueResults[key] = item
                                } else {
                                    val existing = uniqueResults[key]!!
                                    if (existing.optString("author").isEmpty()) existing.put("author", item.optString("author"))
                                    if (existing.optInt("total_units") == 0) existing.put("total_units", item.optInt("total_units"))
                                    if (existing.optInt("release_year") == 0) existing.put("release_year", item.optInt("release_year"))
                                }
                            }
                            uniqueResults.values.forEach { resultsBox.addView(createSearchResultCard(it)) }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { searchingLabel.text = "Error: ${e.localizedMessage}" }
            }
        }
    }

    private fun normalizeMediaJson(item: JSONObject): JSONObject {
        if (!item.has("category")) item.put("category", item.optString("media_type", "other"))
        if (!item.has("media_type")) item.put("media_type", item.optString("category", "other"))
        if (!item.has("id")) item.put("id", item.optString("external_id", "ext_${System.currentTimeMillis()}"))
        if (!item.has("image_url")) item.put("image_url", item.optString("cover_url", ""))
        if (!item.has("synopsis")) item.put("synopsis", item.optString("description", ""))
        item.put("airing_status", normalizedMediaStatus(item.optString("airing_status", item.optString("status", ""))))
        if (!item.has("author")) item.put("author", item.optString("creator", ""))
        val rawGenres = item.optJSONArray("genres") ?: JSONArray()
        val localizedGenres = JSONArray()
        val seenGenres = mutableSetOf<String>()
        for (index in 0 until rawGenres.length()) {
            val label = localizedGenreLabel(rawGenres.optString(index))
            if (label.isNotBlank() && seenGenres.add(normalizedGenre(label))) localizedGenres.put(label)
        }
        item.put("genres", localizedGenres)
        return item
    }

    private fun normalizedMediaStatus(value: String): String {
        val status = normalizedGenre(value)
        return when {
            status in setOf("finished", "ended", "finalizado", "complete", "completed") -> "finished"
            status in setOf("releasing", "airing", "running", "en emision", "currently airing") -> "releasing"
            status in setOf("on_hold", "on hold", "hiatus", "en pausa") -> "on_hold"
            status in setOf("upcoming", "not yet aired", "proximamente") -> "upcoming"
            status in setOf("cancelled", "canceled", "cancelado") -> "cancelled"
            else -> status
        }
    }

    private fun mediaStatusAliases(value: String): List<String> = when (value) {
        "finished" -> listOf("finished", "ended", "finalizado", "complete", "completed")
        "releasing" -> listOf("releasing", "airing", "running", "en emisión", "currently airing")
        "on_hold" -> listOf("on_hold", "on hold", "hiatus", "en pausa")
        "upcoming" -> listOf("upcoming", "not yet aired", "próximamente")
        "cancelled" -> listOf("cancelled", "canceled", "cancelado")
        else -> listOf(value.lowercase())
    }

    private fun normalizedGenre(value: String): String {
        var plain = java.text.Normalizer.normalize(value.lowercase().trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace('-', ' ')
            .replace(Regex("\\s+"), " ")
        if (plain == "superhero" || plain == "superheroes") return "superheroes"
        val aliases = linkedMapOf(
            "science fiction" to "ciencia ficcion", "sci fi" to "ciencia ficcion",
            "graphic novel" to "novela grafica", "role playing" to "rpg",
            "open world" to "mundo abierto", "battle royale" to "battle royale",
            "slice of life" to "slice of life", "martial arts" to "artes marciales",
            "boys love" to "bl", "historical" to "historico",
            "psychological" to "psicologico", "simulation" to "simulacion",
            "platformer" to "plataformas", "platform" to "plataformas",
            "strategy" to "estrategia", "sports" to "deportes", "sport" to "deportes",
            "action" to "accion", "adventure" to "aventura", "comedy" to "comedia",
            "fantasy" to "fantasia", "horror" to "terror", "mystery" to "misterio",
            "thriller" to "suspense", "history" to "historia", "biography" to "biografia",
            "classical" to "clasica", "electronic" to "electronica", "soundtrack" to "banda sonora",
            "music" to "musica", "fiction" to "ficcion"
        )
        for ((source, target) in aliases) plain = plain.replace(source, target)
        return plain.trim()
    }

    private fun localizedGenreLabel(value: String): String {
        val normalized = normalizedGenre(value)
        return when {
            normalized == "accion" -> "Acción"
            normalized == "aventura" -> "Aventura"
            normalized == "comedia" -> "Comedia"
            normalized == "drama" -> "Drama"
            normalized == "fantasia" -> "Fantasía"
            normalized.contains("ciencia ficcion") -> if (normalized == "ciencia ficcion") "Ciencia Ficción" else value.trim()
            normalized == "terror" -> "Terror"
            normalized == "misterio" -> "Misterio"
            normalized == "suspense" -> "Suspense"
            normalized == "rpg" -> "RPG"
            normalized == "estrategia" -> "Estrategia"
            normalized == "simulacion" -> "Simulación"
            normalized == "deportes" -> "Deportes"
            normalized == "plataformas" -> "Plataformas"
            normalized == "mundo abierto" -> "Mundo Abierto"
            normalized == "novela grafica" -> "Novela Gráfica"
            normalized == "superheroes" -> "Superhéroes"
            normalized == "psicologico" -> "Psicológico"
            normalized == "historico" -> "Histórico"
            normalized == "electronica" -> "Electrónica"
            normalized == "clasica" -> "Clásica"
            normalized == "musica" -> "Música"
            else -> value.trim()
        }
    }

    private fun genreMatches(selected: String, actual: String): Boolean {
        val wanted = normalizedGenre(selected)
        val found = normalizedGenre(actual)
        return wanted == found || found.contains(wanted) || wanted.contains(found)
    }

    private fun extractYear(vararg values: String?): Int {
        for (value in values) {
            val match = Regex("(?:18|19|20)\\d{2}").find(value.orEmpty())
            if (match != null) return match.value.toInt()
        }
        return 0
    }

    private fun matchesExternalFilters(item: JSONObject, years: List<String>, included: List<String>, excluded: List<String>, mediaStatus: String?, ageRating: String?): Boolean {
        if (years.isNotEmpty() && item.optInt("release_year", 0).toString() !in years) return false
        if (!mediaStatus.isNullOrBlank() && normalizedMediaStatus(item.optString("airing_status", item.optString("status"))) != mediaStatus) return false
        if (!ageRating.isNullOrBlank() && item.optString("age_rating", "safe") != ageRating) return false
        val values = item.optJSONArray("genres")?.let { array ->
            List(array.length()) { index -> normalizedGenre(array.optString(index)) }
        } ?: emptyList()
        val includeMatch = included.isEmpty() || included.any { selected ->
            val wanted = normalizedGenre(selected)
            values.any { genreMatches(wanted, it) }
        }
        val excludeMatch = excluded.any { selected ->
            val unwanted = normalizedGenre(selected)
            values.any { genreMatches(unwanted, it) }
        }
        return includeMatch && !excludeMatch
    }

    private fun createSearchResultCard(item: JSONObject): View {
        val title = item.optString("title", "Sin título")
        val mediaType = item.optString("media_type", item.optString("category", "medio"))
        val source = item.optString("source", "web")
        val year = item.optInt("release_year", 0)
        val units = if (item.isNull("total_units")) null else item.optInt("total_units")
        val seasons = item.optInt("seasons", 1)
        val imageUrl = item.optString("image_url", "")
        val airing = item.optString("airing_status", "")
        val author = item.optString("author", "")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16)
            setPadding(12, 12, 12, 12)
            elevation = 4f
            layoutParams = makeMarginParams(0, 10)
        }

        val imageSize = (90 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply { setMargins(0, 0, 16, 0) }
            background = makeRoundedDrawable("#111016", "#1F1D29", 12)
            clipToOutline = true
        }
        val coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            if (imageUrl.isNotEmpty()) loadImage(imageUrl, this)
        }
        coverWrapper.addView(coverView)
        card.addView(coverWrapper)

        val infoContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val badge = TextView(this).apply {
            text = mediaType.uppercase(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#A782FF"))
            background = makeRoundedDrawable("#211D36", "#3A335E", 6); setPadding(10, 4, 10, 4)
        }
        val authorLabel = if (author.isNotEmpty()) TextView(this).apply {
            text = " • $author"; textSize = 8f; setTextColor(Color.parseColor("#BCAADB")); setPadding(4, 0, 0, 0)
        } else null
        val sourceLabel = TextView(this).apply {
            text = (if (year > 0) " • $year" else "") + " • " + source.uppercase()
            textSize = 10f; setTextColor(Color.parseColor("#A8A5B2")); setPadding(4, 0, 0, 0)
        }
        topRow.addView(badge); authorLabel?.let { topRow.addView(it) }; topRow.addView(sourceLabel)
        infoContent.addView(topRow)

        val titleView = TextView(this).apply {
            text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 6, 0, 2)
        }
        infoContent.addView(titleView)
        addMediaCardDetails(infoContent, item)

        val metaText = mutableListOf<String>()
        if (airing.isNotEmpty()) metaText.add(airing)
        val genres = item.optJSONArray("genres")?.let { arr -> List(arr.length()) { i -> arr.getString(i) }.take(3).joinToString(", ") }
        if (!genres.isNullOrEmpty()) metaText.add(genres)
        if (metaText.isNotEmpty()) {
            infoContent.addView(TextView(this).apply { text = metaText.joinToString(" • "); textSize = 10f; setTextColor(Color.GRAY); setPadding(0, 0, 0, 6) })
        }

        val detailText = mutableListOf<String>()
        if (units != null && units > 0) detailText.add("$units ${if (mediaType=="manga" || mediaType=="book") "caps" else "eps"}")
        if (seasons > 1) detailText.add("$seasons temp")
        if (detailText.isNotEmpty()) {
            infoContent.addView(TextView(this).apply { text = detailText.joinToString(" • "); textSize = 11f; setTextColor(Color.parseColor("#76E6D5")); setPadding(0, 0, 0, 10) })
        }

        val inLibrary = item.optBoolean("in_library", false)
        val addBtn = Button(this).apply {
            text = if (inLibrary) "EN BIBLIOTECA" else "AÑADIR A LISTA"
            isEnabled = !inLibrary
            textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(if (inLibrary) Color.GRAY else Color.parseColor("#130F1C"))
            background = makeRoundedDrawable(if (inLibrary) "#16151C" else "#76E6D5", if (inLibrary) "#2D2A38" else "#92F5E6", 8)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * resources.displayMetrics.density).toInt())
            setOnClickListener { importAndAddMedia(item, this) }
        }
        infoContent.addView(addBtn)

        card.addView(infoContent)
        return card
    }

    private fun importAndAddMedia(item: JSONObject, btn: Button) {
        btn.isEnabled = false
        btn.text = "Añadiendo..."
        thread {
            try {
                val (importCode, importResp) = request("POST", "/media/import", item.toString())
                if (importCode in 200..299) {
                    val mediaObj = JSONObject(importResp)
                    val mediaId = mediaObj.getString("id")
                    val totalUnits = if (item.isNull("total_units")) null else item.optInt("total_units")
                    val upsertBody = JSONObject().apply { put("status", "planned"); put("progress", 0); if (totalUnits != null) put("total", totalUnits) }
                    val (trackCode, _) = request("PUT", "/library/$mediaId", upsertBody.toString())
                    if (trackCode in 200..299) {
                        runOnUiThread { btn.text = "EN BIBLIOTECA"; toast("Añadido con éxito") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { btn.isEnabled = true; btn.text = "REINTENTAR"; toast("Error: ${e.localizedMessage}") }
            }
        }
    }

    private fun loadRecommendationsTab() {
        contentContainer.removeAllViews()
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, 16) }
        val recsTitle = TextView(this).apply { text = if (selectedCategory == "all") "PARA TI" else "RECOMENDADO: ${selectedCategory.uppercase()}"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val reloadBtn = Button(this).apply { text = "RECARGAR"; textSize = 10f; setTextColor(Color.parseColor("#76E6D5")); background = makeRoundedDrawable("#16151C", "#2D2A38", 10); setPadding(20, 0, 20, 0); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * resources.displayMetrics.density).toInt()); setOnClickListener { loadRecommendationsTab() } }
        header.addView(recsTitle); header.addView(reloadBtn)
        contentContainer.addView(header)
        val desc = TextView(this).apply { text = "Basado en tu biblioteca y preferencias actuales."; textSize = 13f; setTextColor(Color.parseColor("#A8A5B2")); setPadding(0, 0, 0, 20) }
        contentContainer.addView(desc)
        val loading = TextView(this).apply { text = "Calculando afinidades de contenido..."; setTextColor(Color.parseColor("#A8A5B2")) }
        contentContainer.addView(loading)

        thread {
            try {
                val params = mutableListOf("refresh=${System.currentTimeMillis()}")
                if (selectedCategory != "all") params.add("media_type=$selectedCategory")
                if (lastRecommendationIds.isNotEmpty()) params.add("exclude_ids=${URLEncoder.encode(lastRecommendationIds.joinToString(","), "UTF-8")}")
                var (code, resp) = request("GET", "/recommendations?${params.joinToString("&")}", null)
                if (code in 200..299) {
                    var recs = JSONArray(resp)
                    // No dejar Para ti vacío cuando un catálogo pequeño no tenga
                    // suficientes sustitutos para todos los elementos visibles.
                    if (recs.length() == 0 && lastRecommendationIds.isNotEmpty()) {
                        val fallbackParams = mutableListOf("refresh=${System.currentTimeMillis()}-fallback")
                        if (selectedCategory != "all") fallbackParams.add("media_type=$selectedCategory")
                        val fallback = request("GET", "/recommendations?${fallbackParams.joinToString("&")}", null)
                        code = fallback.first
                        resp = fallback.second
                        if (code in 200..299) recs = JSONArray(resp)
                    }
                    runOnUiThread {
                        contentContainer.removeView(loading)
                        if (recs.length() == 0) {
                            contentContainer.addView(TextView(this).apply { text = "Aún no hay recomendaciones disponibles."; setTextColor(Color.parseColor("#A8A5B2")); gravity = Gravity.CENTER; setPadding(40, 80, 40, 0) })
                        } else {
                            lastRecommendationIds.clear()
                            for (i in 0 until recs.length()) {
                                val recommendation = recs.getJSONObject(i)
                                val media = normalizeMediaJson(recommendation.getJSONObject("media"))
                                lastRecommendationIds.add(media.optString("id"))
                                contentContainer.addView(createRecommendationCard(recommendation))
                            }
                        }
                    }
                }
            } catch (e: Exception) { runOnUiThread { loading.text = "Error: ${e.localizedMessage}" } }
        }
    }

    private fun createRecommendationCard(item: JSONObject): View {
        val media = item.getJSONObject("media")
        val title = media.optString("title", "Sin título")
        val mediaType = media.optString("media_type", "medio")
        val imageUrl = media.optString("image_url", "")
        val score = item.optDouble("score", 0.0)
        val reason = item.optString("reason", "Obra destacada")
        val units = if (media.isNull("total_units")) null else media.optInt("total_units")
        val year = media.optInt("release_year", 0)
        val author = media.optString("author", "")

        val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16); setPadding(12, 12, 12, 12); elevation = 6f; layoutParams = makeMarginParams(0, 12) }
        val imageSize = (90 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply { setMargins(0, 0, 16, 0) }; background = makeRoundedDrawable("#111016", "#1F1D29", 12); clipToOutline = true }
        val coverView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP; layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT); if (imageUrl.isNotEmpty()) loadImage(imageUrl, this) }
        coverWrapper.addView(coverView); card.addView(coverWrapper)

        val infoContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        val topRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val typeBadge = TextView(this).apply { text = mediaType.uppercase(); textSize = 8f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#76E6D5")); background = makeRoundedDrawable("#1E2D2A", "#2D4541", 6); setPadding(10, 4, 10, 4) }
        val authorText = if (author.isNotEmpty()) TextView(this).apply { text = " • $author"; textSize = 8f; setTextColor(Color.parseColor("#BCAADB")); setPadding(4, 0, 0, 0) } else null
        val yearText = if (year > 0) TextView(this).apply { text = " • $year"; textSize = 8f; setTextColor(Color.GRAY); setPadding(4, 0, 0, 0) } else null
        val scoreBadge = TextView(this).apply { text = " • Rating: $score"; textSize = 10f; setTextColor(Color.parseColor("#76E6D5")); setPadding(4, 0, 0, 0) }
        topRow.addView(typeBadge); authorText?.let { topRow.addView(it) }; yearText?.let { topRow.addView(it) }; topRow.addView(scoreBadge); infoContent.addView(topRow)
        infoContent.addView(TextView(this).apply { text = title; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 6, 0, 4) })
        addMediaCardDetails(infoContent, media)
        if (units != null && units > 0) infoContent.addView(TextView(this).apply { text = "$units ${if (mediaType=="manga" || mediaType=="book") "caps" else "eps"}"; textSize = 11f; setTextColor(Color.parseColor("#A782FF")); setPadding(0, 0, 0, 6) })
        infoContent.addView(TextView(this).apply { text = reason; textSize = 11f; setTextColor(Color.parseColor("#BCAADB")); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, 0, 0, 10) })
        val addBtn = Button(this).apply { text = "AÑADIR"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); background = makeRoundedDrawable("#342E4A", "#4E466D", 8); setPadding(16, 0, 16, 0); layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * resources.displayMetrics.density).toInt()); setOnClickListener { importAndAddMedia(media, this) } }
        infoContent.addView(addBtn); card.addView(infoContent)
        return card
    }

    private fun renderSettingsTab() {
        contentContainer.removeAllViews()
        val title = TextView(this).apply { text = "Perfil y Configuración"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 20) }
        contentContainer.addView(title)
        val profileCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = makeRoundedDrawable("#181722", "#2B283A", 18); setPadding(30, 30, 30, 30); layoutParams = makeMarginParams(0, 16) }
        avatarPreview = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams((100 * resources.displayMetrics.density).toInt(), (100 * resources.displayMetrics.density).toInt()).apply { gravity = Gravity.CENTER_HORIZONTAL; setMargins(0, 10, 0, 10) }; scaleType = ImageView.ScaleType.CENTER_CROP; background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#2D2A38")) }; clipToOutline = true; if (!userAvatar.isNullOrBlank()) loadImage(userAvatar!!, this) }
        profileCard.addView(avatarPreview)
        val photoButtons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, 10, 0, 20) }
        val choosePresetBtn = TextView(this).apply { text = "Predeterminados"; textSize = 11f; setTextColor(Color.parseColor("#76E6D5")); setPadding(20, 10, 20, 10); setOnClickListener { showPresetAvatarDialog() } }
        val uploadBtn = TextView(this).apply { text = "Subir Galería"; textSize = 11f; setTextColor(Color.parseColor("#A782FF")); setPadding(20, 10, 20, 10); setOnClickListener { val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI); startActivityForResult(intent, 1001) } }
        photoButtons.addView(choosePresetBtn); photoButtons.addView(uploadBtn); profileCard.addView(photoButtons)
        profileCard.addView(TextView(this).apply { text = "Nombre:"; setTextColor(Color.GRAY) })
        val nameInput = EditText(this).apply { setText(userName ?: ""); setTextColor(Color.WHITE); background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8); setPadding(20, 20, 20, 20); layoutParams = makeMarginParams(0, 10) }
        profileCard.addView(nameInput)
        profileCard.addView(TextView(this).apply { text = "URL de imagen:"; setTextColor(Color.GRAY) })
        val avInput = EditText(this).apply { setText(userAvatar ?: ""); setTextColor(Color.WHITE); background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8); setPadding(20, 20, 20, 20); layoutParams = makeMarginParams(0, 10); addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}; override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}; override fun afterTextChanged(s: Editable?) { val url = s?.toString()?.trim() ?: ""; if (url.isNotEmpty()) loadImage(url, avatarPreview!!) } }) }
        profileCard.addView(avInput)
        val notifyBox = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 20, 0, 20) }
        notifyBox.addView(TextView(this).apply { text = "Notificaciones de capítulos"; textSize = 14f; setTextColor(Color.WHITE); layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) })
        val notifySwitch = CheckBox(this).apply { isChecked = notifyNewReleases }
        notifyBox.addView(notifySwitch); profileCard.addView(notifyBox)
        val saveProfileBtn = Button(this).apply { text = "Guardar Cambios"; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#130F1C")); background = makeRoundedDrawable("#76E6D5", "#92F5E6", 12); setOnClickListener { val newName = nameInput.text.toString().trim(); val newAv = avInput.text.toString().trim(); val newNotif = notifySwitch.isChecked; thread { val body = JSONObject().apply { put("display_name", newName); put("avatar_url", newAv); put("notify_new_releases", newNotif) }; val (code, _) = request("PUT", "/auth/profile", body.toString()); if (code in 200..299) { userName = newName; userAvatar = newAv; notifyNewReleases = newNotif; prefs.edit().putString("user_name", newName).putString("user_avatar", newAv).putBoolean("notify_releases", newNotif).apply(); runOnUiThread { toast("Perfil actualizado") } } } } }
        profileCard.addView(saveProfileBtn); contentContainer.addView(profileCard)
        val noticesCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = makeRoundedDrawable("#181722", "#2B283A", 18); setPadding(30, 26, 30, 26); layoutParams = makeMarginParams(0, 16) }
        noticesCard.addView(TextView(this).apply { text = "Avisos Recientes"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 12) })
        noticesCard.addView(Button(this).apply { text = "Ver actualizaciones"; textSize = 12f; setTextColor(Color.WHITE); background = makeRoundedDrawable("#342E4A", "#4E466D", 10); setOnClickListener { showNoticesDialog() } })
        contentContainer.addView(noticesCard)
        val securityCard = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = makeRoundedDrawable("#181722", "#2B283A", 18); setPadding(30, 26, 30, 26); layoutParams = makeMarginParams(0, 16) }
        securityCard.addView(TextView(this).apply { text = "Seguridad"; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); setPadding(0, 0, 0, 12) })
        securityCard.addView(Button(this).apply { 
            text = "Cambiar Contraseña"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#2D2A38", "#3A335E", 12)
            layoutParams = makeMarginParams(0, 8)
            setOnClickListener { showChangePasswordDialog() }
        })
        securityCard.addView(Button(this).apply { text = "Cerrar Sesión"; textSize = 13f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.parseColor("#FF8C94")); background = makeRoundedDrawable("#2B1E22", "#4D2C34", 12); setOnClickListener { logout() } })
        contentContainer.addView(securityCard)
    }

    private fun showChangePasswordDialog() {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Cambiar Contraseña")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(50, 40, 50, 40); setBackgroundColor(Color.parseColor("#15141B")) }
        val p1 = EditText(this).apply { hint = "Nueva contraseña"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8); setPadding(20, 20, 20, 20); inputType = 129; layoutParams = makeMarginParams(0, 10) }
        val p2 = EditText(this).apply { hint = "Confirmar contraseña"; setHintTextColor(Color.GRAY); setTextColor(Color.WHITE); background = makeRoundedDrawable("#0D0C11", "#2D2A38", 8); setPadding(20, 20, 20, 20); inputType = 129; layoutParams = makeMarginParams(0, 10) }
        box.addView(p1); box.addView(p2); b.setView(box)
        b.setPositiveButton("Actualizar") { _, _ ->
            val pass = p1.text.toString(); if (pass.length < 8) { toast("Mínimo 8 caracteres"); return@setPositiveButton }
            if (pass != p2.text.toString()) { toast("Las contraseñas no coinciden"); return@setPositiveButton }
            thread {
                val (code, _) = request("PUT", "/auth/password", JSONObject().apply { put("password", pass) }.toString())
                runOnUiThread { if (code in 200..299) toast("Contraseña actualizada localmente") else toast("Error al cambiar contraseña") }
            }
        }
        b.setNegativeButton("Cancelar", null).show()
    }

    private fun showPresetAvatarDialog() {
        val b = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        b.setTitle("Elige un Avatar")
        val grid = GridLayout(this).apply { columnCount = 3; setPadding(20, 20, 20, 20) }
        for (url in presetAvatars) {
            val img = ImageView(this).apply { layoutParams = GridLayout.LayoutParams().apply { width = (80 * resources.displayMetrics.density).toInt(); height = width; setMargins(10, 10, 10, 10) }; scaleType = ImageView.ScaleType.CENTER_CROP; loadImage(url, this); setOnClickListener { userAvatar = url; avatarPreview?.let { loadImage(url, it) }; toast("Avatar seleccionado") } }
            grid.addView(img)
        }
        b.setView(grid).setPositiveButton("Cerrar", null).show()
    }

    private fun showNoticesDialog() {
        thread {
            val (code, resp) = request("GET", "/notifications", null)
            if (code == 200) {
                val arr = JSONArray(resp)
                runOnUiThread {
                    val b = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                    b.setTitle("Avisos de Capítulos")
                    if (arr.length() == 0) b.setMessage("No hay avisos nuevos por ahora.")
                    else {
                        val list = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 20, 40, 20) }
                        for (i in 0 until arr.length()) {
                            val n = arr.getJSONObject(i)
                            val rawDate = n.optString("created_at", n.optString("time", "Fecha no disponible"))
                            val dateLabel = try {
                                val instant = java.time.Instant.parse(rawDate)
                                java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                                    .withZone(java.time.ZoneId.systemDefault()).format(instant)
                            } catch (_: Exception) { rawDate }
                            list.addView(TextView(this@MainActivity).apply {
                                text = "${n.getString("title")}\n${n.getString("message")}\nActualizado: $dateLabel\n"
                                setTextColor(Color.WHITE); setPadding(0, 10, 0, 10)
                            })
                        }
                        b.setView(ScrollView(this@MainActivity).apply { addView(list) })
                    }
                    b.setPositiveButton("Entendido", null).show()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null) {
            data.data?.let { userAvatar = it.toString(); avatarPreview?.setImageURI(it); prefs.edit().putString("user_avatar", userAvatar).apply(); toast("Imagen cargada") }
        }
    }

    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        if (getBaseUrl() == "local") return handleLocalRequest(method, path, body)
        val conn = URL(getBaseUrl() + path).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 12000; conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toByteArray()) } }
        val code = conn.responseCode
        val responseText = (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to responseText
    }

    private fun getQueryParam(path: String, key: String): String? {
        val query = path.substringAfter("?", ""); if (query.isEmpty()) return null
        for (p in query.split("&")) { val pair = p.split("="); if (pair.size == 2 && pair[0] == key) return URLDecoder.decode(pair[1], "UTF-8") }
        return null
    }

    private fun handleLocalRequest(method: String, path: String, body: String?): Pair<Int, String> {
        val db = LocalDatabaseHelper(this).writableDatabase
        try {
            if (path == "/auth/register" && method == "POST") {
                val json = JSONObject(body ?: "{}"); val email = json.optString("email", "").lowercase().trim()
                if (email.isEmpty()) return 422 to "{\"detail\":\"Email requerido\"}"
                val cursor = db.rawQuery("SELECT id FROM users WHERE email = ?", arrayOf(email))
                if (cursor.moveToFirst()) { cursor.close(); return 409 to "{\"detail\":\"Ya registrado\"}" }
                cursor.close(); db.insert("users", null, android.content.ContentValues().apply { put("email", email); put("display_name", json.optString("display_name", "").trim()); put("avatar_url", defaultAnimalAvatar(email)) })
                return 200 to "{\"access_token\":\"LOCAL_TOKEN\"}"
            }
            if (path == "/auth/password" && method == "PUT") {
                return 200 to "{}"
            }
            if (path == "/auth/login" && method == "POST") {
                val email = JSONObject(body ?: "{}").optString("email", "").lowercase().trim()
                val cursor = db.rawQuery("SELECT id, display_name, avatar_url FROM users WHERE email = ?", arrayOf(email))
                if (cursor.moveToFirst()) {
                    prefs.edit().apply { putString("user_name", cursor.getString(1)); putString("user_email", email); putString("user_avatar", cursor.getString(2)) }.apply()
                    cursor.close(); return 200 to "{\"access_token\":\"LOCAL_TOKEN\"}"
                }
                cursor.close(); return 401 to "{\"detail\":\"No encontrado\"}"
            }
            if (path == "/auth/profile" && method == "PUT") {
                val json = JSONObject(body ?: "{}"); val name = json.optString("display_name", ""); val av = json.optString("avatar_url", "")
                prefs.edit().apply { if (name.isNotEmpty()) putString("user_name", name); if (av.isNotEmpty()) putString("user_avatar", av) }.apply()
                db.execSQL("UPDATE users SET display_name = ?, avatar_url = ? WHERE email = ?", arrayOf(name, av, prefs.getString("user_email", "")))
                return 200 to "{}"
            }
            if (path.startsWith("/library") && method == "GET") {
                val arr = JSONArray(); val fStatus = getQueryParam(path, "status"); val fCat = getQueryParam(path, "media_type")
                val fYears = getQueryParam(path, "year")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val inc = getQueryParam(path, "include_genres")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val exc = getQueryParam(path, "exclude_genres")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val fMediaStatus = getQueryParam(path, "media_status")
                val fAge = getQueryParam(path, "age_rating")
                
                var sql = "SELECT m.id, m.title, m.category, m.synopsis, m.image_url, m.genres, l.status, l.progress, l.rating, l.notes, l.total_units, m.total_units, m.seasons, m.release_year, m.airing_status, m.age_rating, m.rating_avg, m.author FROM library l JOIN media m ON l.media_id = m.id WHERE 1=1"
                val paramsList = mutableListOf<String>()
                
                if (fStatus != null && fStatus != "all") { sql += " AND l.status = ?"; paramsList.add(fStatus) }
                if (fCat != null && fCat != "all") { sql += " AND m.category = ?"; paramsList.add(fCat) }
                
                if (fYears.isNotEmpty()) {
                    val placeholders = fYears.joinToString(",") { "?" }
                    sql += " AND m.release_year IN ($placeholders)"
                    paramsList.addAll(fYears)
                }
                
                if (!fMediaStatus.isNullOrBlank()) {
                    val aliases = mediaStatusAliases(fMediaStatus)
                    sql += " AND LOWER(m.airing_status) IN (${aliases.joinToString(",") { "?" }})"
                    paramsList.addAll(aliases)
                }
                if (!fAge.isNullOrBlank()) { sql += " AND LOWER(COALESCE(NULLIF(m.age_rating, ''), 'safe')) = ?"; paramsList.add(fAge.lowercase()) }
                
                val cursor = db.rawQuery(sql, if (paramsList.isEmpty()) null else paramsList.toTypedArray())
                while (cursor.moveToNext()) {
                    val m = normalizeMediaJson(JSONObject().apply { put("id", cursor.getString(0)); put("title", cursor.getString(1)); put("category", cursor.getString(2)); put("media_type", cursor.getString(2)); put("synopsis", cursor.getString(3)); put("image_url", cursor.getString(4)); put("genres", JSONArray(cursor.getString(5).split(","))); put("total_units", cursor.getInt(11)); put("seasons", cursor.getInt(12)); put("release_year", cursor.getInt(13)); put("airing_status", cursor.getString(14)); put("age_rating", cursor.getString(15)); put("rating_avg", cursor.getDouble(16)); put("author", cursor.getString(17)) })
                    if (!matchesExternalFilters(m, fYears, inc, exc, fMediaStatus, fAge)) continue
                    arr.put(JSONObject().apply { put("media_id", cursor.getString(0)); put("status", cursor.getString(6)); put("progress", cursor.getInt(7)); put("rating", if (cursor.isNull(8)) JSONObject.NULL else cursor.getDouble(8)); put("notes", if (cursor.isNull(9)) "" else cursor.getString(9)); put("total", if (cursor.isNull(10)) JSONObject.NULL else cursor.getInt(10)); put("media", m) })
                }
                cursor.close(); return 200 to arr.toString()
            }
            if (path.startsWith("/library") && (method == "POST" || method == "PUT")) {
                val parts = path.split("/"); val mId = if (parts.size > 2) parts[2] else JSONObject(body ?: "{}").getString("media_id")
                val json = JSONObject(body ?: "{}"); val s = json.optString("status", "planned"); val p = json.optInt("progress", 0); val t = if (json.has("total")) json.getInt("total") else null
                db.execSQL("INSERT OR IGNORE INTO library (media_id, status, progress, total_units) VALUES (?, ?, ?, ?)", arrayOf(mId, s, p, t))
                db.execSQL("UPDATE library SET status = ?, progress = ?, total_units = COALESCE(?, total_units) WHERE media_id = ?", arrayOf(s, p, t, mId))
                return 200 to "{}"
            }
            if (path.startsWith("/library/") && path.endsWith("/progress") && method == "POST") {
                val mId = path.split("/")[2]; val v = JSONObject(body ?: "{}").getInt("value")
                db.execSQL("UPDATE library SET progress = ? WHERE media_id = ?", arrayOf(v, mId)); return 200 to "{}"
            }
            if (path.startsWith("/library/") && method == "DELETE") { db.execSQL("DELETE FROM library WHERE media_id = ?", arrayOf(path.substringAfter("/library/"))); return 200 to "{}" }
            if (path.startsWith("/search") && method == "GET") {
                val q = getQueryParam(path, "query") ?: ""; val cat = getQueryParam(path, "media_type") ?: "all"
                val fYears = getQueryParam(path, "year")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val inc = getQueryParam(path, "include_genres")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val exc = getQueryParam(path, "exclude_genres")?.split(",")?.filter { it.isNotEmpty() } ?: emptyList()
                val fMediaStatus = getQueryParam(path, "media_status")
                val fAge = getQueryParam(path, "age_rating")
                
                val arr = JSONArray()
                var sql = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg, author FROM media WHERE LOWER(title) LIKE ?"
                val paramsList = mutableListOf("%${q.lowercase()}%")
                
                if (cat != "all") { sql += " AND category = ?"; paramsList.add(cat) }
                if (fYears.isNotEmpty()) {
                    val placeholders = fYears.joinToString(",") { "?" }
                    sql += " AND release_year IN ($placeholders)"
                    paramsList.addAll(fYears)
                }
                if (!fMediaStatus.isNullOrBlank()) {
                    val aliases = mediaStatusAliases(fMediaStatus)
                    sql += " AND LOWER(airing_status) IN (${aliases.joinToString(",") { "?" }})"
                    paramsList.addAll(aliases)
                }
                if (!fAge.isNullOrBlank()) { sql += " AND LOWER(COALESCE(NULLIF(age_rating, ''), 'safe')) = ?"; paramsList.add(fAge.lowercase()) }
                
                val c = db.rawQuery(sql, paramsList.toTypedArray())
                while (c.moveToNext()) {
                    val mId = c.getString(0)
                    val libCheck = db.rawQuery("SELECT 1 FROM library WHERE media_id = ?", arrayOf(mId))
                    val inLibrary = libCheck.moveToFirst()
                    libCheck.close()
                    
                    val localItem = normalizeMediaJson(JSONObject().apply {
                        put("id", mId); put("title", c.getString(1)); put("category", c.getString(2)); put("media_type", c.getString(2)); put("synopsis", c.getString(3)); put("image_url", c.getString(4)); put("genres", JSONArray(c.getString(5).split(","))); put("total_units", c.getInt(6)); put("seasons", c.getInt(7)); put("release_year", c.getInt(8)); put("airing_status", c.getString(9)); put("age_rating", c.getString(10)); put("rating_avg", c.getDouble(11)); put("author", c.getString(12)); put("source", "local"); put("in_library", inLibrary) 
                    })
                    if (!matchesExternalFilters(localItem, fYears, inc, exc, fMediaStatus, fAge)) continue
                    arr.put(localItem)
                }
                c.close()
                
                if (arr.length() < 10 && (q.length > 2 || q.isBlank())) {
                    // Permitir búsquedas sólo por filtros. Sin texto usamos los
                    // rankings de cada fuente como conjunto de candidatos.
                    val ext = if (q.isBlank()) discoverTopLocalContent(cat) else performExternalSearch(q, cat)
                    for (i in 0 until ext.length()) { 
                        val item = ext.getJSONObject(i)
                        normalizeMediaJson(item)
                        if (!matchesExternalFilters(item, fYears, inc, exc, fMediaStatus, fAge)) continue
                        var dup = false
                        for (j in 0 until arr.length()) { if (arr.getJSONObject(j).getString("title").equals(item.getString("title"), true)) { dup = true; break } }
                        if (!dup) {
                            val extId = item.optString("id")
                            val libCheck = db.rawQuery("SELECT 1 FROM library WHERE media_id = ?", arrayOf(extId))
                            item.put("in_library", libCheck.moveToFirst())
                            libCheck.close()
                            arr.put(item) 
                        }
                    }
                }
                return 200 to arr.toString()
            }
            if (path.startsWith("/recommendations") && method == "GET") {
                val cat = getQueryParam(path, "media_type") ?: "all"; val arr = JSONArray()
                val excludedIds = getQueryParam(path, "exclude_ids")?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
                val libCursor = db.rawQuery("SELECT COUNT(*) FROM library", null)
                val isLibraryEmpty = if (libCursor.moveToFirst()) libCursor.getInt(0) == 0 else true
                libCursor.close()
                if (isLibraryEmpty) {
                    val catalogCount = db.rawQuery(
                        if (cat == "all") "SELECT COUNT(DISTINCT category) FROM media" else "SELECT COUNT(*) FROM media WHERE category = ?",
                        if (cat == "all") null else arrayOf(cat)
                    )
                    val availableCatalog = if (catalogCount.moveToFirst()) catalogCount.getInt(0) else 0
                    catalogCount.close()
                    if ((cat == "all" && availableCatalog < 8) || (cat != "all" && availableCatalog < 5)) {
                        val discovered = discoverTopLocalContent(cat)
                        for (index in 0 until discovered.length()) persistLocalMedia(db, discovered.getJSONObject(index))
                    }
                    var sqlFall = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg, author FROM media"
                    val paramsList = mutableListOf<String>()
                    if (cat != "all") { sqlFall += " WHERE category = ?"; paramsList.add(cat) }
                    if (excludedIds.isNotEmpty()) {
                        sqlFall += (if (paramsList.isEmpty()) " WHERE" else " AND") + " id NOT IN (${excludedIds.joinToString(",") { "?" }})"
                        paramsList.addAll(excludedIds)
                    }
                    sqlFall += " ORDER BY COALESCE(rating_avg, 0) DESC, title COLLATE NOCASE"
                    if (cat != "all") sqlFall += " LIMIT 10"
                    val cF = db.rawQuery(sqlFall, if (paramsList.isEmpty()) null else paramsList.toTypedArray())
                    val recommendedTypes = mutableSetOf<String>()
                    while (cF.moveToNext() && arr.length() < 10) {
                        val itemType = cF.getString(2)
                        if (cat == "all" && !recommendedTypes.add(itemType)) continue
                        arr.put(JSONObject().apply {
                            put("reason", "De los títulos mejor calificados en ${itemType.uppercase()}")
                            put("score", cF.getDouble(11))
                            put("media", JSONObject().apply {
                                put("id", cF.getString(0)); put("title", cF.getString(1)); put("category", itemType); put("media_type", itemType)
                                put("synopsis", cF.getString(3)); put("image_url", cF.getString(4)); put("genres", JSONArray(cF.getString(5).split(",")))
                                put("total_units", cF.getInt(6)); put("seasons", cF.getInt(7)); put("release_year", cF.getInt(8)); put("airing_status", cF.getString(9))
                                put("age_rating", cF.getString(10)); put("rating_avg", cF.getDouble(11)); put("author", cF.getString(12))
                            })
                        })
                    }
                    cF.close()
                } else {
                    var sql = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg, author FROM media WHERE id NOT IN (SELECT media_id FROM library)"
                    val paramsList = mutableListOf<String>(); if (cat != "all") { sql += " AND category = ?"; paramsList.add(cat) }
                    if (excludedIds.isNotEmpty()) { sql += " AND id NOT IN (${excludedIds.joinToString(",") { "?" }})"; paramsList.addAll(excludedIds) }
                    sql += " ORDER BY RANDOM() LIMIT 8"
                    var c = db.rawQuery(sql, if (paramsList.isEmpty()) null else paramsList.toTypedArray())
                    while (c.moveToNext()) arr.put(JSONObject().apply { put("reason", "Sugerencia del sistema"); put("score", c.getDouble(11)); put("media", JSONObject().apply { put("id", c.getString(0)); put("title", c.getString(1)); put("category", c.getString(2)); put("media_type", c.getString(2)); put("synopsis", c.getString(3)); put("image_url", c.getString(4)); put("genres", JSONArray(c.getString(5).split(","))); put("total_units", c.getInt(6)); put("seasons", c.getInt(7)); put("release_year", c.getInt(8)); put("airing_status", c.getString(9)); put("author", c.getString(12)) }) })
                    c.close()
                    if (arr.length() == 0) {
                        var sqlFall = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg, author FROM media"
                        val paramsList2 = mutableListOf<String>()
                        if (cat != "all") { sqlFall += " WHERE category = ?"; paramsList2.add(cat) }
                        if (excludedIds.isNotEmpty()) {
                            sqlFall += (if (paramsList2.isEmpty()) " WHERE" else " AND") + " id NOT IN (${excludedIds.joinToString(",") { "?" }})"
                            paramsList2.addAll(excludedIds)
                        }
                        sqlFall += " ORDER BY RANDOM() LIMIT 10"
                        val cF = db.rawQuery(sqlFall, if (paramsList2.isEmpty()) null else paramsList2.toTypedArray())
                        while (cF.moveToNext()) arr.put(JSONObject().apply { put("reason", "Top Valorados"); put("score", cF.getDouble(11)); put("media", JSONObject().apply { put("id", cF.getString(0)); put("title", cF.getString(1)); put("category", cF.getString(2)); put("media_type", cF.getString(2)); put("synopsis", cF.getString(3)); put("image_url", cF.getString(4)); put("genres", JSONArray(cF.getString(5).split(","))); put("total_units", cF.getInt(6)); put("seasons", cF.getInt(7)); put("release_year", cF.getInt(8)); put("airing_status", cF.getString(9)); put("author", cF.getString(12)) }) })
                        cF.close()
                    }
                }
                return 200 to arr.toString()
            }
            if (path == "/notifications" && method == "GET") {
                val list = JSONArray(); val c = db.rawQuery("SELECT title FROM media WHERE id IN (SELECT media_id FROM library) ORDER BY RANDOM() LIMIT 3", null)
                val sdf = java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                while (c.moveToNext()) {
                    val dateStr = sdf.format(java.util.Date(System.currentTimeMillis() - (0..86400000).random()))
                    list.put(JSONObject().apply { put("id", System.currentTimeMillis() + list.length()); put("title", "Actualización: ${c.getString(0)}"); put("message", "Contenido actualizado el $dateStr."); put("time", dateStr) })
                }
                c.close(); return 200 to list.toString()
            }
            if (path == "/media/import" && method == "POST") {
                val json = normalizeMediaJson(JSONObject(body ?: "{}")); val gList = mutableListOf<String>(); val gArr = json.optJSONArray("genres") ?: JSONArray(); for (i in 0 until gArr.length()) gList.add(gArr.getString(i))
                val mId = json.optString("id", "loc_" + System.currentTimeMillis())
                db.insertWithOnConflict("media", null, android.content.ContentValues().apply { put("id", mId); put("title", json.getString("title")); put("category", json.getString("category")); put("synopsis", json.optString("synopsis", "")); put("image_url", json.optString("image_url", "")); put("genres", gList.joinToString(",")); put("total_units", json.optInt("total_units", 0)); put("seasons", json.optInt("seasons", 1)); put("release_year", json.optInt("release_year", 0)); put("airing_status", json.optString("airing_status", "finished")); put("age_rating", json.optString("age_rating", "safe")); put("rating_avg", json.optDouble("rating_avg", 0.0)); put("author", json.optString("author", "")) }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                return 200 to json.apply { put("id", mId) }.toString()
            }
        } catch (e: Exception) { return 500 to "{\"detail\":\"${e.localizedMessage}\"}" }
        return 404 to "{}"
    }

    private fun performExternalSearch(q: String, category: String): JSONArray {
        val results = JSONArray()
        when (category) {
            "anime" -> { results.putAll(searchAnilist(q, "ANIME")); results.putAll(searchJikan(q, "anime")) }
            "manga" -> { results.putAll(searchAnilist(q, "MANGA")); results.putAll(searchJikan(q, "manga")) }
            "movie" -> { results.putAll(searchiTunes(q, "movie")); results.putAll(searchIMDb(q, "movie")) }
            "series" -> { results.putAll(searchTVMaze(q)) }
            "book" -> { results.putAll(searchOpenLibrary(q)); results.putAll(searchGoogleBooks(q, null)) }
            "music" -> { results.putAll(searchiTunes(q, "music")); results.putAll(searchDeezer(q)) }
            "comic" -> { results.putAll(searchGoogleBooks(q, "comics")); results.putAll(searchOpenLibrary(q, "comic")) }
            "game" -> { results.putAll(searchFreeToGame(q)) }
            "all" -> { 
                results.putAll(searchAnilist(q, "ANIME"))
                results.putAll(searchiTunes(q, "movie"))
                results.putAll(searchIMDb(q, "all"))
                results.putAll(searchTVMaze(q))
                results.putAll(searchFreeToGame(q))
            }
        }
        return results
    }

    private fun persistLocalMedia(db: android.database.sqlite.SQLiteDatabase, media: JSONObject) {
        val item = normalizeMediaJson(media)
        val genres = item.optJSONArray("genres") ?: JSONArray()
        val genreValues = List(genres.length()) { genres.optString(it) }.filter { it.isNotBlank() }
        db.insertWithOnConflict("media", null, android.content.ContentValues().apply {
            put("id", item.optString("id", "local_${System.currentTimeMillis()}"))
            put("title", item.optString("title", "Sin título"))
            put("category", item.optString("category", "other"))
            put("synopsis", item.optString("synopsis", ""))
            put("image_url", item.optString("image_url", ""))
            put("genres", genreValues.joinToString(","))
            put("total_units", item.optInt("total_units", 0))
            put("seasons", item.optInt("seasons", 1))
            put("release_year", item.optInt("release_year", 0))
            put("airing_status", item.optString("airing_status", "finished"))
            put("age_rating", item.optString("age_rating", "safe"))
            put("rating_avg", item.optDouble("rating_avg", 0.0))
            put("author", item.optString("author", ""))
        }, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
    }

    private fun discoverTopLocalContent(category: String): JSONArray {
        val categories = if (category == "all") {
            listOf("anime", "manga", "movie", "series", "book", "music", "comic", "game")
        } else listOf(category)
        val discovered = java.util.concurrent.ConcurrentHashMap<String, JSONArray>()
        val executor = java.util.concurrent.Executors.newFixedThreadPool(minOf(4, categories.size))
        categories.forEach { mediaType ->
            executor.submit {
                val values = try {
                    when (mediaType) {
                        "anime" -> searchTopAnilist("ANIME")
                        "manga" -> searchTopAnilist("MANGA")
                        "movie" -> searchTopApple("movie")
                        "series" -> searchTopTVMaze()
                        "book" -> searchTopOpenLibrary(false)
                        "music" -> searchTopApple("music")
                        "comic" -> searchTopOpenLibrary(true)
                        "game" -> searchFreeToGame("")
                        else -> JSONArray()
                    }
                } catch (_: Exception) { JSONArray() }
                discovered[mediaType] = values
            }
        }
        executor.shutdown()
        try { executor.awaitTermination(35, java.util.concurrent.TimeUnit.SECONDS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        val result = JSONArray()
        categories.forEach { mediaType ->
            val values = discovered[mediaType] ?: JSONArray()
            val limit = if (category == "all") minOf(values.length(), 4) else minOf(values.length(), 10)
            for (index in 0 until limit) result.put(values.getJSONObject(index))
        }
        return result
    }

    private fun searchTopAnilist(type: String): JSONArray {
        val arr = JSONArray()
        val query = "query(\$type: MediaType) { Page(page: 1, perPage: 10) { media(type: \$type, sort: [SCORE_DESC, POPULARITY_DESC], isAdult: false) { id title { romaji english } description coverImage { large } genres type episodes chapters startDate { year } status averageScore studios(isMain: true) { nodes { name } } staff(perPage: 3) { nodes { name { full } } } } } }"
        try {
            val response = remotePost("https://graphql.anilist.co", JSONObject().apply {
                put("query", query)
                put("variables", JSONObject().put("type", type))
            }.toString())
            val data = JSONObject(response).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
            for (index in 0 until data.length()) {
                val value = data.getJSONObject(index)
                val title = value.getJSONObject("title")
                val studio = value.optJSONObject("studios")?.optJSONArray("nodes")?.optJSONObject(0)?.optString("name").orEmpty()
                val staff = value.optJSONObject("staff")?.optJSONArray("nodes")?.optJSONObject(0)?.optJSONObject("name")?.optString("full").orEmpty()
                arr.put(JSONObject().apply {
                    put("id", "ani_${value.getInt("id")}")
                    put("title", title.optString("english").ifBlank { title.optString("romaji", "Sin título") })
                    put("category", if (type == "ANIME") "anime" else "manga")
                    put("author", if (type == "ANIME") studio.ifBlank { staff } else staff)
                    put("synopsis", value.optString("description", "").replace(Regex("<.*?>"), ""))
                    put("image_url", value.optJSONObject("coverImage")?.optString("large", ""))
                    put("genres", value.optJSONArray("genres") ?: JSONArray())
                    put("total_units", if (type == "ANIME") value.optInt("episodes", 0) else value.optInt("chapters", 0))
                    put("release_year", value.optJSONObject("startDate")?.optInt("year", 0) ?: 0)
                    put("airing_status", value.optString("status", ""))
                    put("rating_avg", value.optDouble("averageScore", 0.0) / 10.0)
                    put("source", "anilist")
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchTopTVMaze(): JSONArray {
        val arr = JSONArray()
        try {
            val data = JSONArray(remoteGet("https://api.tvmaze.com/shows?page=0"))
            val sorted = (0 until data.length()).map { data.getJSONObject(it) }
                .sortedByDescending { it.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0 }
                .take(10)
            sorted.forEach { value ->
                arr.put(JSONObject().apply {
                    put("id", "tvm_${value.getInt("id")}")
                    put("title", value.optString("name", "Sin título"))
                    put("category", "series")
                    put("author", value.optJSONObject("network")?.optString("name") ?: value.optJSONObject("webChannel")?.optString("name") ?: "TV")
                    put("synopsis", value.optString("summary", "").replace(Regex("<.*?>"), ""))
                    put("image_url", value.optJSONObject("image")?.optString("original", ""))
                    put("genres", value.optJSONArray("genres") ?: JSONArray())
                    put("release_year", extractYear(value.optString("premiered")))
                    put("airing_status", value.optString("status", ""))
                    put("rating_avg", value.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0)
                    put("source", "tvmaze")
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchTopApple(type: String): JSONArray {
        val arr = JSONArray()
        try {
            if (type == "movie") {
                val data = JSONObject(remoteGet("https://itunes.apple.com/us/rss/topmovies/limit=10/json"))
                    .optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
                for (index in 0 until data.length()) {
                    val value = data.getJSONObject(index)
                    val images = value.optJSONArray("im:image") ?: JSONArray()
                    val image = if (images.length() > 0) images.optJSONObject(images.length() - 1)?.optString("label", "").orEmpty() else ""
                    val identifier = value.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id", index.toString()) ?: index.toString()
                    val genre = value.optJSONObject("category")?.optJSONObject("attributes")?.optString("label", "Cine") ?: "Cine"
                    arr.put(JSONObject().apply {
                        put("id", "apple_movie_$identifier")
                        put("title", value.optJSONObject("im:name")?.optString("label", "Sin título") ?: "Sin título")
                        put("category", "movie")
                        put("author", value.optJSONObject("im:artist")?.optString("label", "Apple Movies") ?: "Apple Movies")
                        put("synopsis", value.optJSONObject("summary")?.optString("label", "Película destacada en Apple.") ?: "Película destacada en Apple.")
                        put("image_url", image)
                        put("genres", JSONArray().put(genre))
                        put("release_year", extractYear(value.optJSONObject("im:releaseDate")?.optString("label")))
                        put("rating_avg", (9.8 - index * 0.1).coerceAtLeast(8.0))
                        put("source", "itunes_rss")
                    })
                }
                return arr
            }
            val data = JSONObject(remoteGet("https://rss.marketingtools.apple.com/api/v2/us/music/most-played/10/songs.json"))
                .optJSONObject("feed")?.optJSONArray("results") ?: JSONArray()
            for (index in 0 until data.length()) {
                val value = data.getJSONObject(index)
                val genres = JSONArray()
                value.optJSONArray("genres")?.let { source ->
                    for (genreIndex in 0 until source.length()) genres.put(source.optJSONObject(genreIndex)?.optString("name", ""))
                }
                arr.put(JSONObject().apply {
                    put("id", "apple_${type}_${value.optString("id", index.toString())}")
                    put("title", value.optString("name", "Sin título"))
                    put("category", "music")
                    put("author", value.optString("artistName", "Apple"))
                    put("synopsis", value.optString("description", "Canción destacada en Apple Music."))
                    put("image_url", value.optString("artworkUrl100", "").replace("100x100", "600x600"))
                    put("genres", genres)
                    put("release_year", extractYear(value.optString("releaseDate")))
                    put("rating_avg", (9.8 - index * 0.1).coerceAtLeast(8.0))
                    put("source", "apple_rss")
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchTopOpenLibrary(comics: Boolean): JSONArray {
        val arr = JSONArray()
        try {
            val query = if (comics) "subject:comics" else "subject:fiction"
            val fields = "key,title,author_name,publisher,first_publish_year,cover_i,subject,ratings_average"
            val response = remoteGet("https://openlibrary.org/search.json?q=${URLEncoder.encode(query, "UTF-8")}&sort=rating&limit=10&fields=${URLEncoder.encode(fields, "UTF-8")}")
            val data = JSONObject(response).optJSONArray("docs") ?: JSONArray()
            for (index in 0 until data.length()) {
                val value = data.getJSONObject(index)
                val authors = value.optJSONArray("author_name")?.let { names -> List(names.length()) { names.optString(it) }.joinToString(", ") }
                    ?: value.optJSONArray("publisher")?.optString(0).orEmpty()
                val coverId = value.optInt("cover_i", -1)
                arr.put(JSONObject().apply {
                    put("id", "olb_${value.optString("key").substringAfterLast("/")}")
                    put("title", value.optString("title", "Sin título"))
                    put("category", if (comics) "comic" else "book")
                    put("author", authors)
                    put("synopsis", if (comics) "Cómic destacado por sus valoraciones." else "Libro destacado por sus valoraciones.")
                    put("image_url", if (coverId >= 0) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else "")
                    put("genres", value.optJSONArray("subject") ?: JSONArray())
                    put("release_year", value.optInt("first_publish_year", 0))
                    put("rating_avg", value.optDouble("ratings_average", 0.0) * 2.0)
                    put("source", "openlibrary")
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchJikan(q: String, type: String): JSONArray {
        val arr = JSONArray()
        try {
            val resp = remoteGet("https://api.jikan.moe/v4/$type?q=${URLEncoder.encode(q, "UTF-8")}&limit=5")
            val data = JSONObject(resp).optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                arr.put(JSONObject().apply {
                    put("id", "jik_" + x.getInt("mal_id"))
                    put("title", x.getString("title"))
                    put("category", if (type == "anime") "anime" else "manga")
                    put("author", x.optJSONArray("authors")?.optJSONObject(0)?.optString("name") ?: x.optJSONArray("studios")?.optJSONObject(0)?.optString("name") ?: "")
                    put("synopsis", x.optString("synopsis", ""))
                    put("image_url", x.getJSONObject("images").getJSONObject("jpg").optString("large_image_url"))
                    put("genres", JSONArray().apply { x.optJSONArray("genres")?.let { for(j in 0 until it.length()) put(it.getJSONObject(j).getString("name")) } })
                    put("release_year", if (x.optInt("year", 0) > 0) x.optInt("year") else extractYear(
                        x.optJSONObject("aired")?.optString("from"),
                        x.optJSONObject("published")?.optString("from")
                    ))
                    put("rating_avg", x.optDouble("score", 0.0))
                    put("source", "jikan")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchGoogleBooks(q: String, subject: String?): JSONArray {
        val arr = JSONArray()
        try {
            var url = "https://www.googleapis.com/books/v1/volumes?q=${URLEncoder.encode(q, "UTF-8")}"
            if (subject != null) url += "+subject:$subject"
            val resp = remoteGet(url + "&maxResults=10")
            val items = JSONObject(resp).optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val x = items.getJSONObject(i).getJSONObject("volumeInfo")
                arr.put(JSONObject().apply {
                    put("id", "gob_" + items.getJSONObject(i).getString("id"))
                    put("title", x.getString("title"))
                    put("category", if (subject == "comics") "comic" else "book")
                    put("author", x.optJSONArray("authors")?.optString(0) ?: x.optString("publisher", ""))
                    put("synopsis", x.optString("description", ""))
                    put("image_url", x.optJSONObject("imageLinks")?.optString("thumbnail"))
                    put("genres", x.optJSONArray("categories") ?: JSONArray())
                    put("release_year", extractYear(x.optString("publishedDate")))
                    put("rating_avg", x.optDouble("averageRating", 0.0) * 2.0)
                    put("source", "google_books")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchDeezer(q: String): JSONArray {
        val arr = JSONArray()
        try {
            val resp = remoteGet("https://api.deezer.com/search?q=${URLEncoder.encode(q, "UTF-8")}&limit=5")
            val data = JSONObject(resp).optJSONArray("data") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                arr.put(JSONObject().apply {
                    put("id", "dee_" + x.getLong("id"))
                    put("title", x.getString("title") + " - " + x.getJSONObject("artist").getString("name"))
                    put("category", "music")
                    put("author", x.getJSONObject("artist").getString("name"))
                    put("image_url", x.getJSONObject("album").optString("cover_xl"))
                    put("source", "deezer")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchFreeToGame(q: String): JSONArray {
        val arr = JSONArray()
        try {
            val resp = remoteGet("https://www.freetogame.com/api/games?sort-by=popularity")
            val data = JSONArray(resp)
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                val searchable = listOf(
                    x.optString("title"), x.optString("genre"),
                    x.optString("publisher"), x.optString("developer")
                ).joinToString(" ").lowercase()
                if (q.isNotBlank() && !searchable.contains(q.trim().lowercase())) continue
                arr.put(JSONObject().apply {
                    put("id", "ftg_" + x.getInt("id"))
                    put("title", x.getString("title"))
                    put("category", "game")
                    put("author", x.optString("publisher", x.optString("developer", "")))
                    put("synopsis", x.optString("short_description", ""))
                    put("image_url", x.optString("thumbnail", ""))
                    put("genres", JSONArray().put(x.optString("genre", "Videojuego")))
                    put("release_year", extractYear(x.optString("release_date")))
                    put("airing_status", "finished")
                    put("rating_avg", (9.5 - (i.coerceAtMost(70) * 0.05)).coerceAtLeast(6.0))
                    put("source", "freetogame")
                })
                if (arr.length() >= 12) break
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchAnilist(q: String, type: String): JSONArray {
        val arr = JSONArray(); val query = "query(\$search: String, \$type: MediaType) { Page(perPage: 8) { media(search: \$search, type: \$type) { id title { romaji english } description coverImage { large } genres type episodes seasonYear startDate { year } status averageScore studios(isMain: true) { nodes { name } } staff(perPage: 5) { edges { role node { name { full } } } } } } }"
        try {
            val resp = remotePost("https://graphql.anilist.co", JSONObject().apply { put("query", query); put("variables", JSONObject().apply { put("search", q); put("type", type) }) }.toString())
            val data = JSONObject(resp).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                val edges = x.optJSONObject("staff")?.optJSONArray("edges")
                var author = ""
                if (edges != null && edges.length() > 0) {
                    for (j in 0 until edges.length()) {
                        val edge = edges.getJSONObject(j)
                        val role = edge.optString("role").uppercase()
                        if (role.contains("STORY") || role.contains("ART") || role.contains("DIRECTOR") || role.contains("ORIGINAL")) {
                            author = edge.getJSONObject("node").getJSONObject("name").optString("full")
                            break
                        }
                    }
                    if (author.isEmpty()) author = edges.getJSONObject(0).getJSONObject("node").getJSONObject("name").optString("full")
                }
                val studio = x.optJSONObject("studios")?.optJSONArray("nodes")?.optJSONObject(0)?.optString("name") ?: ""
                if (type == "ANIME" && studio.isNotEmpty()) author = studio
                arr.put(JSONObject().apply { 
                    put("id", "ani_" + x.getString("id")); put("title", x.getJSONObject("title").optString("romaji", x.getJSONObject("title").optString("english")))
                    put("category", if (x.getString("type") == "ANIME") "anime" else "manga")
                    put("author", author)
                    put("synopsis", x.optString("description", "").replace(Regex("<.*?>"), ""))
                    put("image_url", x.optJSONObject("coverImage")?.optString("large")); put("genres", x.optJSONArray("genres") ?: JSONArray())
                    put("total_units", x.optInt("episodes", 0)); put("release_year", if (x.optInt("seasonYear", 0) > 0) x.optInt("seasonYear") else x.optJSONObject("startDate")?.optInt("year", 0) ?: 0); put("airing_status", x.optString("status")); put("rating_avg", x.optDouble("averageScore", 0.0) / 10.0); put("source", "anilist")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchiTunes(q: String, media: String): JSONArray {
        val arr = JSONArray()
        try {
            // entity=movie asegura mejores resultados para CINE, song para musica
            val entity = if (media == "movie") "movie" else "song"
            val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(q, "UTF-8")}&media=$media&entity=$entity&limit=15"
            val resp = remoteGet(url)
            val data = JSONObject(resp).optJSONArray("results") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                val isM = media == "music"
                val artist = x.optString("artistName", x.optString("primaryGenreName", "Varios"))
                arr.put(JSONObject().apply { 
                    put("id", "itu_" + (x.optString("trackId", x.optString("collectionId", "0"))))
                    put("title", if (isM) "${x.optString("trackName", "Sin Título")} - $artist" else x.optString("trackName", x.optString("collectionName", "Sin Título")))
                    put("category", if (isM) "music" else "movie")
                    put("author", artist)
                    put("synopsis", x.optString("longDescription", x.optString("description", "Obra distribuida por $artist")))
                    put("image_url", x.optString("artworkUrl100").replace("100x100", "600x600"))
                    put("genres", JSONArray().apply { put(x.optString("primaryGenreName")) })
                    put("release_year", extractYear(x.optString("releaseDate")))
                    put("source", "itunes")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchIMDb(q: String, requestedType: String): JSONArray {
        val arr = JSONArray()
        try {
            val resp = remoteGet("https://v2.sg.media-imdb.com/suggestion/x/${URLEncoder.encode(q, "UTF-8")}.json")
            val data = JSONObject(resp).optJSONArray("d") ?: JSONArray()
            for (i in 0 until minOf(data.length(), 15)) {
                val value = data.getJSONObject(i)
                val kind = value.optString("qid")
                val category = when (kind) {
                    "movie", "video", "short" -> "movie"
                    "tvSeries", "tvMiniSeries" -> "series"
                    else -> continue
                }
                if (requestedType != "all" && requestedType != category) continue
                arr.put(JSONObject().apply {
                    put("id", "imdb_${value.optString("id")}")
                    put("external_id", value.optString("id"))
                    put("source", "imdb")
                    put("title", value.optString("l", q))
                    put("category", category)
                    put("media_type", category)
                    put("author", "")
                    put("synopsis", value.optString("s", ""))
                    put("image_url", value.optJSONObject("i")?.optString("imageUrl", ""))
                    put("genres", JSONArray())
                    put("release_year", value.optInt("y", 0))
                })
            }
        } catch (_: Exception) {}
        return arr
    }

    private fun searchTVMaze(q: String): JSONArray {
        val arr = JSONArray()
        try {
            val resp = remoteGet("https://api.tvmaze.com/search/shows?q=${URLEncoder.encode(q, "UTF-8")}")
            val data = JSONArray(resp)
            for (i in 0 until minOf(data.length(), 8)) {
                val x = data.getJSONObject(i).getJSONObject("show")
                arr.put(JSONObject().apply { put("id", "tvm_" + x.getString("id")); put("title", x.getString("name")); put("category", "series"); put("author", x.optJSONObject("network")?.optString("name") ?: x.optJSONObject("webChannel")?.optString("name") ?: "TV"); put("synopsis", x.optString("summary", "").replace(Regex("<.*?>"), "")); put("image_url", x.optJSONObject("image")?.optString("medium")); put("genres", x.optJSONArray("genres") ?: JSONArray()); put("release_year", extractYear(x.optString("premiered"))); put("airing_status", x.optString("status")); put("rating_avg", x.optJSONObject("rating")?.optDouble("average", 0.0) ?: 0.0); put("source", "tvmaze") })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchOpenLibrary(q: String, category: String = "book"): JSONArray {
        val arr = JSONArray()
        try {
            val subject = if (category == "comic") "&subject=comics" else ""
            val fields = "key,title,author_name,publisher,first_publish_year,cover_i,subject,ratings_average"
            val resp = remoteGet("https://openlibrary.org/search.json?q=${URLEncoder.encode(q, "UTF-8")}$subject&limit=8&fields=${URLEncoder.encode(fields, "UTF-8")}")
            val data = JSONObject(resp).optJSONArray("docs") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                val authors = x.optJSONArray("author_name")?.let { a -> List(a.length()){ a.getString(it) }.joinToString(", ") } ?: "Desconocido"
                val year = x.optInt("first_publish_year", 0)
                arr.put(JSONObject().apply { 
                    put("id", "olb_" + x.optString("key").substringAfterLast("/"))
                    put("title", x.getString("title"))
                    put("category", category)
                    put("author", authors)
                    put("release_year", year)
                    put("synopsis", "Autor(es): $authors")
                    val coverId = x.optInt("cover_i", -1)
                    put("image_url", if (coverId != -1) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else null)
                    put("genres", x.optJSONArray("subject") ?: JSONArray()) 
                    put("rating_avg", x.optDouble("ratings_average", 0.0) * 2.0)
                    put("source", "openlibrary")
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun remoteGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", "TrackerMedia/1.1 (Android)")
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun remotePost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 12_000
        conn.readTimeout = 20_000
        conn.requestMethod = "POST"; conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.setRequestProperty("User-Agent", "TrackerMedia/1.1 (Android)"); conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun JSONArray.putAll(other: JSONArray) { for (i in 0 until other.length()) this.put(other.get(i)) }

    class LocalDatabaseHelper(context: Context) : android.database.sqlite.SQLiteOpenHelper(context, "umt_local_db.db", null, 3) {
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE, display_name TEXT, avatar_url TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS media (id TEXT PRIMARY KEY, title TEXT, category TEXT, synopsis TEXT, image_url TEXT, genres TEXT, total_units INTEGER, seasons INTEGER, release_year INTEGER, airing_status TEXT, age_rating TEXT, rating_avg REAL, author TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS library (media_id TEXT PRIMARY KEY, status TEXT, progress INTEGER, rating INTEGER, notes TEXT, total_units INTEGER)")
        }
        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) { try { db.execSQL("ALTER TABLE media ADD COLUMN total_units INTEGER DEFAULT 0"); db.execSQL("ALTER TABLE media ADD COLUMN seasons INTEGER DEFAULT 1"); db.execSQL("ALTER TABLE media ADD COLUMN release_year INTEGER"); db.execSQL("ALTER TABLE media ADD COLUMN airing_status TEXT"); db.execSQL("ALTER TABLE media ADD COLUMN age_rating TEXT"); db.execSQL("ALTER TABLE media ADD COLUMN rating_avg REAL") } catch (e: Exception) {} }
            if (oldVersion < 3) { try { db.execSQL("ALTER TABLE media ADD COLUMN author TEXT") } catch (e: Exception) {} }
        }
    }

    private fun toast(msg: String) { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun loadImage(urlStr: String, imageView: ImageView) {
        thread { try { val conn = URL(urlStr).openConnection() as HttpURLConnection; conn.doInput = true; conn.connect(); val bitmap = android.graphics.BitmapFactory.decodeStream(conn.inputStream); runOnUiThread { imageView.setImageBitmap(bitmap) } } catch (e: Exception) {} }
    }

    private fun makeRoundedDrawable(bgColor: String, strokeColor: String, radiusDp: Int): GradientDrawable {
        val r = radiusDp * resources.displayMetrics.density
        return GradientDrawable().apply { setColor(Color.parseColor(bgColor)); setStroke(2, Color.parseColor(strokeColor)); cornerRadius = r }
    }

    private fun makeMarginParams(horizontalDp: Int, verticalDp: Int): LinearLayout.LayoutParams {
        val d = resources.displayMetrics.density
        return LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins((horizontalDp * d).toInt(), (verticalDp * d).toInt(), (horizontalDp * d).toInt(), (verticalDp * d).toInt()) }
    }
}

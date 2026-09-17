package com.umt.tracker

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.Editable
import android.text.TextWatcher
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

    // Navegación
    private var activeTab: String = "library" // "library", "explore", "recs", "settings"
    private var selectedCategory: String = "all" // "all", "anime", "manga", "movie", "series", "book"
    private var currentFilter: String = "all" // "all", "in_progress", "planned", "completed"

    // Filtros de géneros (+ incluir, - excluir)
    private val includeGenres = mutableSetOf<String>()
    private val excludeGenres = mutableSetOf<String>()

    private val popularGenres = listOf(
        "Acción", "Aventura", "Comedia", "Drama", "Fantasía",
        "Ciencia Ficción", "Romance", "Sobrenatural", "Misterio", "Terror",
        "BL", "Isekai", "Josei", "Seinen", "Histórico", "Psicológico", "Música", "Mecha"
    )

    private val presetAvatars = listOf(
        "https://api.dicebear.com/7.x/bottts/svg?seed=Felix",
        "https://api.dicebear.com/7.x/bottts/svg?seed=Luna",
        "https://api.dicebear.com/7.x/bottts/svg?seed=Aiden",
        "https://api.dicebear.com/7.x/bottts/svg?seed=Milo",
        "https://api.dicebear.com/7.x/bottts/svg?seed=Zoe"
    )

    private lateinit var rootContainer: LinearLayout
    private lateinit var contentContainer: LinearLayout
    private lateinit var tabLibraryBtn: Button
    private lateinit var tabExploreBtn: Button
    private lateinit var tabRecsBtn: Button
    private lateinit var tabSettingsBtn: Button
    private lateinit var categoryBar: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            text = "• Modo Autónomo Local: Escribe 'local'\n• Servidor Remoto o Docker: http://TU_IP_LOCAL:8000/api/v1"
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

    // -------------------------------------------------------------
    // Autenticación
    // -------------------------------------------------------------
    private fun showAuthScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 80, 50, 40)
            setBackgroundColor(Color.parseColor("#0D0C11"))
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val serverBtn = Button(this).apply {
            text = "⚙ Servidor"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A5B2"))
            background = makeRoundedDrawable("#16151C", "#2D2A38", 16)
            setOnClickListener { showServerDialog() }
        }
        topBar.addView(serverBtn)
        root.addView(topBar)

        val title = TextView(this).apply {
            text = "Universal Media Tracker"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F2EB"))
            setPadding(0, 40, 0, 10)
        }
        val subtitle = TextView(this).apply {
            text = "Tu anime, películas, series, manga y libros en un solo lugar."
            textSize = 14f
            setTextColor(Color.parseColor("#A8A5B2"))
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
            addView(nameField, makeMarginParams(0, 10))
            addView(emailField, makeMarginParams(0, 10))
            addView(passField, makeMarginParams(0, 20))
            addView(actionBtn, makeMarginParams(0, 10))
            addView(toggleModeBtn, makeMarginParams(0, 10))
        }
        root.addView(formBox)

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
                    userName = if (name.isNotEmpty()) name else email.substringBefore("@")
                    userAvatar = "https://api.dicebear.com/7.x/bottts/svg?seed=$email"

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

    // -------------------------------------------------------------
    // Pantalla Principal (Tabs y Categorías Separadas)
    // -------------------------------------------------------------
    private fun showMainScreen() {
        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0D0C11"))
        }

        // Header Superior
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 36, 30, 16)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.parseColor("#131219"))
        }

        val appTitle = TextView(this).apply {
            text = "UM Tracker"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#F5F2EB"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val serverBtn = Button(this).apply {
            text = "⚙ Servidor"
            textSize = 11f
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#211F2A", "#333140", 12)
            setOnClickListener { showServerDialog() }
        }

        header.addView(appTitle)
        header.addView(serverBtn)
        rootContainer.addView(header)

        // Barra de 4 Vistas Principales (Biblioteca, Explorar, Recomendaciones, Ajustes)
        val navBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
            setBackgroundColor(Color.parseColor("#16151C"))
        }

        tabLibraryBtn = Button(this).apply {
            text = "Biblioteca"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("library") }
        }
        tabExploreBtn = Button(this).apply {
            text = "Explorar"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("explore") }
        }
        tabRecsBtn = Button(this).apply {
            text = "Para ti"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("recs") }
        }
        tabSettingsBtn = Button(this).apply {
            text = "Ajustes"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("settings") }
        }

        navBar.addView(tabLibraryBtn)
        navBar.addView(tabExploreBtn)
        navBar.addView(tabRecsBtn)
        navBar.addView(tabSettingsBtn)
        rootContainer.addView(navBar)

        // REQUERIMIENTO 4: Barra de Categorías / Tipos Separados
        val catScroll = HorizontalScrollView(this).apply {
            setPadding(16, 10, 16, 10)
            setBackgroundColor(Color.parseColor("#111016"))
        }
        categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val categories = listOf(
            "all" to "Todos",
            "anime" to "Anime",
            "manga" to "Manga",
            "movie" to "Películas",
            "series" to "Series",
            "book" to "Libros"
        )

        for ((key, label) in categories) {
            val btn = Button(this).apply {
                text = label
                textSize = 12f
                val isSelected = selectedCategory == key
                background = makeRoundedDrawable(
                    if (isSelected) "#76E6D5" else "#1A1824",
                    if (isSelected) "#76E6D5" else "#2B283A",
                    999
                )
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                setPadding(28, 12, 28, 12)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(8, 0, 8, 0)
                }
                setOnClickListener {
                    selectedCategory = key
                    refreshCategoryButtons()
                    renderCurrentTab()
                }
            }
            categoryBar.addView(btn)
        }
        catScroll.addView(categoryBar)
        rootContainer.addView(catScroll)

        // Contenedor con Scroll
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        contentContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 20, 24, 40)
        }
        scrollView.addView(contentContainer)
        rootContainer.addView(scrollView)

        setContentView(rootContainer)
        switchTab("library")
    }

    private fun refreshCategoryButtons() {
        val categories = listOf("all", "anime", "manga", "movie", "series", "book")
        for (i in 0 until categoryBar.childCount) {
            val btn = categoryBar.getChildAt(i) as? Button ?: continue
            val key = categories.getOrNull(i) ?: continue
            val isSelected = selectedCategory == key
            btn.background = makeRoundedDrawable(
                if (isSelected) "#76E6D5" else "#1A1824",
                if (isSelected) "#76E6D5" else "#2B283A",
                999
            )
            btn.setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
        }
    }

    private fun switchTab(tab: String) {
        activeTab = tab

        val activeBg = makeRoundedDrawable("#A782FF", "#B89AFF", 12)
        val inactiveBg = makeRoundedDrawable("#1C1A24", "#2A2735", 12)

        tabLibraryBtn.apply {
            background = if (tab == "library") activeBg else inactiveBg
            setTextColor(if (tab == "library") Color.BLACK else Color.parseColor("#A8A5B2"))
        }
        tabExploreBtn.apply {
            background = if (tab == "explore") activeBg else inactiveBg
            setTextColor(if (tab == "explore") Color.BLACK else Color.parseColor("#A8A5B2"))
        }
        tabRecsBtn.apply {
            background = if (tab == "recs") activeBg else inactiveBg
            setTextColor(if (tab == "recs") Color.BLACK else Color.parseColor("#A8A5B2"))
        }
        tabSettingsBtn.apply {
            background = if (tab == "settings") activeBg else inactiveBg
            setTextColor(if (tab == "settings") Color.BLACK else Color.parseColor("#A8A5B2"))
        }

        // REQUERIMIENTO 6: Ocultar barra de categorías en ajustes
        if (::categoryBar.isInitialized && categoryBar.parent != null) {
            val scroll = categoryBar.parent as? HorizontalScrollView
            scroll?.visibility = if (tab == "settings") View.GONE else View.VISIBLE
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

    // -------------------------------------------------------------
    // VISTA 1: Mi Biblioteca (con límite de caps y edición manual)
    // -------------------------------------------------------------
    private fun loadLibraryTab() {
        contentContainer.removeAllViews()

        val headerText = TextView(this).apply {
            text = if (selectedCategory == "all") "Mi Biblioteca Completa" else "Mi Biblioteca · ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        contentContainer.addView(headerText)

        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val filters = listOf("all" to "Todos", "in_progress" to "Viendo", "planned" to "Plan", "completed" to "Fin")
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

        // Botón para desplegar filtros de géneros (+/-)
        val genreFilterBtn = Button(this).apply {
            text = "⚙ Filtro de Géneros (+/-)"
            textSize = 12f
            setTextColor(Color.parseColor("#C7ADFF"))
            background = makeRoundedDrawable("#1E1B29", "#39334F", 10)
            setOnClickListener { showGenreFilterDialog() }
            layoutParams = makeMarginParams(0, 10)
        }
        contentContainer.addView(genreFilterBtn)

        val loadingLabel = TextView(this).apply {
            text = "Cargando biblioteca..."
            setTextColor(Color.parseColor("#A8A5B2"))
            textSize = 14f
            setPadding(10, 30, 10, 10)
        }
        contentContainer.addView(loadingLabel)

        thread {
            try {
                var urlPath = "/library"
                val params = mutableListOf<String>()
                if (currentFilter != "all") params.add("status=$currentFilter")
                if (selectedCategory != "all") params.add("media_type=$selectedCategory")
                includeGenres.forEach { params.add("include_genres=${URLEncoder.encode(it, "UTF-8")}") }
                excludeGenres.forEach { params.add("exclude_genres=${URLEncoder.encode(it, "UTF-8")}") }

                if (params.isNotEmpty()) {
                    urlPath += "?" + params.joinToString("&")
                }

                val (code, resp) = request("GET", urlPath, null)
                if (code in 200..299) {
                    val array = JSONArray(resp)
                    runOnUiThread {
                        contentContainer.removeView(loadingLabel)
                        if (array.length() == 0) {
                            val empty = TextView(this).apply {
                                text = "No hay medios registrados en esta categoría.\nVe a 'Explorar' para añadir contenido."
                                textSize = 14f
                                setTextColor(Color.parseColor("#A8A5B2"))
                                gravity = Gravity.CENTER
                                setPadding(20, 50, 20, 50)
                            }
                            contentContainer.addView(empty)
                        } else {
                            for (i in 0 until array.length()) {
                                val entry = array.getJSONObject(i)
                                contentContainer.addView(createLibraryEntryCard(entry))
                            }
                        }
                    }
                } else {
                    runOnUiThread { loadingLabel.text = "Error al cargar ($code)" }
                }
            } catch (e: Exception) {
                runOnUiThread { loadingLabel.text = "Error: ${e.localizedMessage}" }
            }
        }
    }

    private fun createLibraryEntryCard(entry: JSONObject): View {
        val media = entry.getJSONObject("media")
        val mediaId = entry.getString("media_id")
        val title = media.optString("title", "Sin título")
        val mediaType = media.optString("media_type", "media")
        val imageUrl = media.optString("image_url", "")
        val status = entry.optString("status", "planned")
        var progress = entry.optDouble("progress", 0.0).toInt()
        
        val total = if (!entry.isNull("total")) entry.optDouble("total").toInt() 
                    else if (media.has("total_units")) media.optInt("total_units")
                    else null
        val seasons = media.optInt("seasons", 1)

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16)
            setPadding(12, 12, 12, 12)
            elevation = 4f
            layoutParams = makeMarginParams(0, 10)
        }

        val imageSize = (85 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply {
                setMargins(0, 0, 14, 0)
            }
            background = makeRoundedDrawable("#111016", "#1F1D29", 10)
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

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val typeBadge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#1E2D2A", "#2D4541", 6)
            setPadding(10, 2, 10, 2)
        }

        val statusBadge = TextView(this).apply {
            val (stText, stColor) = when (status) {
                "in_progress" -> "En progreso" to "#76E6D5"
                "completed" -> "Completado" to "#98C379"
                "on_hold" -> "En pausa" to "#61AFEF"
                "dropped" -> "Abandonado" to "#E06C75"
                else -> "Planificado" to "#E5C07B"
            }
            text = stText
            textSize = 10f
            setTextColor(Color.parseColor(stColor))
            setPadding(12, 0, 0, 0)
        }

        topRow.addView(typeBadge)
        topRow.addView(statusBadge)
        infoContent.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 4, 0, 2)
        }
        infoContent.addView(titleView)

        if (seasons > 1 || mediaType == "series" || mediaType == "anime") {
            val seasonView = TextView(this).apply {
                text = if (mediaType == "manga" || mediaType == "book") "$seasons Volúmenes/Tomos" else "$seasons Temporadas"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, 4)
            }
            infoContent.addView(seasonView)
        }

        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 8)
        }

        val progressText = TextView(this).apply {
            val totalStr = if (total != null && total > 0) "/$total" else ""
            text = "Progreso: $progress$totalStr"
            textSize = 13f
            setTextColor(Color.parseColor("#A8A5B2"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minusBtn = Button(this).apply {
            text = "−"
            textSize = 14f
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#262332", "#38344A", 8)
            layoutParams = LinearLayout.LayoutParams((36 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt()).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                if (progress > 0) {
                    progress--
                    updateMediaProgress(mediaId, progress, progressText, total)
                }
            }
        }

        val plusBtn = Button(this).apply {
            text = "+1"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 8)
            layoutParams = LinearLayout.LayoutParams((44 * resources.displayMetrics.density).toInt(), (32 * resources.displayMetrics.density).toInt()).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                if (total != null && total > 0 && progress >= total) {
                    toast("¡Ya lo terminaste!")
                    return@setOnClickListener
                }
                progress++
                updateMediaProgress(mediaId, progress, progressText, total)
            }
        }

        progressRow.addView(progressText)
        progressRow.addView(minusBtn)
        progressRow.addView(plusBtn)
        infoContent.addView(progressRow)

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val editBtn = TextView(this).apply {
            text = "⚙️ Editar"
            textSize = 11f
            setTextColor(Color.parseColor("#A782FF"))
            setPadding(0, 8, 20, 8)
            setOnClickListener { showManualEditDialog(entry, mediaId, title, progress, total) }
        }

        val finishBtn = Button(this).apply {
            text = "✓ Terminar"
            textSize = 10f
            setTextColor(Color.parseColor("#98C379"))
            background = makeRoundedDrawable("#19241B", "#27422C", 8)
            setPadding(12, 0, 12, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (28 * resources.displayMetrics.density).toInt()).apply {
                setMargins(6, 0, 0, 0)
            }
            setOnClickListener {
                val targetTotal = total ?: progress
                updateLibraryEntryStatus(mediaId, "completed", targetTotal, total)
            }
        }

        val deleteBtn = TextView(this).apply {
            text = "🗑️ Quitar"
            textSize = 11f
            setTextColor(Color.parseColor("#FF6B6B"))
            setPadding(20, 8, 20, 8)
            setOnClickListener { confirmDeleteFromLibrary(mediaId, title) }
        }

        actionRow.addView(editBtn)
        actionRow.addView(finishBtn)
        actionRow.addView(deleteBtn)
        infoContent.addView(actionRow)

        card.addView(infoContent)
        return card
    }

    private fun showManualEditDialog(entry: JSONObject, mediaId: String, title: String, currentProg: Int, currentTotal: Int?) {
        val b = AlertDialog.Builder(this)
        b.setTitle("Editar progreso: $title")

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 30, 50, 20)
        }

        val pLabel = TextView(this).apply { text = "Progreso actual:"; setTextColor(Color.GRAY) }
        val pInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("$currentProg")
        }

        val tLabel = TextView(this).apply { text = "Total de capítulos/tomos:"; setTextColor(Color.GRAY) }
        val tInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(if (currentTotal != null) "$currentTotal" else "")
        }

        val sLabel = TextView(this).apply { text = "Estado:"; setTextColor(Color.GRAY); setPadding(0, 10, 0, 0) }
        val statusList = listOf("planned" to "Plan", "in_progress" to "Viendo", "completed" to "Fin")
        val statusAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, statusList.map { it.second })
        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        val sSpinner = Spinner(this).apply {
            adapter = statusAdapter
            val currentStatus = entry.optString("status", "planned")
            val index = statusList.indexOfFirst { it.first == currentStatus }
            if (index >= 0) setSelection(index)
        }

        box.addView(pLabel)
        box.addView(pInput)
        box.addView(tLabel)
        box.addView(tInput)
        box.addView(sLabel)
        box.addView(sSpinner)
        b.setView(box)

        b.setPositiveButton("Guardar") { _, _ ->
            val pVal = pInput.text.toString().toDoubleOrNull() ?: 0.0
            val tVal = tInput.text.toString().toDoubleOrNull()
            val sVal = statusList[sSpinner.selectedItemPosition].first

            if (tVal != null && tVal > 0 && pVal > tVal) {
                toast("El progreso no puede exceder el total ($tVal)")
                return@setPositiveButton
            }

            thread {
                try {
                    val body = JSONObject().apply {
                        put("status", sVal)
                        put("progress", pVal)
                        if (tVal != null) put("total", tVal)
                    }
                    val (code, _) = request("PUT", "/library/$mediaId", body.toString())
                    if (code in 200..299) {
                        runOnUiThread {
                            toast("Progreso guardado")
                            loadLibraryTab()
                        }
                    }
                } catch (e: Exception) {
                    runOnUiThread { toast("Error: ${e.localizedMessage}") }
                }
            }
        }
        b.setNegativeButton("Cancelar", null)
        b.show()
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
                        label.text = "Progreso: $newValue" + (if (total != null) " / $total" else " / ?")
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

    // -------------------------------------------------------------
    // REQUERIMIENTO 6: Diálogo de Inclusión y Exclusión de Géneros
    // -------------------------------------------------------------
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
                    if (isInc) "#76E6D5" else if (isExc) "#FF8C94" else "#2F2B40",
                    8
                )
                setTextColor(if (isInc) Color.parseColor("#76E6D5") else if (isExc) Color.parseColor("#FF8C94") else Color.WHITE)
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
                        if (nowInc) "#76E6D5" else if (nowExc) "#FF8C94" else "#2F2B40",
                        8
                    )
                    setTextColor(if (nowInc) Color.parseColor("#76E6D5") else if (nowExc) Color.parseColor("#FF8C94") else Color.WHITE)
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

    // -------------------------------------------------------------
    // VISTA 2: Explorar y Buscar (Separado por tipos)
    // -------------------------------------------------------------
    private fun renderExploreTab() {
        contentContainer.removeAllViews()

        val title = TextView(this).apply {
            text = "Explorar ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        contentContainer.addView(title)

        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 10, 0, 20)
        }

        val searchInput = EditText(this).apply {
            hint = "Buscar en $selectedCategory..."
            setHintTextColor(Color.parseColor("#6C6977"))
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#16151C", "#2D2A38", 12)
            setPadding(24, 20, 24, 20)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val searchBtn = Button(this).apply {
            text = "Buscar"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 12)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(10, 0, 0, 0)
            }
        }
        
        val resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val advFilterBtn = TextView(this).apply {
            text = "⌛ Filtros Avanzados"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A782FF"))
            setPadding(10, 10, 10, 10)
            setOnClickListener {
                showAdvancedSearchFilters { filters ->
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

        searchBtn.setOnClickListener {
            val q = searchInput.text.toString().trim()
            performSearch(q, resultsBox, null)
        }
    }

    private fun showAdvancedSearchFilters(onApply: (JSONObject) -> Unit) {
        val b = AlertDialog.Builder(this)
        b.setTitle("Filtros de Búsqueda")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 40, 50, 40)
        }

        // Año
        val yearLabel = TextView(this).apply { text = "Año de lanzamiento:"; setTextColor(Color.GRAY) }
        val yearInput = EditText(this).apply { hint = "Ej. 2024"; inputType = android.text.InputType.TYPE_CLASS_NUMBER }
        
        // Estado
        val statusLabel = TextView(this).apply { text = "\nEstado de la obra:"; setTextColor(Color.GRAY) }
        val statuses = listOf("Cualquiera", "En emisión", "Finalizado", "En pausa", "Cancelado")
        val statusSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, statuses)
        }

        // Clasificación
        val ageLabel = TextView(this).apply { text = "\nClasificación de edad:"; setTextColor(Color.GRAY) }
        val ages = listOf("Cualquiera", "Todo público", "10+", "14+", "16+", "18+ (R)", "18+ (H)")
        val ageSpinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, ages)
        }

        box.addView(yearLabel); box.addView(yearInput)
        box.addView(statusLabel); box.addView(statusSpinner)
        box.addView(ageLabel); box.addView(ageSpinner)

        b.setView(box)
        b.setPositiveButton("Aplicar") { _, _ ->
            val filters = JSONObject().apply {
                val y = yearInput.text.toString()
                if (y.isNotEmpty()) put("year", y.toInt())
                if (statusSpinner.selectedItemPosition > 0) put("status", statuses[statusSpinner.selectedItemPosition])
                if (ageSpinner.selectedItemPosition > 0) put("age_rating", ages[ageSpinner.selectedItemPosition])
            }
            onApply(filters)
        }
        b.setNegativeButton("Cerrar", null)
        b.show()
    }

    private fun performSearch(q: String, resultsBox: LinearLayout, filters: JSONObject?) {
        if (q.isEmpty() && (filters == null || filters.length() == 0)) {
            toast("Ingresa un término o usa filtros")
            return
        }

        resultsBox.removeAllViews()
        val searchingLabel = TextView(this).apply {
            text = "Buscando..."
            setTextColor(Color.parseColor("#A8A5B2"))
            textSize = 14f
            setPadding(0, 20, 0, 20)
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

                val (code, resp) = request("GET", url, null)
                if (code in 200..299) {
                    val array = JSONArray(resp)
                    runOnUiThread {
                        resultsBox.removeAllViews()
                        if (array.length() == 0) {
                            resultsBox.addView(TextView(this).apply { 
                                text = "Sin resultados"; setTextColor(Color.GRAY); setPadding(0, 40, 0, 0); gravity = Gravity.CENTER 
                            })
                        } else {
                            for (i in 0 until array.length()) {
                                resultsBox.addView(createSearchResultCard(array.getJSONObject(i)))
                            }
                        }
                    }
                } else {
                    runOnUiThread { searchingLabel.text = "Error ($code)" }
                }
            } catch (e: Exception) {
                runOnUiThread { searchingLabel.text = "Error: ${e.localizedMessage}" }
            }
        }
    }

    private fun createSearchResultCard(item: JSONObject): View {
        val title = item.optString("title", "Sin título")
        val mediaType = item.optString("media_type", item.optString("category", "medio"))
        val source = item.optString("source", "web")
        val year = item.optInt("release_year", 0)
        val units = if (item.isNull("total_units")) null else item.optInt("total_units")
        val seasons = item.optInt("seasons", 1)
        val imageUrl = item.optString("image_url", "")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16)
            setPadding(12, 12, 12, 12)
            elevation = 2f
            layoutParams = makeMarginParams(0, 10)
        }

        val imageSize = (85 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply {
                setMargins(0, 0, 16, 0)
            }
            background = makeRoundedDrawable("#111016", "#1F1D29", 10)
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

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#A782FF"))
            background = makeRoundedDrawable("#211D36", "#3A335E", 6)
            setPadding(10, 2, 10, 2)
        }

        val sourceLabel = TextView(this).apply {
            text = (if (year > 0) "$year • " else "") + source.uppercase()
            textSize = 10f
            setTextColor(Color.parseColor("#A8A5B2"))
            setPadding(12, 0, 0, 0)
        }

        topRow.addView(badge)
        topRow.addView(sourceLabel)
        infoContent.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 6, 0, 4)
        }
        infoContent.addView(titleView)

        // Mostrar episodios y temporadas
        val detailText = mutableListOf<String>()
        if (units != null && units > 0) detailText.add("$units ${if (mediaType=="manga" || mediaType=="book") "caps/tomos" else "episodios"}")
        if (seasons > 1) detailText.add("$seasons temp")
        
        if (detailText.isNotEmpty()) {
            val detailView = TextView(this).apply {
                text = detailText.joinToString(" • ")
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, 8)
            }
            infoContent.addView(detailView)
        }

        val addBtn = Button(this).apply {
            text = "＋ Añadir a lista"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#1F2D33", "#28424B", 8)
            setPadding(20, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (34 * resources.displayMetrics.density).toInt())
            setOnClickListener {
                importAndAddMedia(item, this)
            }
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

                    val upsertBody = JSONObject().apply {
                        put("status", "planned")
                        put("progress", 0)
                        if (totalUnits != null) put("total", totalUnits)
                    }
                    val (trackCode, _) = request("PUT", "/library/$mediaId", upsertBody.toString())
                    if (trackCode in 200..299) {
                        runOnUiThread {
                            btn.text = "✓ En biblioteca"
                            toast("Añadido con éxito")
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    btn.isEnabled = true
                    btn.text = "＋ Reintentar"
                    toast("Error: ${e.localizedMessage}")
                }
            }
        }
    }

    // -------------------------------------------------------------
    // VISTA 3: Recomendaciones Personalizadas (REQUERIMIENTO 2)
    // -------------------------------------------------------------
    private fun loadRecommendationsTab() {
        contentContainer.removeAllViews()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 16)
        }

        val recsTitle = TextView(this).apply {
            text = if (selectedCategory == "all") "✨ Para ti" else "✨ Recomendado: ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val reloadBtn = Button(this).apply {
            text = "🔄 Recargar"
            textSize = 10f
            setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#16151C", "#2D2A38", 10)
            setPadding(20, 0, 20, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * resources.displayMetrics.density).toInt())
            setOnClickListener { loadRecommendationsTab() }
        }

        header.addView(recsTitle)
        header.addView(reloadBtn)
        contentContainer.addView(header)

        val desc = TextView(this).apply {
            text = "Basado en tu biblioteca y preferencias actuales."
            textSize = 13f
            setTextColor(Color.parseColor("#A8A5B2"))
            setPadding(0, 0, 0, 20)
        }
        contentContainer.addView(desc)

        val loading = TextView(this).apply {
            text = "Calculando afinidades de contenido..."
            setTextColor(Color.parseColor("#A8A5B2"))
        }
        contentContainer.addView(loading)

        thread {
            try {
                val catParam = if (selectedCategory != "all") "?media_type=$selectedCategory" else ""
                val (code, resp) = request("GET", "/recommendations$catParam", null)
                if (code in 200..299) {
                    val recs = JSONArray(resp)
                    runOnUiThread {
                        contentContainer.removeView(loading)
                        if (recs.length() == 0) {
                            val empty = TextView(this).apply {
                                text = "Aún no hay recomendaciones disponibles.\nAgrega obras a tu biblioteca y califícalas para entrenar al recomendador."
                                setTextColor(Color.parseColor("#A8A5B2"))
                                setPadding(0, 40, 0, 0)
                            }
                            contentContainer.addView(empty)
                        } else {
                            for (i in 0 until recs.length()) {
                                val item = recs.getJSONObject(i)
                                contentContainer.addView(createRecommendationCard(item))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { loading.text = "Error: ${e.localizedMessage}" }
            }
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

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = makeRoundedDrawable("#1A1826", "#2D2A3D", 16)
            setPadding(12, 12, 12, 12)
            elevation = 2f
            layoutParams = makeMarginParams(0, 12)
        }

        val imageSize = (80 * resources.displayMetrics.density).toInt()
        val coverWrapper = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(imageSize, (imageSize * 1.4).toInt()).apply {
                setMargins(0, 0, 14, 0)
            }
            background = makeRoundedDrawable("#111016", "#1F1D29", 10)
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

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val typeBadge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C7ADFF"))
            background = makeRoundedDrawable("#261F36", "#4A3A69", 6)
            setPadding(10, 2, 10, 2)
        }

        val scoreBadge = TextView(this).apply {
            text = "Afinidad: $score"
            textSize = 10f
            setTextColor(Color.parseColor("#76E6D5"))
            setPadding(12, 0, 0, 0)
        }

        topRow.addView(typeBadge)
        topRow.addView(scoreBadge)
        infoContent.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 4, 0, 4)
        }
        infoContent.addView(titleView)

        if (units != null && units > 0) {
            val unitsView = TextView(this).apply {
                text = "$units ${if (mediaType=="manga" || mediaType=="book") "caps/tomos" else "episodios"}"
                textSize = 11f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, 4)
            }
            infoContent.addView(unitsView)
        }

        val reasonView = TextView(this).apply {
            text = "💡 $reason"
            textSize = 11f
            setTextColor(Color.parseColor("#BCAADB"))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, 0, 0, 8)
        }
        infoContent.addView(reasonView)

        val addBtn = Button(this).apply {
            text = "＋ Añadir"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#342E4A", "#4E466D", 8)
            setPadding(16, 0, 16, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (32 * resources.displayMetrics.density).toInt())
            setOnClickListener {
                importAndAddMedia(media, this)
            }
        }
        infoContent.addView(addBtn)

        card.addView(infoContent)
        return card
    }

    // -------------------------------------------------------------
    // VISTA 4: Settings / Ajustes (REQUERIMIENTO 3)
    // -------------------------------------------------------------
    private fun renderSettingsTab() {
        contentContainer.removeAllViews()

        val title = TextView(this).apply {
            text = "⚙️ Ajustes de Cuenta y Notificaciones"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16)
        }
        contentContainer.addView(title)

        // Tarjeta de Perfil & Avatar
        val profileCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable("#181722", "#2B283A", 18)
            setPadding(30, 26, 30, 26)
            layoutParams = makeMarginParams(0, 16)
        }

        val pTitle = TextView(this).apply {
            text = "Foto de Perfil y Nombre"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        profileCard.addView(pTitle)

        // Previsualización de Avatar
        val avatarPreview = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams((80 * resources.displayMetrics.density).toInt(), (80 * resources.displayMetrics.density).toInt()).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, 20)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Borde redondeado para la imagen de perfil
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 40 * resources.displayMetrics.density
                setColor(Color.parseColor("#2D2A38"))
            }
            clipToOutline = true
            if (!userAvatar.isNullOrBlank()) {
                loadImage(userAvatar!!, this)
            }
        }
        profileCard.addView(avatarPreview)

        val nameLabel = TextView(this).apply { text = "Nombre para mostrar:"; setTextColor(Color.GRAY) }
        val nameInput = EditText(this).apply {
            setText(userName ?: "")
            setTextColor(Color.WHITE)
        }
        profileCard.addView(nameLabel)
        profileCard.addView(nameInput)

        val avLabel = TextView(this).apply {
            text = "URL de tu Avatar / Foto:"
            setTextColor(Color.GRAY)
            setPadding(0, 10, 0, 0)
        }
        val avInput = EditText(this).apply {
            setText(userAvatar ?: "")
            setTextColor(Color.WHITE)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val url = s?.toString()?.trim() ?: ""
                    if (url.isNotEmpty()) {
                        loadImage(url, avatarPreview)
                    }
                }
            })
        }
        profileCard.addView(avLabel)
        profileCard.addView(avInput)

        // REQUERIMIENTO 3: Switch de notificaciones
        val notifyBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 16, 0, 16)
        }
        val notifyLabel = TextView(this).apply {
            text = "🔔 Notificaciones de nuevos capítulos"
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val notifySwitch = CheckBox(this).apply {
            isChecked = notifyNewReleases
        }
        notifyBox.addView(notifyLabel)
        notifyBox.addView(notifySwitch)
        profileCard.addView(notifyBox)

        val saveProfileBtn = Button(this).apply {
            text = "Guardar Cambios de Perfil"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 10)
            setOnClickListener {
                val newName = nameInput.text.toString().trim()
                val newAv = avInput.text.toString().trim()
                val newNotif = notifySwitch.isChecked

                thread {
                    try {
                        val body = JSONObject().apply {
                            put("display_name", newName)
                            put("avatar_url", newAv)
                            put("notify_new_releases", newNotif)
                        }
                        val (code, _) = request("PUT", "/auth/profile", body.toString())
                        if (code in 200..299) {
                            userName = newName
                            userAvatar = newAv
                            notifyNewReleases = newNotif
                            prefs.edit()
                                .putString("user_name", userName)
                                .putString("user_avatar", userAvatar)
                                .putBoolean("notify_releases", notifyNewReleases)
                                .apply()
                            runOnUiThread { toast("Perfil actualizado con éxito") }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Error: ${e.localizedMessage}") }
                    }
                }
            }
        }
        profileCard.addView(saveProfileBtn)
        contentContainer.addView(profileCard)

        // Tarjeta de Seguridad (Cambio de Contraseña)
        val securityCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable("#181722", "#2B283A", 18)
            setPadding(30, 26, 30, 26)
            layoutParams = makeMarginParams(0, 16)
        }

        val sTitle = TextView(this).apply {
            text = "Cambiar Contraseña"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12)
        }
        val currPassInput = EditText(this).apply {
            hint = "Contraseña actual"
            inputType = 129
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }
        val newPassInput = EditText(this).apply {
            hint = "Nueva contraseña (mínimo 8 caracteres)"
            inputType = 129
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
        }

        val changePassBtn = Button(this).apply {
            text = "Actualizar Contraseña"
            textSize = 13f
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#2B283A", "#423E56", 10)
            setOnClickListener {
                val cP = currPassInput.text.toString()
                val nP = newPassInput.text.toString()
                if (nP.length < 8) {
                    toast("La nueva contraseña debe tener al menos 8 caracteres")
                    return@setOnClickListener
                }
                thread {
                    try {
                        val body = JSONObject().apply {
                            put("current_password", cP)
                            put("new_password", nP)
                        }
                        val (code, resp) = request("POST", "/auth/change-password", body.toString())
                        if (code in 200..299) {
                            runOnUiThread {
                                toast("Contraseña actualizada con éxito")
                                currPassInput.setText("")
                                newPassInput.setText("")
                            }
                        } else {
                            val err = try { JSONObject(resp).optString("detail", resp) } catch (_: Exception) { resp }
                            runOnUiThread { toast("Error: $err") }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Error: ${e.localizedMessage}") }
                    }
                }
            }
        }

        securityCard.addView(sTitle)
        securityCard.addView(currPassInput)
        securityCard.addView(newPassInput)
        securityCard.addView(changePassBtn)
        contentContainer.addView(securityCard)

        // Botón Cerrar Sesión
        val logoutBtn = Button(this).apply {
            text = "Cerrar sesión de UMT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#FF8C94"))
            background = makeRoundedDrawable("#2B1E22", "#4D2C34", 12)
            setOnClickListener { logout() }
            layoutParams = makeMarginParams(0, 20)
        }
        contentContainer.addView(logoutBtn)
    }

    // -------------------------------------------------------------
    // Peticiones de Red HTTP (HttpURLConnection) o Modo Local Autónomo
    // -------------------------------------------------------------
    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
        if (getBaseUrl() == "local") {
            return handleLocalRequest(method, path, body)
        }

        val fullUrl = getBaseUrl() + path
        val conn = URL(fullUrl).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 12000
        conn.setRequestProperty("Accept", "application/json")

        token?.let {
            conn.setRequestProperty("Authorization", "Bearer $it")
        }

        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }
        }

        val code = conn.responseCode
        val responseText = (if (code >= 400) conn.errorStream else conn.inputStream)
            ?.bufferedReader()?.readText() ?: ""
        conn.disconnect()
        return code to responseText
    }

    // -------------------------------------------------------------
    // Motor Local Autónomo (Simulación Completa de la API FastAPI)
    // -------------------------------------------------------------
    private fun handleLocalRequest(method: String, path: String, body: String?): Pair<Int, String> {
        val dbHelper = LocalDatabaseHelper(this)
        val db = dbHelper.writableDatabase

        try {
            // 1. Auth: Registro
            if (path == "/auth/register" && method == "POST") {
                val json = JSONObject(body ?: "{}")
                val email = json.optString("email", "").lowercase().trim()
                val displayName = json.optString("display_name", "").trim()
                
                if (email.isEmpty()) return 422 to "{\"detail\":\"Email requerido\"}"
                
                val cursor = db.rawQuery("SELECT id FROM users WHERE email = ?", arrayOf(email))
                if (cursor.moveToFirst()) {
                    cursor.close()
                    return 409 to "{\"detail\":\"El correo electrónico ya está registrado\"}"
                }
                cursor.close()

                val values = android.content.ContentValues().apply {
                    put("email", email)
                    put("display_name", displayName)
                    put("avatar_url", "https://api.dicebear.com/7.x/bottts/svg?seed=$email")
                }
                val id = db.insert("users", null, values)
                return 200 to "{\"access_token\":\"LOCAL_TOKEN_$id\"}"
            }

            // 2. Auth: Login
            if (path == "/auth/login" && method == "POST") {
                val json = JSONObject(body ?: "{}")
                val email = json.optString("email", "").lowercase().trim()
                
                val cursor = db.rawQuery("SELECT id, display_name, avatar_url FROM users WHERE email = ?", arrayOf(email))
                if (cursor.moveToFirst()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    val av = cursor.getString(2)
                    cursor.close()
                    
                    // Guardar info local de sesión inmediatamente
                    prefs.edit().apply {
                        putString("user_name", name)
                        putString("user_email", email)
                        putString("user_avatar", av)
                    }.apply()

                    return 200 to "{\"access_token\":\"LOCAL_TOKEN_$id\"}"
                }
                cursor.close()
                return 401 to "{\"detail\":\"Credenciales incorrectas o correo no registrado localmente\"}"
            }

            // 3. Obtener Perfil Actual
            if (path == "/auth/me" && method == "GET") {
                return 200 to JSONObject().apply {
                    put("email", prefs.getString("user_email", "local@umt.com"))
                    put("display_name", prefs.getString("user_name", "Usuario Local"))
                    put("avatar_url", prefs.getString("user_avatar", ""))
                    put("notify_new_releases", prefs.getBoolean("notify_releases", true))
                }.toString()
            }

            // 4. Actualizar Perfil
            if (path == "/auth/profile" && method == "PUT") {
                val json = JSONObject(body ?: "{}")
                val name = json.optString("display_name", "").trim()
                val avatar = json.optString("avatar_url", "").trim()
                val notify = json.optBoolean("notify_new_releases", true)
                
                prefs.edit().apply {
                    if (name.isNotEmpty()) putString("user_name", name)
                    if (avatar.isNotEmpty()) putString("user_avatar", avatar)
                    putBoolean("notify_releases", notify)
                }.apply()

                db.execSQL("UPDATE users SET display_name = ?, avatar_url = ? WHERE email = ?", 
                    arrayOf(name, avatar, prefs.getString("user_email", "")))

                return 200 to JSONObject().apply {
                    put("email", prefs.getString("user_email", ""))
                    put("display_name", name)
                    put("avatar_url", avatar)
                    put("notify_new_releases", notify)
                }.toString()
            }

            // 5. Cambio de Contraseña (Simulado)
            if (path == "/auth/change-password" && method == "POST") {
                return 200 to "{\"status\":\"ok\"}"
            }

            // 6. Obtener biblioteca completa (Library)
            if (path.startsWith("/library") && method == "GET") {
                val arr = JSONArray()
                val filterStatus = if (path.contains("status=")) path.substringAfter("status=").substringBefore("&") else null
                val filterCat = if (path.contains("media_type=")) path.substringAfter("media_type=").substringBefore("&") else null
                
                var sql = "SELECT m.id, m.title, m.category, m.synopsis, m.image_url, m.genres, " +
                        "l.status, l.progress, l.rating, l.notes, l.total_units, " +
                        "m.total_units, m.seasons, m.release_year, m.airing_status, m.age_rating " +
                        "FROM library l JOIN media m ON l.media_id = m.id WHERE 1=1"
                val paramsList = mutableListOf<String>()
                if (filterStatus != null) {
                    sql += " AND l.status = ?"
                    paramsList.add(filterStatus)
                }
                if (filterCat != null) {
                    sql += " AND m.category = ?"
                    paramsList.add(filterCat)
                }
                
                val cursor = db.rawQuery(sql, if (paramsList.isEmpty()) null else paramsList.toTypedArray())
                
                while (cursor.moveToNext()) {
                    val mObj = JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("title", cursor.getString(1))
                        put("category", cursor.getString(2))
                        put("media_type", cursor.getString(2))
                        put("synopsis", cursor.getString(3))
                        put("image_url", cursor.getString(4))
                        put("genres", JSONArray(cursor.getString(5).split(",")))
                        put("total_units", cursor.getInt(11))
                        put("seasons", cursor.getInt(12))
                        put("release_year", cursor.getInt(13))
                        put("airing_status", cursor.getString(14))
                        put("age_rating", cursor.getString(15))
                    }
                    val item = JSONObject().apply {
                        put("media_id", cursor.getString(0))
                        put("status", cursor.getString(6))
                        put("progress", cursor.getInt(7))
                        put("rating", if (cursor.isNull(8)) JSONObject.NULL else cursor.getInt(8))
                        put("notes", cursor.getString(9))
                        put("total", if (cursor.isNull(10)) JSONObject.NULL else cursor.getInt(10))
                        put("media", mObj)
                    }
                    arr.put(item)
                }
                cursor.close()
                return 200 to arr.toString()
            }

            // 7. Upsert / Agregar o Modificar elemento de la Biblioteca
            if (path.startsWith("/library") && (method == "POST" || method == "PUT")) {
                val parts = path.split("/")
                val mId = if (parts.size > 2) parts[2] else JSONObject(body ?: "{}").getString("media_id")
                val json = JSONObject(body ?: "{}")
                val status = json.optString("status", "planned")
                val progress = json.optInt("progress", 0)
                val rating = if (json.has("rating") && !json.isNull("rating")) json.getInt("rating") else null
                val notes = json.optString("notes", "")
                val total = if (json.has("total") && !json.isNull("total")) json.getInt("total") else null

                // Asegurar que exista el registro en la tabla de relaciones de la biblioteca
                db.execSQL("INSERT OR IGNORE INTO library (media_id, status, progress, rating, notes, total_units) VALUES (?, ?, ?, ?, ?, ?)",
                    arrayOf(mId, status, progress, rating, notes, total))
                
                db.execSQL("UPDATE library SET status = ?, progress = ?, rating = ?, notes = ?, total_units = COALESCE(?, total_units) WHERE media_id = ?",
                    arrayOf(status, progress, rating, notes, total, mId))

                return 200 to "{\"status\":\"updated_locally\"}"
            }

            // 8. Actualizar Progreso incremental
            if (path.startsWith("/library/") && path.endsWith("/progress") && method == "PUT") {
                val parts = path.split("/")
                val mId = parts[2]
                val json = JSONObject(body ?: "{}")
                val prog = json.getInt("progress")

                db.execSQL("UPDATE library SET progress = ? WHERE media_id = ?", arrayOf(prog, mId))
                return 200 to "{\"status\":\"progress_updated_locally\"}"
            }

            // 9. Eliminar de la Biblioteca
            if (path.startsWith("/library/") && method == "DELETE") {
                val mId = path.substringAfter("/library/")
                db.execSQL("DELETE FROM library WHERE media_id = ?", arrayOf(mId))
                return 200 to "{\"status\":\"deleted_locally\"}"
            }

            // 10. Explorar / Buscar Catálogo Local y APIs Externas
            if (path.startsWith("/search") && method == "GET") {
                val q = if (path.contains("query=")) path.substringAfter("query=").substringBefore("&").trim() else ""
                val category = if (path.contains("media_type=")) path.substringAfter("media_type=").substringBefore("&") else "all"
                val decodedQ = URLDecoder.decode(q, "UTF-8")
                
                val arr = JSONArray()
                
                // 10.1. Resultados Locales (Ya importados)
                var sql = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg FROM media WHERE LOWER(title) LIKE ?"
                val params = mutableListOf<String>("%${decodedQ.lowercase()}%")
                
                // Aplicar filtros adicionales si vienen en el path
                if (category != "all") {
                    sql += " AND category = ?"
                    params.add(category)
                }
                
                val cursor = db.rawQuery(sql, params.toTypedArray())
                while (cursor.moveToNext()) {
                    val cat = cursor.getString(2)
                    arr.put(JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("title", cursor.getString(1))
                        put("category", cat)
                        put("media_type", cat)
                        put("synopsis", cursor.getString(3))
                        put("image_url", cursor.getString(4))
                        put("genres", JSONArray(cursor.getString(5).split(",")))
                        put("total_units", cursor.getInt(6))
                        put("seasons", cursor.getInt(7))
                        put("release_year", cursor.getInt(8))
                        put("airing_status", cursor.getString(9))
                        put("age_rating", cursor.getString(10))
                        put("rating_avg", cursor.getDouble(11))
                        put("is_local", true)
                        put("source", "local")
                    })
                }
                cursor.close()

                // 10.2. Resultados de APIs Externas
                if (arr.length() < 15 && decodedQ.length > 2) {
                    try {
                        val external = performExternalSearch(decodedQ, category)
                        for (i in 0 until external.length()) {
                            val extItem = external.getJSONObject(i)
                            // API espera media_type en el card
                            if (!extItem.has("media_type")) extItem.put("media_type", extItem.optString("category"))
                            
                            var duplicate = false
                            for (j in 0 until arr.length()) {
                                if (arr.getJSONObject(j).optString("title").lowercase() == extItem.optString("title").lowercase()) {
                                    duplicate = true; break
                                }
                            }
                            if (!duplicate) arr.put(extItem)
                        }
                    } catch (e: Exception) { }
                }

                // 10.3. Si todo falla, insertar predeterminados si es la primera vez
                if (arr.length() == 0 && decodedQ.isEmpty()) {
                    insertDefaultMedia(db)
                }

                return 200 to arr.toString()
            }

            // 11. Recomendaciones & Estadísticas Inteligentes (Dinámicas)
            if (path.startsWith("/recommendations") && method == "GET") {
                val category = if (path.contains("media_type=")) path.substringAfter("media_type=").substringBefore("&") else "all"
                val arr = JSONArray()
                
                // 11.1. Lógica avanzada: Buscar géneros favoritos del usuario para recomendar similares
                val favGenres = mutableMapOf<String, Int>()
                val libraryCursor = db.rawQuery("SELECT genres FROM media WHERE id IN (SELECT media_id FROM library)", null)
                while (libraryCursor.moveToNext()) {
                    libraryCursor.getString(0).split(",").forEach { g ->
                        favGenres[g] = (favGenres[g] ?: 0) + 1
                    }
                }
                libraryCursor.close()
                val topGenre = favGenres.entries.maxByOrNull { it.value }?.key

                // Consulta base: Excluir los que ya están en la biblioteca
                var sql = "SELECT id, title, category, synopsis, image_url, genres, total_units, seasons, release_year, airing_status, age_rating, rating_avg FROM media " +
                         "WHERE id NOT IN (SELECT media_id FROM library)"
                val params = mutableListOf<String>()

                if (category != "all") {
                    sql += " AND category = ?"
                    params.add(category)
                }

                // Si hay un género favorito, priorizarlo un poco en el azar (simulado con ORDER BY CASE)
                if (topGenre != null) {
                    sql += " ORDER BY CASE WHEN genres LIKE ? THEN 0 ELSE 1 END, RANDOM() LIMIT 6"
                    params.add("%$topGenre%")
                } else {
                    sql += " ORDER BY rating_avg DESC, RANDOM() LIMIT 6"
                }
                
                val cursor = db.rawQuery(sql, if (params.isEmpty()) null else params.toTypedArray())
                
                while (cursor.moveToNext()) {
                    val mObj = JSONObject().apply {
                        put("id", cursor.getString(0))
                        put("title", cursor.getString(1))
                        put("category", cursor.getString(2))
                        put("media_type", cursor.getString(2))
                        put("synopsis", cursor.getString(3))
                        put("image_url", cursor.getString(4))
                        put("genres", JSONArray(cursor.getString(5).split(",")))
                        put("total_units", cursor.getInt(6))
                        put("seasons", cursor.getInt(7))
                        put("release_year", cursor.getInt(8))
                        put("airing_status", cursor.getString(9))
                        put("age_rating", cursor.getString(10))
                        put("rating_avg", cursor.getDouble(11))
                    }
                    arr.put(JSONObject().apply {
                        put("reason", if (topGenre != null && cursor.getString(5).contains(topGenre)) "Porque te gusta el género $topGenre" else "Sugerencia destacada")
                        put("score", cursor.getDouble(11))
                        put("media", mObj)
                    })
                }
                cursor.close()

                // Fallback: Si no hay nada nuevo, sugerir populares aleatorios
                if (arr.length() < 3) {
                    val cursor2 = db.rawQuery("SELECT id, title, category, synopsis, image_url, genres, total_units, seasons FROM media ORDER BY rating_avg DESC LIMIT 3", null)
                    while (cursor2.moveToNext()) {
                        arr.put(JSONObject().apply {
                            put("reason", "Muy popular en la comunidad")
                            put("score", 9.8)
                            put("media", JSONObject().apply {
                                put("id", cursor2.getString(0))
                                put("title", cursor2.getString(1))
                                put("category", cursor2.getString(2))
                                put("media_type", cursor2.getString(2))
                                put("synopsis", cursor2.getString(3))
                                put("image_url", cursor2.getString(4))
                                put("genres", JSONArray(cursor2.getString(5).split(",")))
                                put("total_units", cursor2.getInt(6))
                                put("seasons", cursor2.getInt(7))
                            })
                        })
                    }
                    cursor2.close()
                }
                return 200 to arr.toString()
            }

            // 12. Notificaciones Simples
            if (path == "/notifications" && method == "GET") {
                val list = JSONArray()
                if (prefs.getBoolean("notify_releases", true)) {
                    list.put(JSONObject().apply {
                        put("id", 1); put("title", "Nuevo episodio disponible"); put("message", "Attack on Titan S4 Cap 12 ya está aquí."); put("time", "Hace 2h")
                    })
                    list.put(JSONObject().apply {
                        put("id", 2); put("title", "Manga actualizado"); put("message", "Solo Leveling tiene un nuevo capítulo traducido."); put("time", "Hace 5h")
                    })
                }
                return 200 to list.toString()
            }

            // 13. Importar un elemento externo al catálogo local
            if (path == "/media/import" && method == "POST") {
                val json = JSONObject(body ?: "{}")
                val title = json.getString("title")
                val cat = json.getString("category")
                val syn = json.optString("synopsis", "Sin sinopsis")
                val img = json.optString("image_url", "")
                val genresArr = json.optJSONArray("genres") ?: JSONArray()
                val gList = mutableListOf<String>()
                for (i in 0 until genresArr.length()) { gList.add(genresArr.getString(i)) }
                
                val mId = json.optString("id", "loc_" + System.currentTimeMillis())
                
                val values = android.content.ContentValues().apply {
                    put("id", mId)
                    put("title", title)
                    put("category", cat)
                    put("synopsis", syn)
                    put("image_url", img)
                    put("genres", gList.joinToString(","))
                    put("total_units", json.optInt("total_units", 0))
                    put("seasons", json.optInt("seasons", 1))
                    put("release_year", json.optInt("release_year", 0))
                    put("airing_status", json.optString("airing_status", "Finalizado"))
                    put("age_rating", json.optString("age_rating", "Todo público"))
                    put("rating_avg", json.optDouble("rating_avg", 0.0))
                }
                db.insertWithOnConflict("media", null, values, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
                
                return 200 to JSONObject(body ?: "{}").apply { put("id", mId) }.toString()
            }

        } catch (e: Exception) {
            return 500 to "{\"detail\":\"Error en motor local: ${e.localizedMessage}\"}"
        }

        return 404 to "{\"detail\":\"Ruta local simulada no encontrada\"}"
    }

    private fun performExternalSearch(q: String, category: String): JSONArray {
        val results = JSONArray()
        when (category) {
            "anime", "manga" -> {
                results.putAll(searchAnilist(q, category.uppercase()))
            }
            "movie" -> {
                results.putAll(searchiTunes(q, "movie"))
            }
            "series" -> {
                results.putAll(searchTVMaze(q))
            }
            "book" -> {
                results.putAll(searchOpenLibrary(q))
            }
            "music" -> {
                results.putAll(searchiTunes(q, "music"))
            }
            "all" -> {
                results.putAll(searchAnilist(q, "ANIME"))
                results.putAll(searchiTunes(q, "movie"))
                results.putAll(searchTVMaze(q))
                results.putAll(searchiTunes(q, "music"))
            }
        }
        return results
    }

    private fun searchAnilist(q: String, type: String): JSONArray {
        val arr = JSONArray()
        val query = """
            query(${'$'}search: String, ${'$'}type: MediaType) {
                Page(perPage: 5) {
                    media(search: ${'$'}search, type: ${'$'}type) {
                        id title { romaji english } description bannerImage coverImage { large } genres type
                    }
                }
            }
        """.trimIndent()
        try {
            val body = JSONObject().apply {
                put("query", query)
                put("variables", JSONObject().apply { put("search", q); put("type", type) })
            }
            val resp = remotePost("https://graphql.anilist.co", body.toString())
            val data = JSONObject(resp).optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                arr.put(JSONObject().apply {
                    put("id", "ani_" + x.getString("id"))
                    put("title", x.getJSONObject("title").optString("romaji") ?: x.getJSONObject("title").optString("english"))
                    put("category", if (x.getString("type") == "ANIME") "anime" else "manga")
                    put("synopsis", x.optString("description", "").replace(Regex("<.*?>"), ""))
                    put("image_url", x.getJSONObject("coverImage").optString("large"))
                    put("genres", x.optJSONArray("genres") ?: JSONArray())
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchiTunes(q: String, media: String): JSONArray {
        val arr = JSONArray()
        try {
            val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(q, "UTF-8")}&media=$media&limit=5"
            val resp = remoteGet(url)
            val data = JSONObject(resp).optJSONArray("results") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                val isMusic = media == "music"
                arr.put(JSONObject().apply {
                    val rawTitle = x.optString("trackName") ?: x.optString("collectionName")
                    val artist = x.optString("artistName")
                    put("id", "itu_" + (x.optString("trackId") ?: x.optString("collectionId")))
                    put("title", if (isMusic) "$rawTitle - $artist" else rawTitle)
                    put("category", if (isMusic) "music" else "movie")
                    put("synopsis", x.optString("longDescription") ?: "Artista: $artist")
                    put("image_url", x.optString("artworkUrl100").replace("100x100", "600x600"))
                    put("genres", JSONArray().apply { put(x.optString("primaryGenreName")) })
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchTVMaze(q: String): JSONArray {
        val arr = JSONArray()
        try {
            val url = "https://api.tvmaze.com/search/shows?q=${URLEncoder.encode(q, "UTF-8")}"
            val resp = remoteGet(url)
            val data = JSONArray(resp)
            for (i in 0 until minOf(data.length(), 5)) {
                val x = data.getJSONObject(i).getJSONObject("show")
                arr.put(JSONObject().apply {
                    put("id", "tvm_" + x.getString("id"))
                    put("title", x.getString("name"))
                    put("category", "series")
                    put("synopsis", x.optString("summary", "").replace(Regex("<.*?>"), ""))
                    put("image_url", x.optJSONObject("image")?.optString("medium"))
                    put("genres", x.optJSONArray("genres") ?: JSONArray())
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun searchOpenLibrary(q: String): JSONArray {
        val arr = JSONArray()
        try {
            val url = "https://openlibrary.org/search.json?q=${URLEncoder.encode(q, "UTF-8")}&limit=5"
            val resp = remoteGet(url)
            val data = JSONObject(resp).optJSONArray("docs") ?: JSONArray()
            for (i in 0 until data.length()) {
                val x = data.getJSONObject(i)
                arr.put(JSONObject().apply {
                    put("id", "olb_" + x.optString("key").substringAfterLast("/"))
                    put("title", x.getString("title"))
                    put("category", "book")
                    val authors = x.optJSONArray("author_name")?.let { a -> List(a.length()){ a.getString(it) }.joinToString(", ") } ?: "Desconocido"
                    put("synopsis", "Autor(es): $authors")
                    val coverId = x.optInt("cover_i", -1)
                    put("image_url", if (coverId != -1) "https://covers.openlibrary.org/b/id/$coverId-L.jpg" else null)
                    put("genres", x.optJSONArray("subject") ?: JSONArray())
                })
            }
        } catch (e: Exception) {}
        return arr
    }

    private fun remoteGet(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun remotePost(urlStr: String, body: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toByteArray()) }
        return conn.inputStream.bufferedReader().use { it.readText() }.also { conn.disconnect() }
    }

    private fun JSONArray.putAll(other: JSONArray) {
        for (i in 0 until other.length()) this.put(other.get(i))
    }

    private fun insertDefaultMedia(db: android.database.sqlite.SQLiteDatabase) {
        val data = listOf(
            // id, title, cat, syn, img, genres, units, seasons, year, air_status, age, rating
            listOf("loc_1", "Attack on Titan", "anime", "La humanidad lucha contra gigantes.", "https://images.justwatch.com/poster/240562629/s276", "Acción,Fantasía,Misterio", "87", "4", "2013", "Finalizado", "16+", "9.1"),
            listOf("loc_2", "Interstellar", "movie", "Un viaje espacial buscando un nuevo hogar.", "https://images.justwatch.com/poster/176467364/s276", "Ciencia Ficción,Drama", "1", "1", "2014", "Finalizado", "Todo público", "8.7"),
            listOf("loc_3", "Solo Leveling", "manga", "El cazador más débil se convierte en el más fuerte.", "https://images.justwatch.com/poster/309193237/s276", "Acción,Aventura,Sobrenatural", "200", "1", "2018", "Finalizado", "14+", "8.9"),
            listOf("loc_4", "Breaking Bad", "series", "Un profesor de química produce metanfetamina.", "https://images.justwatch.com/poster/244304899/s276", "Drama", "62", "5", "2008", "Finalizado", "18+", "9.5"),
            listOf("loc_5", "El Alquimista", "book", "Un pastor viaja en busca de su tesoro.", "https://images.justwatch.com/poster/8575000/s276", "Aventura", "1", "1", "1988", "Finalizado", "Todo público", "8.0"),
            listOf("loc_6", "Oshi no Ko", "anime", "El lado oscuro de la industria del entretenimiento.", "https://images.justwatch.com/poster/305260195/s276", "Drama,Sobrenatural,Psicológico", "24", "2", "2023", "En emisión", "14+", "8.5"),
            listOf("loc_7", "Berserk", "manga", "Un guerrero solitario marcado por el destino.", "https://images.justwatch.com/poster/175825313/s276", "Seinen,Acción,Fantasía,Psicológico", "380", "1", "1989", "En emisión", "18+", "9.4"),
            listOf("loc_8", "Stranger Things", "series", "Niños enfrentan misterios sobrenaturales en los 80.", "https://images.justwatch.com/poster/301474720/s276", "Misterio,Ciencia Ficción,Horror", "34", "4", "2016", "En pausa", "14+", "8.7"),
            listOf("loc_9", "Given", "anime", "Una historia de amor y música.", "https://images.justwatch.com/poster/141019058/s276", "BL,Música,Romance", "11", "1", "2019", "Finalizado", "14+", "8.3"),
            listOf("loc_10", "Mushoku Tensei", "anime", "Reencarnación en un mundo de magia.", "https://images.justwatch.com/poster/241857997/s276", "Isekai,Fantasía,Aventura", "48", "2", "2021", "En emisión", "16+", "8.7"),
            listOf("loc_11", "Chihayafuru", "anime", "Pasión por el juego de Karuta.", "https://images.justwatch.com/poster/154406200/s276", "Josei,Deportes,Drama", "75", "3", "2011", "Finalizado", "Todo público", "8.5")
        )
        for (m in data) {
            val v = android.content.ContentValues().apply {
                put("id", m[0])
                put("title", m[1])
                put("category", m[2])
                put("synopsis", m[3])
                put("image_url", m[4])
                put("genres", m[5])
                put("total_units", m[6].toInt())
                put("seasons", m[7].toInt())
                put("release_year", m[8].toInt())
                put("airing_status", m[9])
                put("age_rating", m[10])
                put("rating_avg", m[11].toDouble())
            }
            db.insertWithOnConflict("media", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    // Class para persistencia SQLite Autónoma e interna de la app
    class LocalDatabaseHelper(context: Context) : 
        android.database.sqlite.SQLiteOpenHelper(context, "umt_local_db.db", null, 2) {
        
        override fun onCreate(db: android.database.sqlite.SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS users (id INTEGER PRIMARY KEY AUTOINCREMENT, email TEXT UNIQUE, display_name TEXT, avatar_url TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS media (id TEXT PRIMARY KEY, title TEXT, category TEXT, synopsis TEXT, image_url TEXT, genres TEXT, total_units INTEGER, seasons INTEGER, release_year INTEGER, airing_status TEXT, age_rating TEXT, rating_avg REAL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS library (media_id TEXT PRIMARY KEY, status TEXT, progress INTEGER, rating INTEGER, notes TEXT, total_units INTEGER)")
        }

        override fun onUpgrade(db: android.database.sqlite.SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                try {
                    db.execSQL("ALTER TABLE media ADD COLUMN total_units INTEGER DEFAULT 0")
                    db.execSQL("ALTER TABLE media ADD COLUMN seasons INTEGER DEFAULT 1")
                    db.execSQL("ALTER TABLE media ADD COLUMN release_year INTEGER")
                    db.execSQL("ALTER TABLE media ADD COLUMN airing_status TEXT")
                    db.execSQL("ALTER TABLE media ADD COLUMN age_rating TEXT")
                    db.execSQL("ALTER TABLE media ADD COLUMN rating_avg REAL")
                } catch (e: Exception) { }
            }
        }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun loadImage(urlStr: String, imageView: ImageView) {
        thread {
            try {
                val conn = URL(urlStr).openConnection() as HttpURLConnection
                conn.doInput = true
                conn.connect()
                val input = conn.inputStream
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                runOnUiThread {
                    imageView.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                // Si falla la carga, simplemente no se muestra o se queda el fondo oscuro
            }
        }
    }

    private fun makeRoundedDrawable(bgColor: String, strokeColor: String, radiusDp: Int): GradientDrawable {
        val r = radiusDp * resources.displayMetrics.density
        return GradientDrawable().apply {
            setColor(Color.parseColor(bgColor))
            setStroke(2, Color.parseColor(strokeColor))
            cornerRadius = r
        }
    }

    private fun makeMarginParams(horizontalDp: Int, verticalDp: Int): LinearLayout.LayoutParams {
        val d = resources.displayMetrics.density
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins((horizontalDp * d).toInt(), (verticalDp * d).toInt(), (horizontalDp * d).toInt(), (verticalDp * d).toInt())
        }
    }
}

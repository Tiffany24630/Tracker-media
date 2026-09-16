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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
        "Ciencia Ficción", "Romance", "Sobrenatural", "Misterio", "Terror"
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
        return prefs.getString("base_url", "http://10.0.2.2:8000/api/v1") ?: "http://10.0.2.2:8000/api/v1"
    }

    private fun setBaseUrl(url: String) {
        val clean = if (url.endsWith("/")) url.dropLast(1) else url
        val finalUrl = if (!clean.endsWith("/api/v1")) "$clean/api/v1" else clean
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
            text = "• Emulador: http://10.0.2.2:8000/api/v1\n• Teléfono físico: http://TU_IP_LOCAL:8000/api/v1"
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
            text = "📚 Biblioteca"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("library") }
        }
        tabExploreBtn = Button(this).apply {
            text = "🔍 Explorar"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("explore") }
        }
        tabRecsBtn = Button(this).apply {
            text = "✨ Para ti"
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { switchTab("recs") }
        }
        tabSettingsBtn = Button(this).apply {
            text = "⚙️ Ajustes"
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
            "all" to "🌐 Todos",
            "anime" to "⛩️ Anime",
            "manga" to "📖 Manga",
            "movie" to "🎬 Películas",
            "series" to "📺 Series",
            "book" to "📚 Libros"
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
            text = "Mi Biblioteca · ${selectedCategory.uppercase()}"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 14)
        }
        contentContainer.addView(headerText)

        // Filtro rápido de estado
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        val filters = listOf("all" to "Todos", "in_progress" to "Viendo", "planned" to "Plan", "completed" to "Fin")
        for ((fKey, fLabel) in filters) {
            val fBtn = Button(this).apply {
                text = fLabel
                textSize = 11f
                val isSelected = currentFilter == fKey
                background = makeRoundedDrawable(
                    if (isSelected) "#76E6D5" else "#211F2A",
                    if (isSelected) "#76E6D5" else "#333140",
                    10
                )
                setTextColor(if (isSelected) Color.BLACK else Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
        val status = entry.optString("status", "planned")
        var progress = entry.optDouble("progress", 0.0).toInt()
        val total = if (entry.isNull("total")) null else entry.optDouble("total").toInt()

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable("#181722", "#2B283A", 16)
            setPadding(26, 22, 26, 22)
            layoutParams = makeMarginParams(0, 14)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val typeBadge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C7ADFF"))
            background = makeRoundedDrawable("#261F36", "#4A3A69", 6)
            setPadding(12, 4, 12, 4)
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
            textSize = 11f
            setTextColor(Color.parseColor(stColor))
            setPadding(14, 0, 0, 0)
        }

        topRow.addView(typeBadge)
        topRow.addView(statusBadge)
        card.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 8)
        }
        card.addView(titleView)

        // Progreso y botones interactivos
        val progressRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 6, 0, 10)
        }

        val progressText = TextView(this).apply {
            text = "Progreso: $progress" + (if (total != null) " / $total" else " / ?")
            textSize = 14f
            setTextColor(Color.parseColor("#A8A5B2"))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val minusBtn = Button(this).apply {
            text = "-"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = makeRoundedDrawable("#262332", "#38344A", 10)
            layoutParams = LinearLayout.LayoutParams(76, 76).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                if (progress > 0) {
                    progress--
                    updateMediaProgress(mediaId, progress, progressText, total)
                }
            }
        }

        // REQUERIMIENTO 5: Validar que no exceda el límite máximo de la base de datos
        val plusBtn = Button(this).apply {
            text = "+1"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#130F1C"))
            background = makeRoundedDrawable("#76E6D5", "#92F5E6", 10)
            layoutParams = LinearLayout.LayoutParams(86, 76).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                if (total != null && total > 0 && progress >= total) {
                    toast("Has alcanzado el límite máximo ($total) según la base de datos.")
                    return@setOnClickListener
                }
                progress++
                updateMediaProgress(mediaId, progress, progressText, total)
            }
        }

        progressRow.addView(progressText)
        progressRow.addView(minusBtn)
        progressRow.addView(plusBtn)
        card.addView(progressRow)

        // Acciones: Editar manual y Marcar terminado
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 4, 0, 4)
        }

        val editBtn = Button(this).apply {
            text = "✏️ Editar"
            textSize = 12f
            setTextColor(Color.parseColor("#A8A5B2"))
            background = makeRoundedDrawable("#1F1D2B", "#322F42", 8)
            setPadding(16, 6, 16, 6)
            setOnClickListener {
                showManualEditDialog(entry, mediaId, title, progress, total)
            }
        }

        val finishBtn = Button(this).apply {
            text = "✓ Terminar"
            textSize = 12f
            setTextColor(Color.parseColor("#98C379"))
            background = makeRoundedDrawable("#19241B", "#27422C", 8)
            setPadding(16, 6, 16, 6)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(8, 0, 0, 0)
            }
            setOnClickListener {
                val targetTotal = total ?: progress
                updateLibraryEntryStatus(mediaId, "completed", targetTotal, total)
            }
        }

        val deleteBtn = Button(this).apply {
            text = "🗑️"
            textSize = 12f
            setTextColor(Color.parseColor("#FF8C94"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            }
            setOnClickListener {
                confirmDeleteFromLibrary(mediaId, title)
            }
        }

        actionRow.addView(editBtn)
        actionRow.addView(finishBtn)
        actionRow.addView(deleteBtn)
        card.addView(actionRow)

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

        box.addView(pLabel)
        box.addView(pInput)
        box.addView(tLabel)
        box.addView(tInput)
        b.setView(box)

        b.setPositiveButton("Guardar") { _, _ ->
            val pVal = pInput.text.toString().toDoubleOrNull() ?: 0.0
            val tVal = tInput.text.toString().toDoubleOrNull()

            if (tVal != null && tVal > 0 && pVal > tVal) {
                toast("El progreso no puede exceder el total ($tVal)")
                return@setPositiveButton
            }

            thread {
                try {
                    val body = JSONObject().apply {
                        put("status", entry.optString("status", "in_progress"))
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

        searchRow.addView(searchInput)
        searchRow.addView(searchBtn)
        contentContainer.addView(searchRow)

        val resultsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        contentContainer.addView(resultsBox)

        searchBtn.setOnClickListener {
            val q = searchInput.text.toString().trim()
            if (q.isEmpty()) {
                toast("Ingresa un término de búsqueda")
                return@setOnClickListener
            }

            resultsBox.removeAllViews()
            val searchingLabel = TextView(this).apply {
                text = "Buscando en catálogo y servicios externos..."
                setTextColor(Color.parseColor("#A8A5B2"))
                textSize = 14f
            }
            resultsBox.addView(searchingLabel)

            thread {
                try {
                    val encoded = URLEncoder.encode(q, "UTF-8")
                    val typeParam = if (selectedCategory != "all") "&media_type=$selectedCategory" else ""
                    val (code, resp) = request("GET", "/search?query=$encoded$typeParam", null)
                    if (code in 200..299) {
                        val array = JSONArray(resp)
                        runOnUiThread {
                            resultsBox.removeAllViews()
                            if (array.length() == 0) {
                                val empty = TextView(this).apply {
                                    text = "No se encontraron resultados para '$q'."
                                    setTextColor(Color.parseColor("#A8A5B2"))
                                }
                                resultsBox.addView(empty)
                            } else {
                                for (i in 0 until array.length()) {
                                    val item = array.getJSONObject(i)
                                    resultsBox.addView(createSearchResultCard(item))
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
    }

    private fun createSearchResultCard(item: JSONObject): View {
        val title = item.optString("title", "Sin título")
        val mediaType = item.optString("media_type", "medio")
        val source = item.optString("source", "web")
        val year = item.optInt("release_year", 0)
        val units = if (item.isNull("total_units")) null else item.optInt("total_units")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable("#181722", "#2B283A", 16)
            setPadding(26, 22, 26, 22)
            layoutParams = makeMarginParams(0, 14)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val badge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C7ADFF"))
            background = makeRoundedDrawable("#261F36", "#4A3A69", 6)
            setPadding(12, 4, 12, 4)
        }

        val sourceLabel = TextView(this).apply {
            text = "Fuente: $source" + (if (year > 0) " • $year" else "") + (if (units != null) " • $units caps" else "")
            textSize = 12f
            setTextColor(Color.parseColor("#A8A5B2"))
            setPadding(16, 0, 0, 0)
        }

        topRow.addView(badge)
        topRow.addView(sourceLabel)
        card.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 12)
        }
        card.addView(titleView)

        val addBtn = Button(this).apply {
            text = "＋ Añadir a mi biblioteca"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#1F2D33", "#28424B", 10)
            setOnClickListener {
                importAndAddMedia(item, this)
            }
        }
        card.addView(addBtn)

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

        val title = TextView(this).apply {
            text = "✨ Recomendaciones para ti"
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        val desc = TextView(this).apply {
            text = "Sugerencias inteligentes basadas en lo que has visto, leído y mejor calificado."
            textSize = 13f
            setTextColor(Color.parseColor("#A8A5B2"))
            setPadding(0, 4, 0, 16)
        }
        contentContainer.addView(title)
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
        val mediaId = media.getString("id")
        val title = media.optString("title", "Sin título")
        val mediaType = media.optString("media_type", "medio")
        val score = item.optDouble("score", 0.0)
        val reason = item.optString("reason", "Obra destacada")

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = makeRoundedDrawable("#1A1826", "#37314E", 16)
            setPadding(26, 22, 26, 22)
            layoutParams = makeMarginParams(0, 14)
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val typeBadge = TextView(this).apply {
            text = mediaType.uppercase()
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#C7ADFF"))
            background = makeRoundedDrawable("#261F36", "#4A3A69", 6)
            setPadding(12, 4, 12, 4)
        }

        val scoreBadge = TextView(this).apply {
            text = "Afinidad: $score"
            textSize = 11f
            setTextColor(Color.parseColor("#76E6D5"))
            setPadding(14, 0, 0, 0)
        }

        topRow.addView(typeBadge)
        topRow.addView(scoreBadge)
        card.addView(topRow)

        val titleView = TextView(this).apply {
            text = title
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setPadding(0, 10, 0, 6)
        }
        card.addView(titleView)

        val reasonView = TextView(this).apply {
            text = "💡 $reason"
            textSize = 12f
            setTextColor(Color.parseColor("#BCAADB"))
            setPadding(0, 0, 0, 12)
        }
        card.addView(reasonView)

        val addBtn = Button(this).apply {
            text = "＋ Añadir a biblioteca"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#76E6D5"))
            background = makeRoundedDrawable("#1F2D33", "#28424B", 10)
            setOnClickListener {
                thread {
                    try {
                        val body = JSONObject().apply {
                            put("status", "planned")
                            put("progress", 0)
                        }
                        val (c, _) = request("PUT", "/library/$mediaId", body.toString())
                        if (c in 200..299) {
                            runOnUiThread {
                                text = "✓ En biblioteca"
                                isEnabled = false
                                toast("Añadido con éxito")
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { toast("Error: ${e.localizedMessage}") }
                    }
                }
            }
        }
        card.addView(addBtn)

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
    // Peticiones de Red HTTP (HttpURLConnection)
    // -------------------------------------------------------------
    private fun request(method: String, path: String, body: String?): Pair<Int, String> {
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

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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

package com.umt.tracker

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.*
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var token: String? = null
    private val baseUrl = "http://10.0.2.2:8000/api/v1"
    private lateinit var output: TextView
    private lateinit var query: EditText
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); showAuth() }
    private fun showAuth() {
        val box=LinearLayout(this); box.orientation=LinearLayout.VERTICAL; box.setPadding(40,50,40,30)
        val title=TextView(this); title.text="Universal Media Tracker"; title.textSize=28f; title.setTextColor(Color.DKGRAY)
        val email=EditText(this); email.hint="Email"; val password=EditText(this); password.hint="Contraseña"; password.inputType=129
        val name=EditText(this); name.hint="Nombre (solo registro)"; val login=Button(this); login.text="Iniciar sesión"; val reg=Button(this); reg.text="Crear cuenta"
        box.addView(title); box.addView(name); box.addView(email); box.addView(password); box.addView(login); box.addView(reg); setContentView(box)
        login.setOnClickListener { auth(false,email.text.toString(),password.text.toString(),name.text.toString()) }; reg.setOnClickListener { auth(true,email.text.toString(),password.text.toString(),name.text.toString()) }
    }
    private fun auth(register:Boolean,email:String,password:String,name:String){thread{try{val body=if(register)"{\"email\":\"$email\",\"display_name\":\"$name\",\"password\":\"$password\"}" else "{\"email\":\"$email\",\"password\":\"$password\"}"; val path=if(register)"/auth/register" else "/auth/login"; val r=request("POST",path,body); if(r.first in 200..299){token=org.json.JSONObject(r.second).getString("access_token");runOnUiThread{showHome()}} else runOnUiThread{toast(r.second)}}catch(e:Exception){runOnUiThread{toast(e.message?:"Error")}}}}
    private fun showHome(){val box=LinearLayout(this);box.orientation=LinearLayout.VERTICAL;box.setPadding(30,40,30,20);val title=TextView(this);title.text="Explora tu contenido";title.textSize=24f;query=EditText(this);query.hint="Anime, película, libro...";val search=Button(this);search.text="Buscar";output=TextView(this);output.textSize=16f;val scroll=ScrollView(this);scroll.addView(output);box.addView(title);box.addView(query);box.addView(search);box.addView(scroll,LinearLayout.LayoutParams(-1,0,1f));setContentView(box);search.setOnClickListener{search()};search()}
    private fun search(){thread{try{val r=request("GET","/search?query="+java.net.URLEncoder.encode(query.text.toString().ifBlank{"One Piece"},"UTF-8"),null);runOnUiThread{output.text=r.second}}catch(e:Exception){runOnUiThread{toast(e.message?:"Error")}}}}
    private fun request(method:String,path:String,body:String?):Pair<Int,String>{val c=URL(baseUrl+path).openConnection() as HttpURLConnection;c.requestMethod=method;c.connectTimeout=15000;c.readTimeout=15000;c.setRequestProperty("Accept","application/json");token?.let{c.setRequestProperty("Authorization","Bearer $it")};if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toByteArray())}};val code=c.responseCode;val text=(if(code>=400)c.errorStream else c.inputStream)?.bufferedReader()?.readText()?:("");c.disconnect();return code to text}
    private fun toast(s:String){Toast.makeText(this,s,Toast.LENGTH_LONG).show()}
}

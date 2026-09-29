package fr.wokgui.phototv
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.random.Random
data class PhotoItem(val uri:Uri,val title:String,val album:String)
class MainActivity:AppCompatActivity(){
 lateinit var image:ImageView; lateinit var title:TextView; lateinit var album:TextView; lateinit var button:Button
 val photos=mutableListOf<PhotoItem>(); val handler=android.os.Handler(android.os.Looper.getMainLooper())
 override fun onCreate(b:Bundle?){super.onCreate(b);setContentView(R.layout.activity_main);image=findViewById(R.id.photo);title=findViewById(R.id.title);album=findViewById(R.id.album);button=findViewById(R.id.importButton);button.setOnClickListener{startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),42)}}
 override fun onActivityResult(req:Int,res:Int,data:Intent?){super.onActivityResult(req,res,data);if(req==42&&res==Activity.RESULT_OK&&data?.data!=null){val u=data.data!!;contentResolver.takePersistableUriPermission(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);button.text="Import en cours…";Thread{scan(DocumentFile.fromTreeUri(this,u)!!);runOnUiThread{button.visibility=View.GONE;next()}}.start()}}
 fun scan(dir:DocumentFile){val c=dir.listFiles();val js=c.filter{it.isFile&&it.name?.endsWith(".json",true)==true};val ms=c.filter{it.isFile&&it.type?.startsWith("image/")==true};for(j in js){try{val n=j.name!!.removeSuffix(".json");val m=ms.firstOrNull{it.name==n||n.startsWith(it.name?:"§§")}?:continue;val s=contentResolver.openInputStream(j.uri)!!.use{BufferedReader(InputStreamReader(it)).readText()};val o=JSONObject(s);val d=o.optString("description","").trim();photos+=PhotoItem(m.uri,if(d.isNotBlank())d else o.optString("title",m.name?:""),dir.name?:"Album")}catch(_:Exception){}};c.filter{it.isDirectory}.forEach{scan(it)}}
 fun next(){if(photos.isEmpty()){button.visibility=View.VISIBLE;button.text="Aucune photo trouvée";return};val p=photos[Random.nextInt(photos.size)];image.setImageURI(p.uri);title.text=p.title;album.text=p.album;handler.postDelayed({next()},10000)}
 override fun onKeyDown(k:Int,e:android.view.KeyEvent?):Boolean{if(k==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){next();return true};return super.onKeyDown(k,e)}
}
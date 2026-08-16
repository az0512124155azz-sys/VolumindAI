package com.volumind.remote

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.*
import okhttp3.*
import java.util.concurrent.TimeUnit

data class ChatMessage(val id:String,val sender:String,val text:String,val timestamp:Long=System.currentTimeMillis())
data class BuildStep(val index:Int,val title:String,val state:String)
data class RemoteQuestion(val id:String,val text:String,val options:List<String>)
data class MobileAttachment(val name:String,val type:String,val text:String="",val data:String="")
data class RemoteState(val connection:String="מנותק",val paired:Boolean=false,val fusionOnline:Boolean=false,val messages:List<ChatMessage> = emptyList(),val steps:List<BuildStep> = emptyList(),val screenshotUrl:String?=null,val screenshotCaption:String="עדיין אין צילום מ־Fusion",val questions:List<RemoteQuestion> = emptyList())

object RemoteSession {
    private val json=Json{ignoreUnknownKeys=true}; private val http=OkHttpClient.Builder().pingInterval(20,TimeUnit.SECONDS).build()
    private val mutable=MutableStateFlow(RemoteState()); val state:StateFlow<RemoteState> = mutable
    private var socket:WebSocket?=null; private var context:Context?=null; private var completionNotified=false

    fun open(appContext:Context,url:String,pairingCode:String){
        context=appContext.applicationContext
        if(!url.startsWith("wss://")){mutable.value=mutable.value.copy(connection="נדרש חיבור wss מאובטח");return}
        mutable.value=mutable.value.copy(connection="מתחבר…");socket?.cancel()
        socket=http.newWebSocket(Request.Builder().url(url).build(),object:WebSocketListener(){
            override fun onOpen(ws:WebSocket,response:Response){ws.send(buildJsonObject{put("type","authenticate");put("role","mobile");put("pairingCode",pairingCode)}.toString())}
            override fun onMessage(ws:WebSocket,text:String)=receive(text)
            override fun onClosed(ws:WebSocket,code:Int,reason:String){mutable.value=mutable.value.copy(connection="מנותק",paired=false,fusionOnline=false)}
            override fun onFailure(ws:WebSocket,t:Throwable,response:Response?){mutable.value=mutable.value.copy(connection="שגיאת חיבור: ${t.message?:"לא ידועה"}",paired=false,fusionOnline=false)}
        })
    }
    fun close(){socket?.close(1000,"service stopped");socket=null}
    fun sendChat(text:String,attachments:List<MobileAttachment> = emptyList()){
        val clean=text.trim().take(4000);if((clean.isEmpty()&&attachments.isEmpty())||!mutable.value.paired)return
        socket?.send(buildJsonObject{
            put("type","chat.command");put("text",clean.ifEmpty{"השתמש בקבצים המצורפים"})
            put("attachments",buildJsonArray{attachments.take(6).forEach{file->add(buildJsonObject{put("name",file.name);put("type",file.type);if(file.text.isNotEmpty())put("text",file.text);if(file.data.isNotEmpty())put("data",file.data)})}})
        }.toString())
        val label=if(attachments.isEmpty())clean else "$clean\n📎 ${attachments.joinToString(" · "){it.name}}".trim()
        mutable.value=mutable.value.copy(messages=mutable.value.messages+ChatMessage("local-${System.nanoTime()}","user",label))
    }
    fun stopBuild(){socket?.send("{\"type\":\"build.stop\"}")}
    fun submitAnswers(answers:Map<String,String>){if(answers.size!=mutable.value.questions.size)return;socket?.send(buildJsonObject{put("type","questionnaire.answer");put("answers",buildJsonObject{answers.forEach{(k,v)->put(k,v)}})}.toString());mutable.value=mutable.value.copy(questions=emptyList())}

    private fun receive(raw:String){runCatching{val o=json.parseToJsonElement(raw).jsonObject;when(o["type"]?.jsonPrimitive?.content){
        "authenticated"->mutable.value=mutable.value.copy(connection="מחובר לשרת · בודק את Fusion",paired=true)
        "presence"->{if(o.containsKey("desktopConnected")){val online=o["desktopConnected"]?.jsonPrimitive?.booleanOrNull==true;mutable.value=mutable.value.copy(fusionOnline=online,connection=if(online)"מחובר ל־Fusion" else "מחובר לשרת · Fusion מנותק")}}
        "chat.message"->mutable.value=mutable.value.copy(messages=mutable.value.messages+ChatMessage(o["id"]?.jsonPrimitive?.content?:"remote-${System.nanoTime()}","assistant",o["text"]?.jsonPrimitive?.content.orEmpty()))
        "build.plan"->{completionNotified=false;val titles=o["steps"]?.jsonPrimitive?.content.orEmpty().split("|").filter(String::isNotBlank);mutable.value=mutable.value.copy(steps=titles.mapIndexed{i,t->BuildStep(i,t,"waiting")});notify("Volumind התחיל לעבוד","תוכנית הבנייה כוללת ${titles.size} שלבים",2101)}
        "build.step"->{val index=o["index"]?.jsonPrimitive?.content?.toIntOrNull()?:0;val status=o["status"]?.jsonPrimitive?.content?:"running";val updated=mutable.value.steps.map{if(it.index==index)it.copy(state=status)else it};mutable.value=mutable.value.copy(steps=updated);if(status=="error")notify("Volumind עצר","אירעה בעיה בשלב ${index+1}",2102) else if(!completionNotified&&updated.isNotEmpty()&&updated.all{it.state=="done"}){completionNotified=true;notify("המודל מוכן","Volumind סיים את כל שלבי הבנייה ב־Fusion",2103)}}
        "fusion.screenshot"->mutable.value=mutable.value.copy(screenshotUrl=o["url"]?.jsonPrimitive?.content,screenshotCaption=o["caption"]?.jsonPrimitive?.content?:"צילום חדש מ־Fusion")
        "questionnaire"->{val questions=o["questions"]?.jsonArray?.map{val q=it.jsonObject;RemoteQuestion(q["id"]?.jsonPrimitive?.content.orEmpty(),q["text"]?.jsonPrimitive?.content.orEmpty(),q["options"]?.jsonArray?.map{x->x.jsonPrimitive.content}.orEmpty())}.orEmpty();mutable.value=mutable.value.copy(questions=questions);notify("Volumind מחכה לתשובה","יש כמה שאלות לפני המשך הבנייה",2104)}
        "error"->{val raw=o["message"]?.jsonPrimitive?.content?:"שגיאה";val message=when(raw){"Pairing code expired"->"קוד הצימוד אינו פעיל. הפעל את מחבר Windows ונסה שוב";"Fusion connector is offline"->"מחבר Windows או Fusion אינם מחוברים";"Invalid pairing code"->"קוד הצימוד חייב להכיל 6 ספרות";"Pairing code already in use"->"הקוד כבר רשום על מחשב מחובר אחר";else->raw};mutable.value=mutable.value.copy(connection=message,paired=false,fusionOnline=false);notify("שגיאה ב־Volumind",message,2105)}
    }}.onFailure{mutable.value=mutable.value.copy(connection="התקבל מידע לא תקין")}}

    private fun notify(title:String,text:String,id:Int){val c=context?:return;val manager=c.getSystemService(NotificationManager::class.java);manager.createNotificationChannel(NotificationChannel("volumind_build","עדכוני בנייה",NotificationManager.IMPORTANCE_HIGH));if(android.os.Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(c,android.Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)manager.notify(id,NotificationCompat.Builder(c,"volumind_build").setSmallIcon(R.drawable.ic_notification_volumind).setContentTitle(title).setContentText(text).setAutoCancel(true).build())}
}

class RelayClient(private val context:Context){val state:StateFlow<RemoteState> = RemoteSession.state;fun connect(url:String,pairingCode:String)=VolumindConnectionService.start(context,url,pairingCode);fun sendChat(text:String,attachments:List<MobileAttachment> = emptyList())=RemoteSession.sendChat(text,attachments);fun stopBuild()=RemoteSession.stopBuild();fun submitAnswers(answers:Map<String,String>)=RemoteSession.submitAnswers(answers)}

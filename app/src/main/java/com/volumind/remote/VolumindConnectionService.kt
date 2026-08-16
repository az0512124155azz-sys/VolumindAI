package com.volumind.remote

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class VolumindConnectionService:Service(){
    override fun onCreate(){super.onCreate();getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"חיבור Volumind",NotificationManager.IMPORTANCE_LOW))}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int{val prefs=getSharedPreferences("connection",MODE_PRIVATE);val url=intent?.getStringExtra("url")?:prefs.getString("url",null);val code=intent?.getStringExtra("code")?:prefs.getString("code",null);val openApp=PendingIntent.getActivity(this,0,Intent(this,MainActivity::class.java),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT);startForeground(2001,NotificationCompat.Builder(this,CHANNEL).setSmallIcon(R.drawable.ic_notification_volumind).setContentTitle("Volumind מחובר ל־Fusion").setContentText("מקבל תהליך וצילומים גם כשהאפליקציה ברקע").setContentIntent(openApp).setOngoing(true).build());if(url!=null&&code!=null){prefs.edit().putString("url",url).putString("code",code).apply();RemoteSession.open(this,url,code)};return START_STICKY}
    override fun onDestroy(){RemoteSession.close();super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object{private const val CHANNEL="volumind_connection";fun start(context:Context,url:String,code:String){ContextCompat.startForegroundService(context,Intent(context,VolumindConnectionService::class.java).putExtra("url",url).putExtra("code",code))}}
}

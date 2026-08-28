package com.vinod.cryptofuturessignal;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SignalService extends Service {
    public static final String CHANNEL_MONITOR = "signal_monitor";
    public static final String CHANNEL_ALERTS = "trade_signal_alerts";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean running = true;
    private final String[] symbols = {"BTCUSDT","ETHUSDT","SOLUSDT","ADAUSDT","XRPUSDT"};

    @Override public void onCreate() {
        super.onCreate(); createChannels();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_MONITOR)
                .setSmallIcon(com.vinod.cryptofuturessignal.R.drawable.ic_app)
                .setContentTitle("Crypto signal monitor is active")
                .setContentText("Checking futures setups about every 60 seconds")
                .setContentIntent(pi).setOngoing(true).build();
        startForeground(1001, n);
        worker.submit(this::loop);
        return START_STICKY;
    }

    private void loop() {
        SharedPreferences prefs=getSharedPreferences("signals",MODE_PRIVATE);
        while(running) {
            String tf=prefs.getString("timeframe","15m");
            for(String symbol:symbols) {
                if(!running)break;
                MarketAnalyzer.Result r=MarketAnalyzer.analyze(symbol,tf);
                if(r.isStrongDirectional()) {
                    String key="last_"+symbol+"_"+tf;
                    String old=prefs.getString(key,"");
                    String state=r.signal+":"+(r.strength/5);
                    if(!state.equals(old)) {
                        sendSignal(r);
                        prefs.edit().putString(key,state).apply();
                    }
                }
            }
            try { TimeUnit.SECONDS.sleep(60); } catch(InterruptedException ignored) { Thread.currentThread().interrupt(); break; }
        }
    }

    private void sendSignal(MarketAnalyzer.Result r) {
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pi=PendingIntent.getActivity(this,r.symbol.hashCode(),open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        String title=r.symbol+"  "+r.signal+" setup";
        String text="Strength "+r.strength+"% • Price "+MarketAnalyzer.price(r.price)+" • SL "+MarketAnalyzer.price(r.stopLoss)+" • T1 "+MarketAnalyzer.price(r.target1);
        Notification n=new Notification.Builder(this,CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_app).setContentTitle(title).setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text+"\nIndicator-based signal; verify before trading."))
                .setAutoCancel(true).setContentIntent(pi).build();
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).notify(2000+Math.abs(r.symbol.hashCode()%1000),n);
    }

    private void createChannels() {
        if(Build.VERSION.SDK_INT>=26) {
            NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel monitor=new NotificationChannel(CHANNEL_MONITOR,"Signal monitoring",NotificationManager.IMPORTANCE_LOW);
            monitor.setDescription("Ongoing status while real-time signal monitoring is enabled");
            NotificationChannel alerts=new NotificationChannel(CHANNEL_ALERTS,"Trading signal alerts",NotificationManager.IMPORTANCE_HIGH);
            alerts.setDescription("Strong LONG/SHORT futures setup notifications");
            nm.createNotificationChannel(monitor); nm.createNotificationChannel(alerts);
        }
    }

    @Override public void onDestroy() { running=false; worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}

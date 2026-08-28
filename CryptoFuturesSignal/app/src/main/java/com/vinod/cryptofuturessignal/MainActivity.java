package com.vinod.cryptofuturessignal;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private final String[] symbols={"BTCUSDT","ETHUSDT","SOLUSDT","ADAUSDT","XRPUSDT"};
    private final String[] frames={"1m","5m","15m","1h","4h"};
    private final ExecutorService pool=Executors.newFixedThreadPool(3);
    private LinearLayout cards;
    private TextView status;
    private Spinner timeframe;
    private Switch alerts;
    private final Map<String,PairCard> cardMap=new HashMap<>();
    private volatile boolean destroyed=false;
    private final Handler autoHandler=new Handler(Looper.getMainLooper());
    private final Runnable autoRefresh=new Runnable(){ public void run(){ if(!destroyed){ refreshAll(); autoHandler.postDelayed(this,30000); } } };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); requestNotif(); buildUi(); refreshAll();
    }

    private void buildUi() {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(0xFF07111F);
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(18),dp(16),dp(18),dp(28));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-2));

        TextView title=text("FUTURES SIGNAL LAB",26,0xFFF4F7FB,true); root.addView(title);
        TextView sub=text("Live indicator analysis • USDT perpetual pairs",14,0xFF9CB0C8,false); root.addView(sub,lp(-1,-2,0,4,0,18));

        LinearLayout controls=new LinearLayout(this); controls.setGravity(Gravity.CENTER_VERTICAL); controls.setOrientation(LinearLayout.HORIZONTAL);
        timeframe=new Spinner(this);
        ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,frames); timeframe.setAdapter(adapter); timeframe.setSelection(2);
        String saved=getSharedPreferences("signals",MODE_PRIVATE).getString("timeframe","15m");
        for(int i=0;i<frames.length;i++)if(frames[i].equals(saved))timeframe.setSelection(i);
        controls.addView(timeframe,new LinearLayout.LayoutParams(0,dp(48),1));
        Button refresh=new Button(this); refresh.setText("REFRESH"); refresh.setAllCaps(false); refresh.setTextColor(0xFF07111F); refresh.setBackground(round(0xFF56D6C5,14));
        controls.addView(refresh,new LinearLayout.LayoutParams(dp(118),dp(48)));
        root.addView(controls);

        alerts=new Switch(this); alerts.setText("  Real-time signal notifications"); alerts.setTextColor(0xFFF4F7FB); alerts.setTextSize(14);
        alerts.setChecked(getSharedPreferences("signals",MODE_PRIVATE).getBoolean("monitoring",false));
        root.addView(alerts,lp(-1,dp(54),0,8,0,4));
        status=text("Ready",12,0xFF9CB0C8,false); root.addView(status,lp(-1,-2,0,0,0,12));

        TextView warn=text("Signals are rule-based market analysis, not guaranteed predictions. Futures can cause rapid losses. Always verify the setup and use your own risk limit.",12,0xFFFFD18A,false);
        warn.setPadding(dp(12),dp(10),dp(12),dp(10)); warn.setBackground(round(0xFF182231,12)); root.addView(warn,lp(-1,-2,0,0,0,14));

        cards=new LinearLayout(this); cards.setOrientation(LinearLayout.VERTICAL); root.addView(cards);
        for(String s:symbols){PairCard pc=new PairCard(s); cardMap.put(s,pc); cards.addView(pc.root,lp(-1,-2,0,0,0,12));}

        refresh.setOnClickListener(v->refreshAll());
        timeframe.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id) {
                getSharedPreferences("signals",MODE_PRIVATE).edit().putString("timeframe",frames[pos]).apply();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });
        alerts.setOnCheckedChangeListener((buttonView,isChecked)->{
            getSharedPreferences("signals",MODE_PRIVATE).edit().putBoolean("monitoring",isChecked).apply();
            if(isChecked){ requestNotif(); Intent i=new Intent(this,SignalService.class); if(Build.VERSION.SDK_INT>=26)startForegroundService(i); else startService(i); status.setText("Background alerts enabled"); }
            else { stopService(new Intent(this,SignalService.class)); status.setText("Background alerts disabled"); }
        });
        setContentView(scroll);
    }

    @Override protected void onResume(){ super.onResume(); autoHandler.removeCallbacks(autoRefresh); autoHandler.postDelayed(autoRefresh,30000); }
    @Override protected void onPause(){ autoHandler.removeCallbacks(autoRefresh); super.onPause(); }

    private void refreshAll() {
        final String tf=(String)timeframe.getSelectedItem();
        status.setText("Analyzing "+tf+" market data…");
        final int[] remaining={symbols.length};
        for(String symbol:symbols) pool.submit(()->{
            MarketAnalyzer.Result r=MarketAnalyzer.analyze(symbol,tf);
            runOnUiThread(()->{
                if(destroyed)return; cardMap.get(symbol).bind(r); remaining[0]--;
                if(remaining[0]==0) status.setText("Updated "+new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(new Date()));
            });
        });
    }

    private final class PairCard {
        LinearLayout root; TextView symbol,price,signal,metrics,risk,source; Sparkline spark;
        PairCard(String s){
            root=new LinearLayout(MainActivity.this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(14),dp(13),dp(14),dp(13)); root.setBackground(round(0xFF0E1B2B,18));
            LinearLayout top=new LinearLayout(MainActivity.this); top.setGravity(Gravity.CENTER_VERTICAL);
            symbol=text(s,18,0xFFF4F7FB,true); top.addView(symbol,new LinearLayout.LayoutParams(0,-2,1));
            signal=text("LOADING",13,0xFF9CB0C8,true); signal.setGravity(Gravity.CENTER); signal.setPadding(dp(10),dp(6),dp(10),dp(6)); signal.setBackground(round(0xFF253247,20)); top.addView(signal);
            root.addView(top);
            price=text("—",24,0xFFF4F7FB,true); root.addView(price,lp(-1,-2,0,6,0,0));
            spark=new Sparkline(MainActivity.this); root.addView(spark,new LinearLayout.LayoutParams(-1,dp(72)));
            metrics=text("RSI —   EMA —   MACD —",12,0xFF9CB0C8,false); root.addView(metrics);
            risk=text("SL —   T1 —   T2 —",12,0xFFC8D6E5,false); root.addView(risk,lp(-1,-2,0,5,0,0));
            source=text("",10,0xFF6F839C,false); root.addView(source,lp(-1,-2,0,6,0,0));
        }
        void bind(MarketAnalyzer.Result r){
            if("ERROR".equals(r.signal)){
                signal.setText("ERROR"); signal.setTextColor(0xFFFF8A8A); price.setText("Data unavailable"); metrics.setText(r.error); risk.setText("Check internet access or retry."); return;
            }
            int c="LONG".equals(r.signal)?0xFF56D6A9:("SHORT".equals(r.signal)?0xFFFF7D86:0xFFFFD18A);
            signal.setText(r.signal+"  "+r.strength+"%"); signal.setTextColor(c); signal.setBackground(round(0xFF182A36,20));
            String ch=String.format(Locale.US,"%+.2f%%",r.change24h); price.setText("$"+MarketAnalyzer.price(r.price)+"   "+ch);
            metrics.setText(String.format(Locale.US,"RSI %.1f   EMA9/21 %s   MACD %s   Vol %.2fx",r.rsi,r.ema9>r.ema21?"↑":"↓",r.macdHist>=0?"+":"−",r.volumeRatio));
            risk.setText("SL "+MarketAnalyzer.price(r.stopLoss)+"   T1 "+MarketAnalyzer.price(r.target1)+"   T2 "+MarketAnalyzer.price(r.target2));
            source.setText(r.provider+" • funding "+String.format(Locale.US,"%.4f%%",r.fundingRate*100)+" • score "+r.score);
            spark.setData(r.closes,c);
        }
    }

    private static final class Sparkline extends View {
        private List<Double> data=new ArrayList<>(); private int color=0xFF56D6C5; private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        Sparkline(android.content.Context c){super(c); p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3f);}
        void setData(List<Double>d,int c){data=new ArrayList<>(d);color=c;invalidate();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas); if(data.size()<2)return; double min=Double.MAX_VALUE,max=-Double.MAX_VALUE; for(double v:data){min=Math.min(min,v);max=Math.max(max,v);} double range=Math.max(1e-9,max-min); Path path=new Path(); float w=getWidth(),h=getHeight(); for(int i=0;i<data.size();i++){float x=i*w/(data.size()-1);float y=(float)(h-6-(data.get(i)-min)/range*(h-12)); if(i==0)path.moveTo(x,y);else path.lineTo(x,y);} p.setColor(color); canvas.drawPath(path,p);}
    }

    private TextView text(String s,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;}
    private GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(dp(radius));return g;}
    private LinearLayout.LayoutParams lp(int w,int h,int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(w,h);p.setMargins(dp(l),dp(t),dp(r),dp(b));return p;}
    private int dp(int v){return (int)(v*getResources().getDisplayMetrics().density+0.5f);}
    private void requestNotif(){if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);}
    @Override protected void onDestroy(){destroyed=true;autoHandler.removeCallbacks(autoRefresh);pool.shutdownNow();super.onDestroy();}
}

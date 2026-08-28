package com.vinod.cryptofuturessignal;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MarketAnalyzer {
    private static final int LIMIT = 200;

    public static final class Result {
        public String symbol;
        public String timeframe;
        public String provider;
        public String signal;
        public int score;
        public int strength;
        public double price;
        public double change24h;
        public double rsi;
        public double ema9;
        public double ema21;
        public double macdHist;
        public double atr;
        public double volumeRatio;
        public double fundingRate;
        public double openInterest;
        public double stopLoss;
        public double target1;
        public double target2;
        public long updatedAt;
        public List<Double> closes = new ArrayList<>();
        public String error;

        public boolean isStrongDirectional() {
            return ("LONG".equals(signal) || "SHORT".equals(signal)) && strength >= 72;
        }
    }

    private static final class Candle {
        double open, high, low, close, volume;
        Candle(double o, double h, double l, double c, double v) {
            open=o; high=h; low=l; close=c; volume=v;
        }
    }

    private static final class Snapshot {
        String provider;
        List<Candle> candles = new ArrayList<>();
        double change24h;
        double funding;
        double openInterest;
    }

    public static Result analyze(String symbol, String timeframe) {
        Result out = new Result();
        out.symbol = symbol;
        out.timeframe = timeframe;
        out.updatedAt = System.currentTimeMillis();
        try {
            Snapshot s;
            try {
                s = fetchBinance(symbol, timeframe);
            } catch (Exception primary) {
                s = fetchBybit(symbol, timeframe);
            }
            if (s.candles.size() < 60) throw new IllegalStateException("Not enough candle data");
            out.provider = s.provider;
            out.change24h = s.change24h;
            out.fundingRate = s.funding;
            out.openInterest = s.openInterest;

            int n = s.candles.size();
            double[] close = new double[n];
            double[] high = new double[n];
            double[] low = new double[n];
            double[] vol = new double[n];
            for (int i=0;i<n;i++) {
                Candle c=s.candles.get(i);
                close[i]=c.close; high[i]=c.high; low[i]=c.low; vol[i]=c.volume;
                if (i >= n-50) out.closes.add(c.close);
            }
            out.price = close[n-1];
            out.ema9 = ema(close, 9);
            out.ema21 = ema(close, 21);
            out.rsi = rsi(close, 14);
            double[] macd = macd(close);
            out.macdHist = macd[2];
            out.atr = atr(high, low, close, 14);
            out.volumeRatio = vol[n-1] / Math.max(1e-9, average(vol, Math.max(0,n-21), n-1));

            int score = 0;
            if (out.ema9 > out.ema21) score += 2; else score -= 2;
            if (out.price > out.ema21) score += 1; else score -= 1;
            if (out.macdHist > 0) score += 2; else score -= 2;
            if (out.rsi >= 52 && out.rsi <= 70) score += 1;
            else if (out.rsi <= 48 && out.rsi >= 30) score -= 1;
            else if (out.rsi > 76) score -= 1;
            else if (out.rsi < 24) score += 1;
            if (out.volumeRatio >= 1.20) score += out.price >= out.ema9 ? 1 : -1;
            if (out.change24h > 1.0) score += 1;
            else if (out.change24h < -1.0) score -= 1;
            if (out.fundingRate > 0.0005) score -= 1;
            else if (out.fundingRate < -0.0005) score += 1;

            out.score = score;
            if (score >= 5) out.signal = "LONG";
            else if (score <= -5) out.signal = "SHORT";
            else out.signal = "WAIT";
            out.strength = Math.min(95, 50 + Math.abs(score) * 6);
            if ("WAIT".equals(out.signal)) out.strength = Math.min(69, out.strength);

            double risk = Math.max(out.atr * 1.5, out.price * 0.0035);
            if ("SHORT".equals(out.signal)) {
                out.stopLoss = out.price + risk;
                out.target1 = out.price - risk * 1.5;
                out.target2 = out.price - risk * 2.5;
            } else {
                out.stopLoss = out.price - risk;
                out.target1 = out.price + risk * 1.5;
                out.target2 = out.price + risk * 2.5;
            }
        } catch (Exception ex) {
            out.signal = "ERROR";
            out.error = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
        }
        return out;
    }

    private static Snapshot fetchBinance(String symbol, String tf) throws Exception {
        Snapshot s = new Snapshot(); s.provider = "Binance Futures";
        String kline = get("https://fapi.binance.com/fapi/v1/klines?symbol="+symbol+"&interval="+tf+"&limit="+LIMIT);
        JSONArray a = new JSONArray(kline);
        for (int i=0;i<a.length();i++) {
            JSONArray c=a.getJSONArray(i);
            s.candles.add(new Candle(c.getDouble(1),c.getDouble(2),c.getDouble(3),c.getDouble(4),c.getDouble(5)));
        }
        JSONObject ticker = new JSONObject(get("https://fapi.binance.com/fapi/v1/ticker/24hr?symbol="+symbol));
        s.change24h = ticker.optDouble("priceChangePercent",0);
        JSONObject premium = new JSONObject(get("https://fapi.binance.com/fapi/v1/premiumIndex?symbol="+symbol));
        s.funding = premium.optDouble("lastFundingRate",0);
        JSONObject oi = new JSONObject(get("https://fapi.binance.com/fapi/v1/openInterest?symbol="+symbol));
        s.openInterest = oi.optDouble("openInterest",0);
        return s;
    }

    private static Snapshot fetchBybit(String symbol, String tf) throws Exception {
        Snapshot s = new Snapshot(); s.provider = "Bybit Linear";
        String interval = bybitInterval(tf);
        JSONObject k = new JSONObject(get("https://api.bybit.com/v5/market/kline?category=linear&symbol="+symbol+"&interval="+interval+"&limit="+LIMIT));
        JSONArray rows = k.getJSONObject("result").getJSONArray("list");
        List<Candle> reversed = new ArrayList<>();
        for (int i=0;i<rows.length();i++) {
            JSONArray c=rows.getJSONArray(i);
            reversed.add(new Candle(c.getDouble(1),c.getDouble(2),c.getDouble(3),c.getDouble(4),c.getDouble(5)));
        }
        Collections.reverse(reversed); s.candles.addAll(reversed);
        JSONObject t = new JSONObject(get("https://api.bybit.com/v5/market/tickers?category=linear&symbol="+symbol));
        JSONObject row=t.getJSONObject("result").getJSONArray("list").getJSONObject(0);
        s.change24h = row.optDouble("price24hPcnt",0) * 100.0;
        s.funding = row.optDouble("fundingRate",0);
        s.openInterest = row.optDouble("openInterest",0);
        return s;
    }

    private static String bybitInterval(String tf) {
        switch (tf) {
            case "1m": return "1";
            case "5m": return "5";
            case "15m": return "15";
            case "1h": return "60";
            case "4h": return "240";
            default: return "15";
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(9000); c.setReadTimeout(9000);
        c.setRequestMethod("GET"); c.setRequestProperty("User-Agent","CryptoFuturesSignal/1.0");
        int code=c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP "+code);
        try (BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()))) {
            StringBuilder sb=new StringBuilder(); String line;
            while((line=br.readLine())!=null) sb.append(line);
            return sb.toString();
        } finally { c.disconnect(); }
    }

    private static double ema(double[] a, int p) {
        double k=2.0/(p+1.0), v=a[0];
        for (int i=1;i<a.length;i++) v=a[i]*k+v*(1-k);
        return v;
    }

    private static double rsi(double[] a, int p) {
        if (a.length <= p) return 50;
        double gain=0, loss=0;
        for (int i=1;i<=p;i++) { double d=a[i]-a[i-1]; if(d>=0)gain+=d; else loss-=d; }
        gain/=p; loss/=p;
        for(int i=p+1;i<a.length;i++) {
            double d=a[i]-a[i-1];
            gain=(gain*(p-1)+Math.max(0,d))/p;
            loss=(loss*(p-1)+Math.max(0,-d))/p;
        }
        if(loss==0) return 100;
        double rs=gain/loss; return 100-(100/(1+rs));
    }

    private static double[] macd(double[] a) {
        double e12=seriesEmaLast(a,12), e26=seriesEmaLast(a,26);
        double[] line=new double[a.length];
        for(int i=0;i<a.length;i++) {
            double[] sub=new double[i+1]; System.arraycopy(a,0,sub,0,i+1);
            line[i]=seriesEmaLast(sub,12)-seriesEmaLast(sub,26);
        }
        double sig=seriesEmaLast(line,9); double m=e12-e26;
        return new double[]{m,sig,m-sig};
    }

    private static double seriesEmaLast(double[] a, int p) {
        if(a.length==0)return 0; double k=2.0/(p+1.0),v=a[0];
        for(int i=1;i<a.length;i++)v=a[i]*k+v*(1-k); return v;
    }

    private static double atr(double[] h,double[] l,double[] c,int p) {
        int start=Math.max(1,c.length-p); double sum=0; int count=0;
        for(int i=start;i<c.length;i++) {
            double tr=Math.max(h[i]-l[i],Math.max(Math.abs(h[i]-c[i-1]),Math.abs(l[i]-c[i-1])));
            sum+=tr; count++;
        }
        return count==0?0:sum/count;
    }

    private static double average(double[] a,int start,int endExclusive) {
        if(endExclusive<=start)return 0; double s=0; int n=0;
        for(int i=start;i<endExclusive;i++){s+=a[i];n++;} return n==0?0:s/n;
    }

    public static String price(double v) {
        if(v>=1000)return String.format(Locale.US,"%,.2f",v);
        if(v>=1)return String.format(Locale.US,"%.4f",v);
        return String.format(Locale.US,"%.6f",v);
    }
}

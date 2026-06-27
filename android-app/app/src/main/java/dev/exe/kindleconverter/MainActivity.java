package dev.exe.kindleconverter;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.format.Formatter;
import android.util.Base64;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity {
  private static final int PICK_BOOKS = 42;
  WebView webView; KindleHttpServer server; ExecutorService io = Executors.newSingleThreadExecutor();
  @SuppressLint("SetJavaScriptEnabled") @Override public void onCreate(Bundle b) { super.onCreate(b);
    webView = new WebView(this);
    FrameLayout root = new FrameLayout(this);
    root.addView(webView, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    WebSettings s = webView.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setAllowFileAccess(true); s.setAllowContentAccess(true);
    WebView.setWebContentsDebuggingEnabled(true); webView.setWebViewClient(new WebViewClient()); webView.setWebChromeClient(new WebChromeClient(){ public boolean onConsoleMessage(ConsoleMessage m){ android.util.Log.d("KindleLibrary", m.message()); return true; }});
    webView.addJavascriptInterface(new AndroidBridge(this), "AndroidKindle"); server = new KindleHttpServer(this); server.start();
    webView.loadDataWithBaseURL("https://kindle-library.local/", readAssetText("app/app.html"), "text/html", "UTF-8", null);
  }
  @Override protected void onDestroy(){ server.stop(); io.shutdownNow(); super.onDestroy(); }
  private String readAssetText(String name){ try(InputStream in=getAssets().open(name)){ return new String(readAll(in), java.nio.charset.StandardCharsets.UTF_8); } catch(Exception e){ return "<h1>Failed to load app</h1><pre>"+e+"</pre>"; } }
  @Override protected void onActivityResult(int request, int result, Intent data){ super.onActivityResult(request,result,data); if(request==PICK_BOOKS && result==RESULT_OK && data!=null){ if(data.getClipData()!=null){ for(int i=0;i<data.getClipData().getItemCount();i++) importUri(data.getClipData().getItemAt(i).getUri()); } else if(data.getData()!=null) importUri(data.getData()); }}
  void importUri(Uri uri){ io.execute(() -> { try { String name = displayName(uri); if(name==null) name="book-"+UUID.randomUUID(); String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.US) : "bin"; File dest = new File(getFilesDir(), "library/"+UUID.randomUUID()+"."+ext); dest.getParentFile().mkdirs(); try(InputStream in=getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(dest)){ copy(in,out); } runJs("window.NativeLibrary&&window.NativeLibrary.importedFile("+json(name)+","+json(ext)+","+json(dest.getAbsolutePath())+","+dest.length()+")"); } catch(Exception e){ runJs("alert("+json("Import failed: "+e.getMessage())+")"); }}); }
  String displayName(Uri uri){ try(Cursor c=getContentResolver().query(uri,null,null,null,null)){ if(c!=null){ int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0 && c.moveToFirst()) return c.getString(i); }} return null; }
  void runJs(String js){ runOnUiThread(() -> webView.evaluateJavascript(js,null)); }
  public class AndroidBridge { Context ctx; AndroidBridge(Context c){ctx=c;} @JavascriptInterface public void pickBooks(){ Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); startActivityForResult(i,PICK_BOOKS); }
    @JavascriptInterface public String readAssetBase64(String name) throws IOException { try(InputStream in=ctx.getAssets().open("app/"+name)){ return Base64.encodeToString(readAll(in), Base64.NO_WRAP); }}
    @JavascriptInterface public String readFileBase64(String path) throws IOException { return Base64.encodeToString(readAll(new FileInputStream(path)), Base64.NO_WRAP); }
    @JavascriptInterface public String writeBookBase64(String id, String name, String b64) throws IOException { File dir=new File(ctx.getFilesDir(),"converted/"+id); dir.mkdirs(); File f=new File(dir,name.replaceAll("[\\\\/:*?\"<>|]+","-")); try(FileOutputStream out=new FileOutputStream(f)){ out.write(Base64.decode(b64, Base64.DEFAULT)); } return f.getAbsolutePath(); }
    @JavascriptInterface public int serverPort(){ return server.port; } @JavascriptInterface public String serverUrls(){ return serverUrlsJson(ctx, server.port); } @JavascriptInterface public void setCatalogJson(String j){ server.catalogJson=j; } @JavascriptInterface public void log(String m){ android.util.Log.d("KindleLibrary",m); }}
  static byte[] readAll(InputStream in) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); copy(in,b); return b.toByteArray(); } static void copy(InputStream in, OutputStream out) throws IOException { byte[] buf=new byte[65536]; int n; while((n=in.read(buf))>=0) out.write(buf,0,n); }
  static String json(String s){ return "\""+s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""; }
  static String serverUrlsJson(Context ctx, int port){ StringBuilder sb=new StringBuilder("["); boolean first=true; for(String ip: discoverIpv4(ctx)){ if(!first) sb.append(','); first=false; sb.append(json("http://"+ip+":"+port+"/")); } return sb.append(']').toString(); }
  static List<String> discoverIpv4(Context ctx){ LinkedHashSet<String> out=new LinkedHashSet<>(); try{ WifiManager wm=(WifiManager)ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE); if(wm!=null && wm.getConnectionInfo()!=null && wm.getConnectionInfo().getIpAddress()!=0) out.add(Formatter.formatIpAddress(wm.getConnectionInfo().getIpAddress())); }catch(Throwable ignored){} try{ Enumeration<NetworkInterface> e=NetworkInterface.getNetworkInterfaces(); while(e.hasMoreElements()){ NetworkInterface nif=e.nextElement(); if(!nif.isUp()||nif.isLoopback()) continue; Enumeration<InetAddress> a=nif.getInetAddresses(); while(a.hasMoreElements()){ InetAddress addr=a.nextElement(); if(addr instanceof Inet4Address && !addr.isLoopbackAddress()) out.add(addr.getHostAddress()); }}}catch(Throwable ignored){} if(out.isEmpty()) out.add("127.0.0.1"); return new ArrayList<>(out); }
}

class KindleHttpServer { volatile String catalogJson="[]"; volatile int port=0; private ServerSocket socket; private final Context ctx; KindleHttpServer(Context c){ctx=c;} void start(){ new Thread(() -> { try{ socket=new ServerSocket(0); port=socket.getLocalPort(); while(!Thread.currentThread().isInterrupted()) handle(socket.accept()); }catch(Exception ignored){} }, "kindle-http").start(); } void stop(){ try{ socket.close(); }catch(Exception ignored){} }
  void handle(Socket sock){ try(Socket s=sock){ BufferedInputStream in=new BufferedInputStream(s.getInputStream()); String req=readHeader(in); String path="/"; String[] parts=req.split(" "); if(parts.length>1) path=parts[1]; OutputStream out=s.getOutputStream(); if(path.equals("/")) respond(out,"text/html; charset=utf-8", kindlePage(catalogJson).getBytes()); else if(path.startsWith("/catalog.json")) respond(out,"application/json",catalogJson.getBytes()); else if(path.startsWith("/download/")) download(out,path); else respond(out,"text/plain","Not found".getBytes(),"404 Not Found"); } catch(Exception ignored){} }
  String readHeader(InputStream in) throws IOException { ByteArrayOutputStream b=new ByteArrayOutputStream(); int c; String tail=""; while((c=in.read())>=0){ b.write(c); tail=(tail+(char)c); if(tail.length()>4) tail=tail.substring(tail.length()-4); if(tail.equals("\r\n\r\n")) break; } return b.toString(); }
  void download(OutputStream out,String path) throws IOException { String id=path.substring("/download/".length()).replaceAll("[/?].*$","").replaceAll("[^a-zA-Z0-9_-]",""); Matcher m=Pattern.compile("\\{[^}]*\\\"id\\\"\\s*:\\s*\\\""+Pattern.quote(id)+"\\\"[^}]*}").matcher(catalogJson); String file=null; if(m.find()){ Matcher f=Pattern.compile("\\\"azw3Path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(m.group()); if(f.find()) file=f.group(1).replace("\\/","/").replace("\\\\","\\"); } File actual=file==null?null:new File(file); if(actual!=null&&actual.exists()) respondFile(out,actual); else respond(out,"text/plain","Not found".getBytes(),"404 Not Found"); }
  void respondFile(OutputStream out, File f) throws IOException { out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: "+f.length()+"\r\nContent-Disposition: attachment; filename=\""+f.getName()+"\"\r\nConnection: close\r\n\r\n").getBytes()); try(FileInputStream in=new FileInputStream(f)){ MainActivity.copy(in,out); }}
  void respond(OutputStream out,String type,byte[] bytes) throws IOException { respond(out,type,bytes,"200 OK"); } void respond(OutputStream out,String type,byte[] bytes,String status) throws IOException { out.write(("HTTP/1.1 "+status+"\r\nContent-Type: "+type+"\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes()); out.write(bytes); }
  String kindlePage(String catalog){ return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'><title>Kindle Library</title><style>body{font-family:serif;background:#f8f3e8;color:#211b13;margin:18px;line-height:1.35}h1{font-size:26px}input{font-size:18px;width:96%;padding:8px;border:1px solid #8b7355;background:#fffaf0}.book{border-top:1px solid #cdbb9e;padding:12px 0}.book:first-of-type{border-top:3px solid #4b3826}a{font-size:20px;color:#111;display:inline-block;padding:8px 0}.meta{color:#665844;font-size:14px}.tag{font-size:13px;background:#eee0c9;padding:2px 5px;margin-right:4px}</style></head><body><h1>Kindle downloads</h1><p>Newest converted AZW3 books appear first. Tap a title to download.</p><input id='q' placeholder='Filter title, author, tag'><div id='books'></div><script>var books="+catalog+";function r(){var q=document.getElementById('q').value.toLowerCase(),root=document.getElementById('books');root.innerHTML='';books.filter(function(b){return (b.title+' '+(b.author||'')+' '+(b.tags||[]).join(' ')).toLowerCase().indexOf(q)>=0}).forEach(function(b,i){var d=document.createElement('div');d.className='book';d.innerHTML=(i==0?'<div class=meta>Latest Kindle-ready book</div>':'')+'<a href=\"/download/'+b.id+'\">'+b.title+'</a><div class=meta>'+(b.author||'')+' · '+Math.round((b.size||0)/1024)+' KB</div><div>'+((b.tags||[]).map(function(t){return '<span class=tag>'+t+'</span>'}).join(''))+'</div>';root.appendChild(d)})}document.getElementById('q').oninput=r;r()</script></body></html>"; }
}

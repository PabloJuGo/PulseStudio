package com.pulsestudio.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.*;
import android.widget.FrameLayout;
import android.widget.Toast;
import androidx.annotation.RequiresApi;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.webkit.JavaScriptReplyProxy;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int SAVE_DOCUMENT=701;
    private static final int SAVE_IMAGE_FOLDER=702;
    private static final int PICK_MEDIA=703;
    /** Public gallery sub-folder (Pictures/PulseStudio and Movies/PulseStudio) on Android 10+. */
    private static final String GALLERY_FOLDER="PulseStudio";
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private WebView web;
    private SecretStore secrets;
    private volatile boolean destroyed;
    // These transfer fields are touched only on the single IO executor.
    private ExportBuffer transfer;
    private String transferToken;
    private boolean transferToGallery;
    private boolean choosingDocument;
    private ImageBatchBuffer imageBatch;
    private String imageBatchToken;
    private boolean choosingImageFolder;
    // The pending SAF response belongs to this Activity and this original JS document.
    private Request pendingSave;
    private File pendingFile;
    private Request pendingImageSave;
    private File[] pendingImages;
    private String pendingImageFolder;
    private File exportDir;
    private int documentGeneration;
    // <input type="file"> callback for the photo (thumbnail) and the narrated video (editor).
    private ValueCallback<Uri[]> pendingChooser;

    private final class Request {
        final String id;
        final JavaScriptReplyProxy proxy;
        final int generation;
        Request(String id,JavaScriptReplyProxy proxy) { this.id=id; this.proxy=proxy; generation=documentGeneration; }
        void done(JSONObject result) { reply(result,null); }
        void fail(String error) { reply(null,error); }
        private void reply(JSONObject value,String error) {
            runOnUiThread(()->{
                if(destroyed || generation!=documentGeneration || web==null || !SecurityPolicy.internalDocument(web.getUrl())) return;
                try {
                    JSONObject response=new JSONObject().put("id",id).put("ok",error==null);
                    if(error==null) response.put("result",value==null?new JSONObject():value); else response.put("error",error);
                    if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) proxy.postMessage(response.toString());
                } catch(Exception ignored) { /* Never log messages that might contain a key. */ }
            });
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        secrets=new SecretStore(this);
        exportDir=new File(getCacheDir(),"exports");
        if(!exportDir.exists() && !exportDir.mkdirs()) { fatal("No se puede preparar el guardado de archivos."); return; }
        io.execute(()->{File[] files=exportDir.listFiles((dir,name)->name.startsWith("pulse-export-")&&name.endsWith(".tmp")); if(files!=null) for(File f:files) f.delete();});
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            fatal("Actualiza Android System WebView o Chrome para utilizar Pulse Studio con seguridad."); return;
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        FrameLayout root=new FrameLayout(this); root.setBackgroundColor(Color.rgb(8,18,32));
        web=new WebView(this);
        root.addView(web,new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        ViewCompat.setOnApplyWindowInsetsListener(root,(v,insets)->{
            Insets safe=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout()|WindowInsetsCompat.Type.ime());
            v.setPadding(safe.left,safe.top,safe.right,safe.bottom); return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
        // Disabled even for debug APK: no routine DevTools access to loaded API keys.
        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false); s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(false); s.setSupportMultipleWindows(true);
        // Local blob: videos are played and recorded programmatically by the in-app editor.
        s.setGeolocationEnabled(false); s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_NO_CACHE);
        if(Build.VERSION.SDK_INT>=26) {
            s.setSafeBrowsingEnabled(true);
            // Keep the renderer (MediaPipe + canvas + MediaRecorder) at foreground priority while visible.
            web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,true);
        }
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web,false);
        web.setSaveEnabled(false); // Never serialize DOM / password fields to saved instance state.
        WebViewAssetLoader assets=new WebViewAssetLoader.Builder()
            .addPathHandler("/assets/",new WebViewAssetLoader.AssetsPathHandler(this)).build();
        web.setWebViewClient(new WebViewClient(){
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) {
                String url=request.getUrl().toString();
                if(SecurityPolicy.internalDocument(url) && "GET".equals(request.getMethod())) {
                    WebResourceResponse response=assets.shouldInterceptRequest(request.getUrl());
                    return response!=null?response:blocked();
                }
                String assetMime=SecurityPolicy.internalAssetMime(url);
                if(assetMime!=null && "GET".equals(request.getMethod()) && !request.isForMainFrame()) {
                    // Local MediaPipe engine: exact allowlist and explicit MIME (ES module + WebAssembly streaming).
                    WebResourceResponse response=assets.shouldInterceptRequest(request.getUrl());
                    if(response==null) return blocked();
                    response.setMimeType(assetMime);
                    return response;
                }
                if(request.isForMainFrame() || !SecurityPolicy.networkAllowed(url)) return blocked();
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                if(SecurityPolicy.internalDocument(request.getUrl().toString()) && request.isForMainFrame()) return false;
                if(request.isForMainFrame() && request.hasGesture()) openExternal(request.getUrl().toString());
                return true;
            }
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon) {
                documentGeneration++;
                if(!SecurityPolicy.internalDocument(url)) { view.stopLoading(); fatal("Se ha bloqueado una navegación no permitida."); }
            }
            @Override public void onReceivedSslError(WebView view,SslErrorHandler handler,SslError error) { handler.cancel(); }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
                ((ViewGroup)view.getParent()).removeView(view); view.destroy(); web=null;
                fatal("La vista se ha cerrado. Abre de nuevo Pulse Studio. Las claves guardadas se conservan; regenera el pack."); return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onCreateWindow(WebView view,boolean isDialog,boolean isUserGesture,Message resultMsg) { return false; }
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                return showFileChooser(view,callback,params);
            }
        });
        web.setDownloadListener((url,userAgent,disposition,mime,length)->Toast.makeText(this,"Usa los botones de descarga de Pulse Studio.",Toast.LENGTH_LONG).show());
        if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(web,"PulseNative",Collections.singleton(SecurityPolicy.ORIGIN),
            (view,message,origin,mainFrame,reply)->{
                if(!mainFrame || !SecurityPolicy.trustedOrigin(origin.toString()) || !SecurityPolicy.internalDocument(view.getUrl())) return;
                String raw=message.getData();
                if(raw==null || raw.length()>70000) return;
                try {
                    JSONObject object=new JSONObject(raw);
                    String id=object.getString("id");
                    if(!id.matches("[0-9]{1,12}")) return;
                    Request req=new Request(id,reply);
                    io.execute(()->handle(req,object));
                } catch(Exception ignored) { /* Unparseable messages receive no privileged operation. */ }
            });
        web.loadUrl(SecurityPolicy.PAGE);
        if(state!=null) Toast.makeText(this,"Sesión recuperada. Si había una exportación pendiente, vuelve a generar y guardar el pack.",Toast.LENGTH_LONG).show();
    }

    private WebResourceResponse blocked() {
        return new WebResourceResponse("text/plain","UTF-8",403,"Blocked",Collections.emptyMap(),new ByteArrayInputStream("Blocked".getBytes(StandardCharsets.UTF_8)));
    }
    private void fatal(String text) { new AlertDialog.Builder(this).setTitle("Pulse Studio").setMessage(text).setPositiveButton("Cerrar",(d,w)->finish()).setCancelable(false).show(); }
    private void openExternal(String raw) {
        if(!SecurityPolicy.externalAllowed(raw)) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(raw)).addCategory(Intent.CATEGORY_BROWSABLE)); }
        catch(Exception ignored) { Toast.makeText(this,"No hay navegador disponible para abrir el enlace.",Toast.LENGTH_LONG).show(); }
    }
    private void handle(Request request,JSONObject object) {
        if(destroyed) return;
        String op=object.optString("op");
        try {
            switch(op) {
                case "keys.load": request.done(secrets.load()); break;
                case "keys.save":
                    secrets.save(object.getString("groq").trim(),object.getString("pexels").trim(),object.optString("hf","").trim()); request.done(null); break;
                case "keys.clear": secrets.clear(); request.done(null); break;
                case "screen.keepOn": {
                    // Exportación de vídeo larga: la pantalla no se apaga mientras dura.
                    boolean on=object.optBoolean("on",false);
                    runOnUiThread(()->{
                        if(destroyed) return;
                        if(on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    });
                    request.done(null); break;
                }
                case "external.open": {
                    String url=object.getString("url");
                    if(!SecurityPolicy.externalAllowed(url)) throw new IllegalArgumentException("Enlace externo bloqueado.");
                    runOnUiThread(()->openExternal(url)); request.done(null); break;
                }
                case "export.begin":
                    if(transfer!=null || imageBatch!=null || choosingDocument || choosingImageFolder) throw new IllegalStateException("Ya hay un archivo pendiente de guardar.");
                    transfer=new ExportBuffer(exportDir,object.getLong("size"),object.getString("name"),object.getString("mime"));
                    // "gallery": thumbnail and edited video go straight to Pictures/Movies (Android 10+).
                    transferToGallery="gallery".equals(object.optString("destination")) && (transfer.isImage() || transfer.isVideo());
                    transferToken=UUID.randomUUID().toString();
                    request.done(new JSONObject().put("token",transferToken)); break;
                case "export.chunk": {
                    checkToken(object); if(choosingDocument) throw new IllegalStateException("El archivo ya está preparado.");
                    String encoded=object.getString("data");
                    if(encoded.length()>65536 || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) throw new IllegalArgumentException("Fragmento inválido.");
                    transfer.append(object.getInt("sequence"),Base64.decode(encoded,Base64.NO_WRAP)); request.done(null); break;
                }
                case "export.finish": {
                    checkToken(object); if(choosingDocument) throw new IllegalStateException("El selector ya está abierto.");
                    File file=transfer.finish();
                    String name=transfer.name,mime=transfer.mime;
                    if(transferToGallery && Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
                        try {
                            saveToGallery(file,name,mime,transfer.isVideo());
                            request.done(new JSONObject().put("saved",true).put("gallery",true));
                        } catch(Exception error) { request.fail("No se pudo guardar en la galería. Comprueba el espacio disponible y vuelve a intentarlo."); }
                        finally { cleanup(); }
                        break;
                    }
                    choosingDocument=true;
                    runOnUiThread(()->{
                        if(destroyed) return;
                        pendingSave=request; pendingFile=file;
                        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime).putExtra(Intent.EXTRA_TITLE,name);
                        try { startActivityForResult(intent,SAVE_DOCUMENT); }
                        catch(Exception error) { pendingSave=null; pendingFile=null; io.execute(()->{cleanup(); request.fail("No hay un selector de archivos compatible.");}); }
                    }); break;
                }
                case "export.abort": checkToken(object); if(!choosingDocument) cleanup(); request.done(null); break;
                case "images.begin": {
                    if(transfer!=null || imageBatch!=null || choosingDocument || choosingImageFolder) throw new IllegalStateException("Ya hay un guardado pendiente.");
                    JSONArray files=object.getJSONArray("files");
                    if(files.length()!=ImageBatchBuffer.IMAGE_COUNT) throw new IllegalArgumentException("Se requieren seis imágenes.");
                    String[] names=new String[ImageBatchBuffer.IMAGE_COUNT]; long[] sizes=new long[ImageBatchBuffer.IMAGE_COUNT];
                    for(int i=0;i<files.length();i++) { JSONObject file=files.getJSONObject(i); names[i]=file.getString("name"); sizes[i]=file.getLong("size"); }
                    imageBatch=new ImageBatchBuffer(exportDir,object.getString("folderName"),names,sizes);
                    imageBatchToken=UUID.randomUUID().toString();
                    request.done(new JSONObject().put("token",imageBatchToken)); break;
                }
                case "images.chunk": {
                    checkImageToken(object); if(choosingImageFolder) throw new IllegalStateException("El lote ya está preparado.");
                    String encoded=object.getString("data");
                    if(encoded.length()>65536 || !encoded.matches("[A-Za-z0-9+/]*={0,2}")) throw new IllegalArgumentException("Fragmento inválido.");
                    imageBatch.append(object.getInt("fileIndex"),object.getInt("sequence"),Base64.decode(encoded,Base64.NO_WRAP)); request.done(null); break;
                }
                case "images.finish": {
                    checkImageToken(object); if(choosingImageFolder) throw new IllegalStateException("El selector ya está abierto.");
                    File[] files=imageBatch.finish(); choosingImageFolder=true;
                    runOnUiThread(()->{
                        if(destroyed) return;
                        pendingImageSave=request; pendingImages=files; pendingImageFolder=imageBatch.folderName;
                        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                        try { startActivityForResult(intent,SAVE_IMAGE_FOLDER); }
                        catch(Exception error) { pendingImageSave=null; pendingImages=null; pendingImageFolder=null; io.execute(()->{cleanupImages(); request.fail("No hay un selector de carpetas compatible.");}); }
                    }); break;
                }
                case "images.abort": checkImageToken(object); if(!choosingImageFolder) cleanupImages(); request.done(null); break;
                default: request.fail("Operación desconocida.");
            }
        } catch(Exception error) {
            // Provider or crypto exception details can carry sensitive information; don't echo them.
            request.fail(op.startsWith("keys.")?"No se pudo acceder al almacén cifrado. Puedes borrar las claves y volver a configurarlas.":"Operación rechazada. Comprueba la URL, el archivo y que no haya otro guardado pendiente.");
        }
    }
    private void checkToken(JSONObject object) throws Exception {
        if(transfer==null || !transferToken.equals(object.getString("token"))) throw new IllegalArgumentException("Transferencia desconocida.");
    }
    private void cleanup() {
        if(transfer!=null) try { transfer.close(); } catch(IOException ignored) {}
        transfer=null; transferToken=null; transferToGallery=false; choosingDocument=false;
    }
    private void checkImageToken(JSONObject object) throws Exception {
        if(imageBatch==null || !imageBatchToken.equals(object.getString("token"))) throw new IllegalArgumentException("Lote desconocido.");
    }
    private void cleanupImages() {
        if(imageBatch!=null) try { imageBatch.close(); } catch(IOException ignored) {}
        imageBatch=null; imageBatchToken=null; choosingImageFolder=false;
    }
    private boolean showFileChooser(WebView view,ValueCallback<Uri[]> callback,WebChromeClient.FileChooserParams params) {
        if(pendingChooser!=null) { pendingChooser.onReceiveValue(null); pendingChooser=null; }
        if(destroyed || view==null || !SecurityPolicy.internalDocument(view.getUrl())) { callback.onReceiveValue(null); return true; }
        // Only the two media inputs of the editor exist: one image or one video, never arbitrary documents.
        String type=null;
        String[] accept=params==null?null:params.getAcceptTypes();
        if(accept!=null) for(String raw:accept) for(String item:raw.split(",")) {
            String value=item.trim().toLowerCase(java.util.Locale.ROOT);
            if(value.startsWith("video/")) type="video/*";
            else if(value.startsWith("image/") && type==null) type="image/*";
        }
        if(type==null) { callback.onReceiveValue(null); return true; }
        Intent intent=new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType(type)
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE,false).putExtra(Intent.EXTRA_LOCAL_ONLY,true);
        pendingChooser=callback;
        try { startActivityForResult(intent,PICK_MEDIA); }
        catch(Exception error) {
            pendingChooser=null; callback.onReceiveValue(null);
            Toast.makeText(this,"No hay un selector de archivos compatible.",Toast.LENGTH_LONG).show();
        }
        return true;
    }
    @RequiresApi(Build.VERSION_CODES.Q)
    private void saveToGallery(File file,String name,String mime,boolean video) throws IOException {
        ContentResolver resolver=getContentResolver();
        ContentValues values=new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,name);
        values.put(MediaStore.MediaColumns.MIME_TYPE,mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH,(video?Environment.DIRECTORY_MOVIES:Environment.DIRECTORY_PICTURES)+"/"+GALLERY_FOLDER);
        values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri collection=video?MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            :MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri item=resolver.insert(collection,values);
        if(item==null) throw new IOException("MediaStore no disponible.");
        try {
            copyFile(file,item);
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING,0);
            resolver.update(item,values,null,null);
        } catch(IOException|RuntimeException error) {
            try { resolver.delete(item,null,null); } catch(RuntimeException ignored) {}
            throw error;
        }
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==PICK_MEDIA) {
            ValueCallback<Uri[]> callback=pendingChooser; pendingChooser=null;
            if(callback==null) return;
            Uri picked=resultCode==RESULT_OK && data!=null ? data.getData() : null;
            callback.onReceiveValue(picked!=null && "content".equals(picked.getScheme()) ? new Uri[]{picked} : null);
            return;
        }
        if(requestCode==SAVE_DOCUMENT && pendingSave!=null) {
            Request request=pendingSave; File file=pendingFile; pendingSave=null; pendingFile=null;
            Uri target=data==null?null:data.getData();
            io.execute(()->{
                try {
                    if(resultCode!=RESULT_OK || target==null) { request.done(new JSONObject().put("saved",false)); return; }
                    if(!"content".equals(target.getScheme())) throw new IOException("URI no admitida.");
                    copyFile(file,target);
                    request.done(new JSONObject().put("saved",true));
                } catch(Exception error) { request.fail("No se pudo completar el guardado. El destino puede contener un archivo parcial; elimínalo y vuelve a intentarlo."); }
                finally { cleanup(); }
            });
            return;
        }
        if(requestCode==SAVE_IMAGE_FOLDER && pendingImageSave!=null) {
            Request request=pendingImageSave; File[] files=pendingImages; String folderName=pendingImageFolder;
            pendingImageSave=null; pendingImages=null; pendingImageFolder=null;
            Uri tree=data==null?null:data.getData(); int flags=data==null?0:data.getFlags();
            io.execute(()->{
                try {
                    if(resultCode!=RESULT_OK || tree==null) { request.done(new JSONObject().put("saved",false).put("count",0)); return; }
                    if(!"content".equals(tree.getScheme())) throw new IOException("URI no admitida.");
                    try { getContentResolver().takePersistableUriPermission(tree,flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION)); } catch(Exception ignored) {}
                    Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
                    Uri folder;
                    try { folder=DocumentsContract.createDocument(getContentResolver(),parent,DocumentsContract.Document.MIME_TYPE_DIR,folderName); }
                    catch(Exception ignored) { folder=null; }
                    if(folder==null) folder=parent;
                    for(int i=0;i<files.length;i++) {
                        Uri target=DocumentsContract.createDocument(getContentResolver(),folder,"image/jpeg",imageBatch.nameAt(i));
                        if(target==null) throw new IOException("No se puede crear una imagen en la carpeta elegida.");
                        copyFile(files[i],target);
                    }
                    request.done(new JSONObject().put("saved",true).put("count",files.length));
                } catch(Exception error) { request.fail("No se pudieron guardar las seis imágenes en la carpeta elegida."); }
                finally { cleanupImages(); }
            });
        }
    }
    private void copyFile(File file,Uri target) throws IOException {
        try(InputStream in=new FileInputStream(file); OutputStream out=getContentResolver().openOutputStream(target,"wt")) {
            if(out==null) throw new IOException("No se puede abrir el destino.");
            byte[] buffer=new byte[32768]; int count;
            while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
            out.flush();
        }
    }
    @Override public void onBackPressed() {
        if(web==null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.PulseSettings && window.PulseSettings.closeIfOpen()",result->{
            if(!"true".equals(result) && !destroyed) MainActivity.super.onBackPressed();
        });
    }
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // UI hidden or memory pressure: let the page release the idle MediaPipe engine, canvases and blob URLs.
        if(web!=null && !destroyed && level>=TRIM_MEMORY_UI_HIDDEN)
            web.evaluateJavascript("window.PulseMedia && window.PulseMedia.trimMemory("+level+")",null);
    }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putBoolean("pulseSession",true); }
    @Override protected void onDestroy() {
        destroyed=true;
        if(pendingChooser!=null) { pendingChooser.onReceiveValue(null); pendingChooser=null; }
        if(web!=null) {
            if(WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.removeWebMessageListener(web,"PulseNative");
            ((ViewGroup)web.getParent()).removeView(web); web.stopLoading(); web.destroy(); web=null;
        }
        io.execute(()->{cleanup();cleanupImages();}); io.shutdown(); super.onDestroy();
    }
}

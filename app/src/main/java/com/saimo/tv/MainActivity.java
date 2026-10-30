package com.saimo.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * Saimo TV para Android TV / Mi TV Stick.
 * Abre o site dos canais em tela cheia, com suporte ao controle remoto,
 * vídeo em tela cheia, botão Voltar e tela de erro com nova tentativa.
 */
public class MainActivity extends Activity {

    // Endereço do seu site. Troque aqui se o link mudar.
    private static final String HOME_URL = "https://saimo-tv-f2w.pages.dev/";

    private WebView webView;
    private FrameLayout root;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private long lastBackPress = 0;
    private boolean showingError = false;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0a"));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0a0a0a"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setUserAgentString(s.getUserAgentString() + " SaimoTV-AndroidTV");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        }
        CookieManager.getInstance().setAcceptCookie(true);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false; // tudo abre dentro do app
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                showingError = false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                injectTvHelpers();
                webView.requestFocus();
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && request.isForMainFrame()) {
                    showErrorPage();
                }
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                if (failingUrl != null && failingUrl.startsWith(HOME_URL)) showErrorPage();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // Aparelhos antigos às vezes têm certificados desatualizados.
                // Segue só para o próprio site; outros endereços são bloqueados.
                if (error.getUrl() != null && error.getUrl().contains("pages.dev")) handler.proceed();
                else handler.cancel();
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                if (customView != null) { callback.onCustomViewHidden(); return; }
                customView = view;
                customViewCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
                webView.setVisibility(View.GONE);
                view.requestFocus();
                hideSystemUi();
            }

            @Override
            public void onHideCustomView() { exitFullscreen(); }

            @Override
            public Bitmap getDefaultVideoPoster() {
                // evita o ícone cinza de "play" antes do vídeo carregar
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            }
        });

        if (savedInstanceState != null) webView.restoreState(savedInstanceState);
        else webView.loadUrl(HOME_URL);
    }

    private String tvNavJs;

    /** Injeta a navegação por setas/OK (assets/tvnav.js) para os cards do site. */
    private void injectTvHelpers() {
        if (tvNavJs == null) {
            try {
                java.io.InputStream in = getAssets().open("tvnav.js");
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                tvNavJs = out.toString("UTF-8");
            } catch (java.io.IOException e) {
                tvNavJs = "";
            }
        }
        if (!tvNavJs.isEmpty()) webView.evaluateJavascript(tvNavJs, null);
    }

    private void showErrorPage() {
        if (showingError) return;
        showingError = true;
        String html = "<html><body style='background:#0a0a0a;color:#fff;font-family:sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<h1 style='color:#f5c518'>Sem conexão</h1>"
                + "<p>Não foi possível abrir o Saimo TV. Verifique a internet do Mi Stick.</p>"
                + "<button id='r' autofocus onclick=\"location.href='" + HOME_URL + "'\" "
                + "style='font-size:28px;padding:16px 40px;border:0;border-radius:12px;background:#f5c518;color:#000'>"
                + "Tentar de novo</button>"
                + "<script>document.getElementById('r').focus()</script></body></html>";
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        webView.setVisibility(View.VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
        webView.requestFocus();
        hideSystemUi();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int k = event.getKeyCode();
            if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
                handleBack();
                return true;
            }
            // Botões de mídia do controle -> play/pause no vídeo da página
            if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                    || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                webView.evaluateJavascript("(function(){var v=document.querySelector('video');"
                        + "if(v){v.paused?v.play():v.pause();}})()", null);
                return true;
            }
        }
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) return true;
        return super.dispatchKeyEvent(event);
    }

    private void handleBack() {
        if (customView != null) { exitFullscreen(); return; }
        if (webView.canGoBack() && !showingError) { webView.goBack(); return; }
        long now = System.currentTimeMillis();
        if (now - lastBackPress < 2000) { finish(); return; }
        lastBackPress = now;
        Toast.makeText(this, "Aperte Voltar de novo para sair", Toast.LENGTH_SHORT).show();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); webView.saveState(out); }
    @Override protected void onResume() { super.onResume(); webView.onResume(); hideSystemUi(); }
    @Override protected void onPause() { webView.onPause(); super.onPause(); }
    @Override protected void onDestroy() { if (webView != null) { webView.destroy(); } super.onDestroy(); }
}

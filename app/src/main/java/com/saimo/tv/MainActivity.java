package com.saimo.tv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
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
 * Abre o site em tela cheia e mostra um cursor (mouse) controlado pelas setas
 * do controle remoto. O botão OK dá um toque de verdade onde o cursor está,
 * igual ao dedo no celular. Encostar o cursor na borda rola a página.
 */
public class MainActivity extends Activity {

    // Endereço do seu site. Troque aqui se o link mudar.
    private static final String HOME_URL = "https://saimo-tv-f2w.pages.dev/";

    private WebView webView;
    private FrameLayout root;
    private CursorView cursor;
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    private long lastBackPress = 0;
    private boolean showingError = false;

    private float cx = -1, cy = -1;          // posição do cursor (pixels da tela)
    private float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideCursor = new Runnable() {
        @Override public void run() { cursor.setVisibility(View.INVISIBLE); }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0a0a0a"));
        webView = new WebView(this);
        webView.setBackgroundColor(Color.parseColor("#0a0a0a"));
        webView.setFocusable(true);
        webView.setFocusableInTouchMode(true);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        cursor = new CursorView(this, density);
        root.addView(cursor, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();

        root.post(new Runnable() {
            @Override public void run() {
                if (cx < 0) { cx = root.getWidth() / 2f; cy = root.getHeight() / 2f; }
                cursor.moveTo(cx, cy);
                showCursor();
            }
        });

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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
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
                cursor.setVisibility(View.INVISIBLE);
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

    // ---------------------------------------------------------------- cursor

    private void showCursor() {
        cursor.bringToFront();
        cursor.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideCursor);
        handler.postDelayed(hideCursor, 8000);
    }

    private static boolean isArrow(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN
                || k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT;
    }

    private static boolean isOk(int k) {
        return k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER
                || k == KeyEvent.KEYCODE_NUMPAD_ENTER || k == KeyEvent.KEYCODE_BUTTON_A;
    }

    /** Move o cursor; segurar a seta acelera. Na borda, rola a página. */
    private void moveCursor(int k, int repeat) {
        float step = (7 + Math.min(repeat, 14) * 3) * density;   // 7dp ... 49dp
        float w = root.getWidth(), h = root.getHeight(), edge = 6 * density;
        float nx = cx, ny = cy;
        if (k == KeyEvent.KEYCODE_DPAD_LEFT) nx -= step;
        if (k == KeyEvent.KEYCODE_DPAD_RIGHT) nx += step;
        if (k == KeyEvent.KEYCODE_DPAD_UP) ny -= step;
        if (k == KeyEvent.KEYCODE_DPAD_DOWN) ny += step;

        float scroll = Math.max(step * 4, 120 * density);
        if (ny < edge) { scrollAt(cx, h / 2f, 0, -scroll); ny = edge; }
        if (ny > h - edge) { scrollAt(cx, h / 2f, 0, scroll); ny = h - edge; }
        if (nx < edge) { scrollAt(edge * 3, cy, -scroll, 0); nx = edge; }
        if (nx > w - edge) { scrollAt(w - edge * 3, cy, scroll, 0); nx = w - edge; }

        cx = nx; cy = ny;
        cursor.moveTo(cx, cy);
        hoverAt(cx, cy);
    }

    /** Rola o elemento rolável que está naquele ponto (fileiras de cards, listas) ou a página. */
    private void scrollAt(float x, float y, float dx, float dy) {
        String js = "(function(x,y,dx,dy){var r=window.devicePixelRatio||1;x/=r;y/=r;dx/=r;dy/=r;"
                + "var el=document.elementFromPoint(x,y);"
                + "while(el&&el!==document.body&&el!==document.documentElement){"
                + "var s=getComputedStyle(el);"
                + "if(dy&&/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight+1){el.scrollBy(0,dy);return;}"
                + "if(dx&&/(auto|scroll)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth+1){el.scrollBy(dx,0);return;}"
                + "el=el.parentElement;}"
                + "window.scrollBy(dx,dy);})(" + x + "," + y + "," + dx + "," + dy + ")";
        webView.evaluateJavascript(js, null);
    }

    /** Passa o "mouse" por cima (efeitos de hover do site). */
    private void hoverAt(float x, float y) {
        long t = SystemClock.uptimeMillis();
        MotionEvent e = MotionEvent.obtain(t, t, MotionEvent.ACTION_HOVER_MOVE, x, y, 0);
        e.setSource(InputDevice.SOURCE_MOUSE);
        webView.dispatchGenericMotionEvent(e);
        e.recycle();
    }

    /** Toque de verdade (como o dedo) onde o cursor está. */
    private void tapAt(final float x, final float y) {
        final long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        d.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        webView.dispatchTouchEvent(d);
        d.recycle();
        handler.postDelayed(new Runnable() {
            @Override public void run() {
                MotionEvent u = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                        MotionEvent.ACTION_UP, x, y, 0);
                u.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                webView.dispatchTouchEvent(u);
                u.recycle();
            }
        }, 60);
        cursor.pulse();
    }

    // ------------------------------------------------------------- teclas

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_ESCAPE) {
            if (down && event.getRepeatCount() == 0) handleBack();
            return true;
        }

        // Botões de mídia do controle -> play/pause no vídeo da página
        if (k == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k == KeyEvent.KEYCODE_MEDIA_PLAY
                || k == KeyEvent.KEYCODE_MEDIA_PAUSE) {
            if (down) webView.evaluateJavascript("(function(){var v=document.querySelector('video');"
                    + "if(v){v.paused?v.play():v.pause();}})()", null);
            return true;
        }

        // Com o vídeo em tela cheia, as teclas vão para o player
        if (customView != null) return super.dispatchKeyEvent(event);

        if (isArrow(k)) {
            if (down) {
                boolean wasHidden = cursor.getVisibility() != View.VISIBLE;
                showCursor();
                if (!wasHidden || event.getRepeatCount() > 0) moveCursor(k, event.getRepeatCount());
            }
            return true;
        }
        if (isOk(k)) {
            if (down && event.getRepeatCount() == 0) {
                boolean wasHidden = cursor.getVisibility() != View.VISIBLE;
                showCursor();
                if (!wasHidden) tapAt(cx, cy);
            }
            return true;
        }
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

    // --------------------------------------------------------- tela de erro

    private void showErrorPage() {
        if (showingError) return;
        showingError = true;
        String html = "<html><body style='background:#0a0a0a;color:#fff;font-family:sans-serif;"
                + "display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;margin:0'>"
                + "<h1 style='color:#f5c518'>Sem conexão</h1>"
                + "<p>Não foi possível abrir o Saimo TV. Verifique a internet do Mi Stick.</p>"
                + "<button onclick=\"location.href='" + HOME_URL + "'\" "
                + "style='font-size:28px;padding:16px 40px;border:0;border-radius:12px;background:#f5c518;color:#000'>"
                + "Tentar de novo</button></body></html>";
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
        showCursor();
        hideSystemUi();
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
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (webView != null) { webView.destroy(); }
        super.onDestroy();
    }

    // ------------------------------------------------------ desenho do cursor

    /** Setinha de mouse desenhada por cima do site. Não bloqueia toques. */
    static class CursorView extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrow = new Path();
        private final float d;
        private float x, y;
        private long pulseUntil = 0;

        CursorView(Context c, float density) {
            super(c);
            d = density;
            setClickable(false);
            setFocusable(false);
            setWillNotDraw(false);
            fill.setColor(Color.WHITE);
            fill.setStyle(Paint.Style.FILL);
            stroke.setColor(Color.BLACK);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(1.6f * d);
            stroke.setStrokeJoin(Paint.Join.ROUND);
            ring.setColor(Color.parseColor("#F5C518"));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(3f * d);
            float s = 1.5f * d;  // tamanho da seta
            arrow.moveTo(0, 0);
            arrow.lineTo(0, 17 * s);
            arrow.lineTo(4.5f * s, 13 * s);
            arrow.lineTo(7.5f * s, 20 * s);
            arrow.lineTo(10.5f * s, 18.8f * s);
            arrow.lineTo(7.5f * s, 12 * s);
            arrow.lineTo(13 * s, 12 * s);
            arrow.close();
        }

        void moveTo(float nx, float ny) { x = nx; y = ny; invalidate(); }

        void pulse() { pulseUntil = SystemClock.uptimeMillis() + 220; invalidate(); }

        @Override
        public boolean onTouchEvent(MotionEvent event) { return false; }

        @Override
        protected void onDraw(Canvas canvas) {
            long now = SystemClock.uptimeMillis();
            if (now < pulseUntil) {
                float p = 1f - (pulseUntil - now) / 220f;
                canvas.drawCircle(x, y, (8 + 18 * p) * d, ring);
                postInvalidateOnAnimation();
            }
            canvas.save();
            canvas.translate(x, y);
            canvas.drawPath(arrow, fill);
            canvas.drawPath(arrow, stroke);
            canvas.restore();
        }
    }
}

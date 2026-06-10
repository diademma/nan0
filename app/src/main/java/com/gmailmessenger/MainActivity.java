package com.gmailmessenger;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Drop-in replacement for app/src/main/java/com/gmailmessenger/MainActivity.java
 *
 * Что исправлено:
 * - больше нет мгновенного автоперехода в чат по любому mail.google.com
 * - вход считается завершённым только когда появились auth-cookies
 * - проверка входа идёт тихо в фоне, пока ты логинишься
 */
public class MainActivity extends AppCompatActivity {

    private static final String BASE = "https://mail.google.com/mail/u/0/h/";
    private static final String LOGIN_URL = "https://mail.google.com/";
    private static final String DRAFTS_URL = BASE + "?v=dr";

    // Prefix convention: GM1|<message>
    private static final String PREFIX = "GM1|";

    private static final long POLL_MS = 45_000L;
    private static final long AUTH_CHECK_DELAY_MS = 1_500L;
    private static final long AUTH_RETRY_MS = 2_000L;

    // Cookie markers that usually appear only after Google auth is established.
    private static final String[] AUTH_COOKIE_MARKERS = new String[] {
            "__Secure-3PSID",
            "__Secure-1PSID",
            "SID=",
            "HSID=",
            "SSID=",
            "LSID=",
            "APISID=",
            "SAPISID="
    };

    private WebView loginWebView;
    private WebView workerWebView;
    private RecyclerView rvMessages;
    private EditText etMessage;
    private ImageButton btnSend;
    private TextView tvStatus;
    private FrameLayout loginOverlay;

    private final List<Message> messages = new ArrayList<>();
    private ChatAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> seenThreadIds = new HashSet<>();

    private boolean loggedIn = false;
    private boolean authCheckScheduled = false;

    private enum Op {
        IDLE,
        NAVIGATING_COMPOSE,
        SAVING_DRAFT,
        NAVIGATING_DRAFTS
    }

    private Op op = Op.IDLE;
    private String pendingMessage = null;

    private final Runnable authCheckRunnable = new Runnable() {
        @Override
        public void run() {
            authCheckScheduled = false;

            if (loggedIn) {
                return;
            }

            if (looksAuthenticated()) {
                unlockSession();
                return;
            }

            scheduleAuthCheck(AUTH_RETRY_MS);
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (loggedIn && op == Op.IDLE) {
                pollDrafts();
            }
            schedulePoll();
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        CookieManager.getInstance().setAcceptCookie(true);

        loginWebView = findViewById(R.id.loginWebView);
        workerWebView = findViewById(R.id.webView);
        rvMessages = findViewById(R.id.rvMessages);
        etMessage = findViewById(R.id.etMessage);
        btnSend = findViewById(R.id.btnSend);
        tvStatus = findViewById(R.id.tvStatus);
        loginOverlay = findViewById(R.id.loginOverlay);

        setupRecycler();
        setupLoginWebView();
        setupWorkerWebView();

        btnSend.setOnClickListener(v -> onSendClicked());

        showLoginScreen();
        loginWebView.loadUrl(LOGIN_URL);

        scheduleAuthCheck(AUTH_CHECK_DELAY_MS);
    }

    private void setupRecycler() {
        adapter = new ChatAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupLoginWebView() {
        WebSettings s = loginWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Tablet) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // Никакого автоперехода отсюда больше нет.
                // Просто тихо проверяем, появилась ли уже авторизованная сессия.
                scheduleAuthCheck(800L);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void setupWorkerWebView() {
        WebSettings s = workerWebView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUserAgentString(
                "Mozilla/5.0 (Linux; Android 13; Tablet) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        );

        workerWebView.addJavascriptInterface(new GmailBridge(), "AndroidBridge");

        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                handleWorkerPageFinished(url);
            }
        });
    }

    private void handleWorkerPageFinished(String url) {
        if (op == Op.NAVIGATING_COMPOSE && url != null && url.contains("v=cm")) {
            op = Op.SAVING_DRAFT;
            injectSaveDraft();
            return;
        }

        if (op == Op.SAVING_DRAFT) {
            op = Op.IDLE;
            if (pendingMessage != null) {
                final String sent = pendingMessage;
                pendingMessage = null;
                runOnUiThread(() -> pushMessage(sent, true, null));
            }
            return;
        }

        if (op == Op.NAVIGATING_DRAFTS && url != null && url.contains("v=dr")) {
            injectReadDrafts();
            op = Op.IDLE;
        }
    }

    private void onSendClicked() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty() || !loggedIn || op != Op.IDLE) {
            return;
        }

        etMessage.setText("");
        pendingMessage = text;
        createDraftToSelf(text);
        pushMessage(text, true, null);
        setStatus("черновик…", 0xFFFFB300);
    }

    private void createDraftToSelf(String text) {
        String subject = PREFIX + System.currentTimeMillis();
        String body = text.replace("\n", " ");

        String composeUrl = BASE
                + "?v=cm&to=me"
                + "&su=" + Uri.encode(subject)
                + "&body=" + Uri.encode(body);

        op = Op.NAVIGATING_COMPOSE;
        workerWebView.post(() -> workerWebView.loadUrl(composeUrl));

        handler.postDelayed(() -> {
            String js = "javascript:(function(){"
                    + "var b=document.querySelector('input[name=\"ms\"]');"
                    + "if(b){b.click();}"
                    + "})();";
            workerWebView.loadUrl(js);
        }, 1200);
    }

    private void pollDrafts() {
        if (!loggedIn || op != Op.IDLE) {
            return;
        }

        op = Op.NAVIGATING_DRAFTS;
        workerWebView.loadUrl(DRAFTS_URL);

        handler.postDelayed(() -> {
            String js = "javascript:(function(){"
                    + "var res=[];"
                    + "var links=document.querySelectorAll('a');"
                    + "for(var i=0;i<links.length;i++){"
                    + "  var a=links[i];"
                    + "  var t=(a.textContent||'').trim();"
                    + "  if(t.indexOf('" + PREFIX + "')!==0) continue;"
                    + "  var m=a.href.match(/th=([^&]+)/);"
                    + "  if(m) res.push({th:m[1], subj:t});"
                    + "}"
                    + "AndroidBridge.onDraftList(JSON.stringify(res));"
                    + "})();";
            workerWebView.evaluateJavascript(js, null);
        }, 1200);
    }

    private void injectSaveDraft() {
        String js = "javascript:(function(){"
                + "var b=document.querySelector('input[name=\"ms\"]');"
                + "if(b){b.click();}"
                + "})();";
        workerWebView.post(() -> workerWebView.loadUrl(js));
    }

    private void injectReadDrafts() {
        String js = "javascript:(function(){"
                + "var res=[];"
                + "var links=document.querySelectorAll('a');"
                + "for(var i=0;i<links.length;i++){"
                + "  var a=links[i];"
                + "  var t=(a.textContent||'').trim();"
                + "  if(t.indexOf('" + PREFIX + "')!==0) continue;"
                + "  var m=a.href.match(/th=([^&]+)/);"
                + "  if(m) res.push({th:m[1], subj:t});"
                + "}"
                + "AndroidBridge.onDraftList(JSON.stringify(res));"
                + "})();";
        workerWebView.post(() -> workerWebView.evaluateJavascript(js, null));
    }

    private boolean looksAuthenticated() {
        String cookies = safeCookie(LOGIN_URL) + " " + safeCookie("https://accounts.google.com");

        if (cookies.trim().isEmpty()) {
            return false;
        }

        for (String marker : AUTH_COOKIE_MARKERS) {
            if (cookies.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private String safeCookie(String url) {
        String c = CookieManager.getInstance().getCookie(url);
        return c == null ? "" : c;
    }

    private void unlockSession() {
        if (loggedIn) {
            return;
        }

        loggedIn = true;
        CookieManager.getInstance().flush();

        handler.removeCallbacks(authCheckRunnable);
        authCheckScheduled = false;

        showChatScreen();
        workerWebView.loadUrl(BASE);
        schedulePoll();
    }

    private void scheduleAuthCheck(long delayMs) {
        if (loggedIn) {
            return;
        }
        if (authCheckScheduled) {
            return;
        }
        authCheckScheduled = true;
        handler.postDelayed(authCheckRunnable, delayMs);
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    private void pushMessage(String text, boolean sent, String threadId) {
        messages.add(new Message(text, sent, threadId));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
        setStatus("онлайн", 0xFF4CAF50);
    }

    private void setStatus(String label, int color) {
        tvStatus.setText(label);
        tvStatus.setTextColor(color);
    }

    private void showLoginScreen() {
        loginOverlay.setVisibility(View.VISIBLE);
        rvMessages.setVisibility(View.GONE);
        etMessage.setEnabled(false);
        btnSend.setEnabled(false);
        setStatus("вход…", 0xFFFFB300);
    }

    private void showChatScreen() {
        runOnUiThread(() -> {
            loginOverlay.setVisibility(View.GONE);
            rvMessages.setVisibility(View.VISIBLE);
            etMessage.setEnabled(true);
            btnSend.setEnabled(true);
            setStatus("онлайн", 0xFF4CAF50);
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(authCheckRunnable);
        handler.removeCallbacks(pollRunnable);
    }

    private class GmailBridge {
        @JavascriptInterface
        public void onDraftList(String json) {
            try {
                JSONArray arr = new JSONArray(json);

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String th = o.optString("th", "");
                    String subj = o.optString("subj", "");

                    if (th.isEmpty() || seenThreadIds.contains(th)) {
                        continue;
                    }

                    seenThreadIds.add(th);

                    String text = subj.length() > PREFIX.length()
                            ? subj.substring(PREFIX.length())
                            : subj;

                    runOnUiThread(() -> {
                        pushMessage(text, false, th);
                        setStatus("онлайн", 0xFF4CAF50);
                    });
                }
            } catch (Exception ignored) {
                // keep quiet
            } finally {
                runOnUiThread(() -> {
                    if (loggedIn && op == Op.IDLE) {
                        schedulePoll();
                    }
                });
            }
        }
    }
}

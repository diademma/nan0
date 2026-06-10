package com.gmailmessenger;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceError;
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
 * Что делает эта версия:
 * - пишет живые логи прямо в чат-ленту на главном экране
 * - ждёт реальную загрузку страницы, а не стреляет одним таймером вслепую
 * - пытается несколько раз нажать Save Draft и показывает, где именно сломалось
 * - отдельно проверяет, появился ли черновик после сохранения
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GmailMessenger";

    private static final String BASE = "https://mail.google.com/mail/u/0/h/";
    private static final String LOGIN_URL = "https://mail.google.com/";
    private static final String DRAFTS_URL = BASE + "?v=dr";

    // Subject format: GM1|<timestamp>|<message>
    private static final String PREFIX = "GM1|";

    private static final long POLL_MS = 45_000L;
    private static final long AUTH_CHECK_DELAY_MS = 1_500L;
    private static final long AUTH_RETRY_MS = 2_000L;
    private static final long SAVE_RETRY_DELAY_MS = 900L;
    private static final long VERIFY_RETRY_DELAY_MS = 1_000L;
    private static final long COMPOSE_READY_DELAY_MS = 700L;
    private static final int SAVE_MAX_TRIES = 10;
    private static final int VERIFY_MAX_TRIES = 8;

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
    private boolean busy = false;
    private boolean pollScheduled = false;

    private String pendingMessageText = null;
    private String pendingSubject = null;
    private int saveTriesLeft = 0;
    private int verifyTriesLeft = 0;

    private enum Op {
        IDLE,
        WAITING_COMPOSE,
        WAITING_SAVE,
        VERIFYING_SAVE,
        READING_DRAFTS
    }

    private Op op = Op.IDLE;

    private final Runnable authCheckRunnable = new Runnable() {
        @Override
        public void run() {
            authCheckScheduled = false;

            if (loggedIn) return;

            boolean auth = looksAuthenticated();
            logStep(auth
                    ? "AUTH: cookies найдены, вход подтверждён"
                    : "AUTH: cookies ещё нет, жду логин");

            if (auth) {
                unlockSession();
            } else {
                scheduleAuthCheck(AUTH_RETRY_MS);
            }
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            pollScheduled = false;
            if (loggedIn && op == Op.IDLE && !busy) {
                pollDrafts();
            }
            schedulePoll();
        }
    };

    private final Runnable saveRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!busy || op != Op.WAITING_SAVE) return;
            attemptSaveDraft();
        }
    };

    private final Runnable verifyRetryRunnable = new Runnable() {
        @Override
        public void run() {
            if (!busy || op != Op.VERIFYING_SAVE) return;
            verifySavedDraft();
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
        logStep("APP: старт, открываю Gmail для входа");
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

        CookieManager.getInstance().setAcceptThirdPartyCookies(loginWebView, true);

        loginWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                logStep("LOGIN: старт страницы: " + shortUrl(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                logStep("LOGIN: страница загружена: " + shortUrl(url));
                scheduleAuthCheck(700L);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                logStep("LOGIN_ERR: " + safeUrl(request) + " / " + errorDescription(error));
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

        CookieManager.getInstance().setAcceptThirdPartyCookies(workerWebView, true);

        workerWebView.addJavascriptInterface(new GmailBridge(), "AndroidBridge");

        workerWebView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                logStep("WORKER: старт страницы: " + shortUrl(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                logStep("WORKER: страница загружена: " + shortUrl(url));
                handleWorkerPageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                logStep("WORKER_ERR: " + safeUrl(request) + " / " + errorDescription(error));
            }
        });
    }

    private void handleWorkerPageFinished(String url) {
        if (op == Op.WAITING_COMPOSE && isComposeUrl(url)) {
            logStep("SEND: compose открыт, пытаюсь сохранить черновик");
            op = Op.WAITING_SAVE;
            saveTriesLeft = SAVE_MAX_TRIES;
            handler.postDelayed(this::attemptSaveDraft, COMPOSE_READY_DELAY_MS);
            return;
        }

        if (op == Op.VERIFYING_SAVE && isDraftsUrl(url)) {
            logStep("VERIFY: список черновиков открыт, проверяю наличие сообщения");
            injectVerifyDraftList();
            return;
        }

        if (op == Op.READING_DRAFTS && isDraftsUrl(url)) {
            logStep("READ: список черновиков открыт, читаю входящие");
            injectReadDrafts();
        }
    }

    private void onSendClicked() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            logStep("SEND: пустое сообщение, пропускаю");
            return;
        }
        if (!loggedIn) {
            logStep("SEND_ERR: вход ещё не завершён");
            return;
        }
        if (busy || op != Op.IDLE) {
            logStep("SEND_ERR: уже занят другой операцией");
            return;
        }

        etMessage.setText("");
        createDraftToSelf(text);
    }

    private void createDraftToSelf(String text) {
        busy = true;
        op = Op.WAITING_COMPOSE;
        saveTriesLeft = SAVE_MAX_TRIES;
        verifyTriesLeft = VERIFY_MAX_TRIES;

        pendingMessageText = text;
        pendingSubject = PREFIX + System.currentTimeMillis() + "|" + text;

        logStep("SEND: начинаю черновик: " + pendingSubject);
        setStatus("SEND: открываю compose", 0xFFFFB300);

        String composeUrl = BASE
                + "?v=cm&to=me"
                + "&su=" + Uri.encode(pendingSubject)
                + "&body=" + Uri.encode(text);

        workerWebView.post(() -> workerWebView.loadUrl(composeUrl));
    }

    private void attemptSaveDraft() {
        if (!busy || op != Op.WAITING_SAVE) return;

        if (saveTriesLeft <= 0) {
            failOperation("SAVE_ERR: кнопку Save Draft так и не нашёл");
            return;
        }

        saveTriesLeft--;
        logStep("SAVE: пробую нажать кнопку, попытка " + (SAVE_MAX_TRIES - saveTriesLeft) + "/" + SAVE_MAX_TRIES);

        String js = "javascript:(function(){"
                + "var selectors=["
                + "'input[name=\\\"ms\\\"]',"
                + "'button[name=\\\"ms\\\"]',"
                + "'input[value*=\\\"Save\\\"]',"
                + "'input[value*=\\\"Сохран\\\"]',"
                + "'[aria-label*=\\\"Save\\\"]',"
                + "'[aria-label*=\\\"Сохран\\\"]'"
                + "];"
                + "for(var i=0;i<selectors.length;i++){"
                + "  try{var el=document.querySelector(selectors[i]); if(el){el.click(); return 'FOUND:'+selectors[i];}}catch(e){}"
                + "}"
                + "return 'NOT_FOUND';"
                + "})();";

        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String result = stripJsValue(value);
                logStep("SAVE: JS result = " + result);

                if (result.startsWith("FOUND:")) {
                    logStep("SAVE: кнопка найдена, проверяю, появился ли черновик");
                    op = Op.VERIFYING_SAVE;
                    handler.postDelayed(MainActivity.this::verifySavedDraft, VERIFY_RETRY_DELAY_MS);
                } else {
                    handler.postDelayed(MainActivity.this::attemptSaveDraft, SAVE_RETRY_DELAY_MS);
                }
            }
        });
    }

    private void verifySavedDraft() {
        if (!busy || op != Op.VERIFYING_SAVE) return;

        if (verifyTriesLeft <= 0) {
            failOperation("VERIFY_ERR: черновик не появился после сохранения");
            return;
        }

        verifyTriesLeft--;
        logStep("VERIFY: открываю список черновиков, попытка " + (VERIFY_MAX_TRIES - verifyTriesLeft) + "/" + VERIFY_MAX_TRIES);

        workerWebView.loadUrl(DRAFTS_URL);
    }

    private void pollDrafts() {
        if (!loggedIn) return;

        busy = true;
        op = Op.READING_DRAFTS;
        workerWebView.loadUrl(DRAFTS_URL);
    }

    private void injectVerifyDraftList() {
        if (pendingSubject == null) {
            failOperation("VERIFY_ERR: нет subject для проверки");
            return;
        }

        String js = buildDraftSearchJs(pendingSubject, true);
        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String result = stripJsValue(value);
                logStep("VERIFY: JS result = " + result);

                try {
                    JSONArray arr = new JSONArray(result);
                    if (arr.length() > 0) {
                        logStep("VERIFY_OK: черновик найден, всё записалось");
                        finishOperationSuccess();
                    } else {
                        if (verifyTriesLeft > 0) {
                            handler.postDelayed(MainActivity.this::verifySavedDraft, VERIFY_RETRY_DELAY_MS);
                        } else {
                            failOperation("VERIFY_ERR: черновик так и не появился");
                        }
                    }
                } catch (Exception e) {
                    failOperation("VERIFY_ERR: не смог прочитать ответ проверки / " + e.getMessage());
                }
            }
        });
    }

    private void injectReadDrafts() {
        String js = buildDraftSearchJs(PREFIX, false);
        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                try {
                    String result = stripJsValue(value);
                    JSONArray arr = new JSONArray(result);
                    if (arr.length() == 0) {
                        logStep("READ: подходящих черновиков нет");
                    }
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String th = o.optString("th", "");
                        String subj = o.optString("subj", "");

                        if (th.isEmpty() || seenThreadIds.contains(th)) continue;
                        seenThreadIds.add(th);

                        String text = subj.startsWith(PREFIX) ? subj.substring(PREFIX.length()) : subj;
                        pushMessage(text, false, th);
                    }
                } catch (Exception e) {
                    logStep("READ_ERR: " + e.getMessage());
                } finally {
                    finishOperationSuccess();
                }
            }
        });
    }

    private String buildDraftSearchJs(String target, boolean exactPrefixMatchOnly) {
        String escapedTarget = JSONObject.quote(target);
        String escapedPrefix = JSONObject.quote(PREFIX);
        return "javascript:(function(){"
                + "var target=" + escapedTarget + ";"
                + "var prefix=" + escapedPrefix + ";"
                + "var res=[];"
                + "var links=document.querySelectorAll('a');"
                + "for(var i=0;i<links.length;i++){"
                + "  var a=links[i];"
                + "  var t=(a.textContent||'').trim();"
                + (exactPrefixMatchOnly
                    ? "  if(t.indexOf(target)!==0) continue;"
                    : "  if(t.indexOf(prefix)!==0) continue;")
                + "  var m=a.href && a.href.match(/th=([^&]+)/);"
                + "  if(m) res.push({th:m[1],subj:t});"
                + "}"
                + "return JSON.stringify(res);"
                + "})();";
    }

    private boolean looksAuthenticated() {
        String cookies = safeCookie(LOGIN_URL) + " " + safeCookie("https://accounts.google.com");
        if (cookies.trim().isEmpty()) return false;

        for (String marker : AUTH_COOKIE_MARKERS) {
            if (cookies.contains(marker)) return true;
        }
        return false;
    }

    private String safeCookie(String url) {
        String c = CookieManager.getInstance().getCookie(url);
        return c == null ? "" : c;
    }

    private void unlockSession() {
        if (loggedIn) return;

        loggedIn = true;
        CookieManager.getInstance().flush();
        handler.removeCallbacks(authCheckRunnable);
        authCheckScheduled = false;

        logStep("AUTH: вход подтверждён, чат открыт");
        showChatScreen();

        workerWebView.loadUrl(BASE);
        schedulePoll();
    }

    private void scheduleAuthCheck(long delayMs) {
        if (loggedIn || authCheckScheduled) return;
        authCheckScheduled = true;
        handler.postDelayed(authCheckRunnable, delayMs);
    }

    private void schedulePoll() {
        if (pollScheduled) return;
        pollScheduled = true;
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    private void pushMessage(String text, boolean sent, String threadId) {
        messages.add(new Message(text, sent, threadId));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void logStep(String text) {
        Log.d(TAG, text);
        runOnUiThread(() -> {
            messages.add(new Message(text, false, null));
            adapter.notifyItemInserted(messages.size() - 1);
            rvMessages.scrollToPosition(messages.size() - 1);
            setStatus(text, 0xFFFFB300);
        });
    }

    private void finishOperationSuccess() {
        busy = false;
        op = Op.IDLE;
        pendingMessageText = null;
        pendingSubject = null;
        saveTriesLeft = 0;
        verifyTriesLeft = 0;
        setStatus("онлайн", 0xFF4CAF50);
        schedulePoll();
    }

    private void failOperation(String reason) {
        logStep(reason);
        busy = false;
        op = Op.IDLE;
        pendingMessageText = null;
        pendingSubject = null;
        saveTriesLeft = 0;
        verifyTriesLeft = 0;
        setStatus("ошибка", 0xFFE53935);
        schedulePoll();
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
        loginOverlay.setVisibility(View.GONE);
        rvMessages.setVisibility(View.VISIBLE);
        etMessage.setEnabled(true);
        btnSend.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(authCheckRunnable);
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(saveRetryRunnable);
        handler.removeCallbacks(verifyRetryRunnable);
    }

    private void attemptSaveDraftRetryLater() {
        handler.postDelayed(saveRetryRunnable, SAVE_RETRY_DELAY_MS);
    }

    private void verifyDraftRetryLater() {
        handler.postDelayed(verifyRetryRunnable, VERIFY_RETRY_DELAY_MS);
    }

    private String stripJsValue(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.startsWith("\"") && v.endsWith("\"")) {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\\"", "\"");
    }

    private boolean isComposeUrl(String url) {
        return url != null && (url.contains("v=cm") || url.contains("#cm") || url.contains("compose"));
    }

    private boolean isDraftsUrl(String url) {
        return url != null && (url.contains("v=dr") || url.contains("draft"));
    }

    private String shortUrl(String url) {
        if (url == null) return "(null)";
        if (url.length() <= 140) return url;
        return url.substring(0, 140) + "…";
    }

    private String safeUrl(WebResourceRequest request) {
        try {
            return request == null || request.getUrl() == null ? "(unknown)" : request.getUrl().toString();
        } catch (Exception e) {
            return "(unknown)";
        }
    }

    private String errorDescription(WebResourceError error) {
        try {
            return error == null ? "(no error)" : error.getDescription().toString();
        } catch (Exception e) {
            return "(error desc unavailable)";
        }
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

                    if (th.isEmpty() || seenThreadIds.contains(th)) continue;
                    seenThreadIds.add(th);

                    String text = subj.startsWith(PREFIX) ? subj.substring(PREFIX.length()) : subj;
                    pushMessage(text, false, th);
                }
            } catch (Exception e) {
                logStep("READ_ERR: " + e.getMessage());
            } finally {
                if (op == Op.READING_DRAFTS) {
                    finishOperationSuccess();
                }
            }
        }

        @JavascriptInterface
        public void onVerifyList(String json) {
            try {
                JSONArray arr = new JSONArray(json);
                if (arr.length() > 0) {
                    logStep("VERIFY_OK: черновик найден");
                    finishOperationSuccess();
                } else {
                    logStep("VERIFY: черновик ещё не виден");
                    if (verifyTriesLeft > 0) {
                        verifyDraftRetryLater();
                    } else {
                        failOperation("VERIFY_ERR: черновик так и не появился");
                    }
                }
            } catch (Exception e) {
                failOperation("VERIFY_ERR: " + e.getMessage());
            }
        }
    }
}

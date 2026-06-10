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
 * - показывает живые диагностические логи на экране
 * - не отправляет сообщение "вслепую"
 * - сначала пытается создать черновик, потом проверяет, что он реально появился
 * - если что-то ломается, пишет, на каком шаге именно
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GmailMessenger";

    private static final String BASE = "https://mail.google.com/mail/u/0/h/";
    private static final String LOGIN_URL = "https://mail.google.com/";
    private static final String DRAFTS_URL = BASE + "?v=dr";

    // Prefix convention: GM1|<timestamp>|<message>
    private static final String PREFIX = "GM1|";

    private static final long POLL_MS = 45_000L;
    private static final long AUTH_CHECK_DELAY_MS = 1_500L;
    private static final long AUTH_RETRY_MS = 2_000L;
    private static final long COMPOSE_READY_DELAY_MS = 900L;
    private static final long VERIFY_RETRY_DELAY_MS = 900L;
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
    private boolean suppressNextDraftList = false;

    private String pendingMessageText = null;
    private String pendingSubject = null;
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
                    ? "AUTH: cookies найдены, можно входить в чат"
                    : "AUTH: cookies ещё нет, жду логин", auth ? Log.INFO : Log.WARN, false);

            if (auth) {
                unlockSession();
                return;
            }

            scheduleAuthCheck(AUTH_RETRY_MS);
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (loggedIn && op == Op.IDLE && !busy) {
                readDrafts();
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
        logStep("APP: старт, открываю Gmail", Log.INFO, false);
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
                logStep("LOGIN: страница загружена: " + shortUrl(url), Log.INFO, false);
                scheduleAuthCheck(800L);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                logStep("LOGIN ERROR: " + safeError(error), Log.ERROR, true);
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
                logStep("WORKER: страница загружена: " + shortUrl(url), Log.INFO, false);
                handleWorkerPageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                logStep("WORKER ERROR: " + safeError(error), Log.ERROR, true);
                failCurrentDraft("ошибка WebView: " + safeError(error));
            }
        });
    }

    private void handleWorkerPageFinished(String url) {
        if (url == null) return;

        if (op == Op.WAITING_COMPOSE && url.contains("v=cm")) {
            logStep("DRAFT: compose открылся, ищу кнопку Save draft", Log.INFO, true);
            op = Op.WAITING_SAVE;
            handler.postDelayed(this::attemptSaveDraftClick, COMPOSE_READY_DELAY_MS);
            return;
        }

        if (op == Op.WAITING_SAVE && url.contains("v=cm")) {
            // compose still on screen; nothing to do here
            return;
        }

        if (op == Op.VERIFYING_SAVE && url.contains("v=dr")) {
            handler.postDelayed(this::verifySavedDraftOnPage, 700L);
            return;
        }

        if (op == Op.READING_DRAFTS && url.contains("v=dr")) {
            handler.postDelayed(this::readDraftListOnPage, 700L);
        }
    }

    private void onSendClicked() {
        if (!loggedIn) {
            logStep("SEND: ещё не выполнен вход", Log.WARN, true);
            return;
        }
        if (busy || op != Op.IDLE) {
            logStep("SEND: уже занят другим действием", Log.WARN, true);
            return;
        }

        String text = etMessage.getText().toString().trim();
        if (text.isEmpty()) {
            logStep("SEND: пустое сообщение", Log.WARN, true);
            return;
        }

        busy = true;
        setBusyUi(true);
        pendingMessageText = text;
        pendingSubject = PREFIX + System.currentTimeMillis() + "|" + text.replace('\n', ' ');
        verifyTriesLeft = VERIFY_MAX_TRIES;

        logStep("SEND: начинаю черновик: " + pendingSubject, Log.INFO, true);
        openComposeForSelf(text, pendingSubject);
    }

    private void openComposeForSelf(String bodyText, String subject) {
        String composeUrl = BASE
                + "?v=cm&to=me"
                + "&su=" + Uri.encode(subject)
                + "&body=" + Uri.encode(bodyText);

        op = Op.WAITING_COMPOSE;
        logStep("SEND: открываю compose", Log.INFO, true);
        workerWebView.post(() -> workerWebView.loadUrl(composeUrl));
    }

    private void attemptSaveDraftClick() {
        if (op != Op.WAITING_SAVE) return;

        String js = "(function(){try{" +
                "var sels=['input[name=\"ms\"]','button[name=\"ms\"]','input[value=\"Save Draft\"]','input[value=\"Сохранить черновик\"]','button[aria-label*=\"Save\"]'];" +
                "for(var i=0;i<sels.length;i++){var el=document.querySelector(sels[i]);if(el){el.click();return 'clicked:'+sels[i];}}" +
                "return 'missing-save-button';" +
                "}catch(e){return 'js-error:'+e.message;}})();";

        workerWebView.evaluateJavascript(js, value -> {
            String result = jsValue(value);
            logStep("SAVE: " + result, result.startsWith("clicked") ? Log.INFO : Log.ERROR, true);

            if (result.startsWith("clicked")) {
                op = Op.VERIFYING_SAVE;
                handler.postDelayed(this::loadDraftsForVerification, 900L);
            } else {
                failCurrentDraft("не нашёл кнопку Save draft: " + result);
            }
        });
    }

    private void loadDraftsForVerification() {
        if (op != Op.VERIFYING_SAVE || pendingSubject == null) return;
        logStep("VERIFY: открываю drafts для проверки", Log.INFO, true);
        workerWebView.loadUrl(DRAFTS_URL);
    }

    private void verifySavedDraftOnPage() {
        if (op != Op.VERIFYING_SAVE || pendingSubject == null) return;

        String subject = pendingSubject;
        String js = buildFindSubjectJs(subject);

        workerWebView.evaluateJavascript(js, value -> {
            String result = jsValue(value);
            boolean found = result.contains("\"found\":true") || result.equals("true");
            logStep("VERIFY: " + result, found ? Log.INFO : Log.WARN, true);

            if (found) {
                successCurrentDraft();
            } else {
                verifyTriesLeft--;
                if (verifyTriesLeft > 0) {
                    logStep("VERIFY: ещё не видно, пробую снова (осталось " + verifyTriesLeft + ")", Log.WARN, true);
                    handler.postDelayed(() -> workerWebView.loadUrl(DRAFTS_URL), VERIFY_RETRY_DELAY_MS);
                } else {
                    failCurrentDraft("черновик так и не появился в списке");
                }
            }
        });
    }

    private void readDrafts() {
        if (!loggedIn || op != Op.IDLE || busy) return;
        op = Op.READING_DRAFTS;
        suppressNextDraftList = false;
        workerWebView.loadUrl(DRAFTS_URL);
    }

    private void readDraftListOnPage() {
        if (op != Op.READING_DRAFTS) return;

        String js = buildReadDraftsJs();
        workerWebView.evaluateJavascript(js, value -> {
            if (suppressNextDraftList) {
                op = Op.IDLE;
                return;
            }

            String json = jsValue(value);
            logStep("DRAFTS: список получен", Log.INFO, true);
            try {
                JSONArray arr = new JSONArray(json);
                int added = 0;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    String th = o.optString("th", "");
                    String subj = o.optString("subj", "");
                    if (th.isEmpty() || subj.isEmpty() || seenThreadIds.contains(th)) continue;
                    seenThreadIds.add(th);
                    String text = subj.startsWith(PREFIX) ? subj.substring(PREFIX.length()) : subj;
                    pushIncomingMessage(text, th);
                    added++;
                }
                logStep("DRAFTS: новых сообщений: " + added, Log.INFO, true);
            } catch (Exception e) {
                logStep("DRAFTS PARSE ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage(), Log.ERROR, true);
            } finally {
                op = Op.IDLE;
                schedulePoll();
            }
        });
    }

    private void successCurrentDraft() {
        String text = pendingMessageText == null ? "" : pendingMessageText;
        logStep("SAVE: черновик появился, всё ок", Log.INFO, true);

        clearSendState();
        if (!text.isEmpty()) {
            pushSentMessage(text, null);
            etMessage.setText("");
        }
        setStatus("черновик сохранён", 0xFF4CAF50);
        busy = false;
        setBusyUi(false);
        op = Op.IDLE;
        schedulePoll();
    }

    private void failCurrentDraft(String reason) {
        logStep("ERROR: " + reason, Log.ERROR, true);
        setStatus("ошибка: " + reason, 0xFFFF5252);
        busy = false;
        setBusyUi(false);
        op = Op.IDLE;
        clearSendState();
        schedulePoll();
    }

    private void clearSendState() {
        pendingMessageText = null;
        pendingSubject = null;
        verifyTriesLeft = 0;
    }

    private void pushSentMessage(String text, String threadId) {
        messages.add(new Message(text, true, threadId));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
    }

    private void pushIncomingMessage(String text, String threadId) {
        messages.add(new Message(text, false, threadId));
        adapter.notifyItemInserted(messages.size() - 1);
        rvMessages.scrollToPosition(messages.size() - 1);
        setStatus("онлайн", 0xFF4CAF50);
    }

    private void setBusyUi(boolean sending) {
        runOnUiThread(() -> {
            btnSend.setEnabled(!sending && loggedIn);
            etMessage.setEnabled(!sending && loggedIn);
            if (sending) {
                setStatus("работаю…", 0xFFFFB300);
            }
        });
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

        showChatScreen();
        workerWebView.loadUrl(BASE);
        logStep("AUTH: вход подтверждён, чат открыт", Log.INFO, true);
        schedulePoll();
    }

    private void scheduleAuthCheck(long delayMs) {
        if (loggedIn || authCheckScheduled) return;
        authCheckScheduled = true;
        handler.postDelayed(authCheckRunnable, delayMs);
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_MS);
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

    private void setStatus(String label, int color) {
        runOnUiThread(() -> {
            tvStatus.setText(label);
            tvStatus.setTextColor(color);
        });
    }

    private void logStep(String message, int priority, boolean showInChat) {
        Log.println(priority, TAG, message);
        String shortMsg = message;
        setStatus(shortMsg, priority == Log.ERROR ? 0xFFFF5252 : priority == Log.WARN ? 0xFFFFB300 : 0xFF4CAF50);

        if (!showInChat) return;
        if (!loggedIn && !showChatIsNotYetVisible()) return;

        runOnUiThread(() -> {
            messages.add(new Message("🛠 " + message, false, null));
            adapter.notifyItemInserted(messages.size() - 1);
            rvMessages.scrollToPosition(messages.size() - 1);
        });
    }

    private boolean showChatIsNotYetVisible() {
        return loginOverlay != null && loginOverlay.getVisibility() != View.VISIBLE;
    }

    private static String shortUrl(String url) {
        if (url == null) return "<null>";
        if (url.length() <= 90) return url;
        return url.substring(0, 87) + "...";
    }

    private static String safeError(WebResourceError error) {
        if (error == null) return "unknown";
        try {
            return error.getErrorCode() + ": " + error.getDescription();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String jsValue(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\n", "\n").replace("\\u003C", "<").replace("\\u003E", ">");
    }

    private static String buildReadDraftsJs() {
        return "(function(){" +
                "var res=[];" +
                "var links=document.querySelectorAll('a');" +
                "for(var i=0;i<links.length;i++){" +
                "  var a=links[i];" +
                "  var t=(a.textContent||'').trim();" +
                "  if(t.indexOf('" + PREFIX + "')!==0) continue;" +
                "  var m=a.href && a.href.match(/th=([^&]+)/);" +
                "  if(m) res.push({th:m[1],subj:t});" +
                "}" +
                "return JSON.stringify(res);" +
                "})()";
    }

    private static String buildFindSubjectJs(String subject) {
        String safe = subject.replace("\\", "\\\\").replace("'", "\\'");
        return "(function(){" +
                "var found=false;" +
                "var links=document.querySelectorAll('a');" +
                "for(var i=0;i<links.length;i++){" +
                "  var a=links[i];" +
                "  var t=(a.textContent||'').trim();" +
                "  if(t=== '" + safe + "' || t.indexOf('" + safe + "')===0){found=true;break;}" +
                "}" +
                "return JSON.stringify({found:found,subject:'" + safe + "'});" +
                "})()";
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
            // Старый мост оставлен только ради совместимости.
            // В этой версии чтение черновиков идёт через логику выше.
            Log.d(TAG, "Bridge onDraftList invoked: " + json);
        }
    }
}

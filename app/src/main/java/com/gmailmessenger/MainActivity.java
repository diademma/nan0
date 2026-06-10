package com.gmailmessenger;

import android.annotation.SuppressLint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
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
 * Стабильная версия MainActivity.
 * Предоставляет 5-секундную паузу для завершения фонового POST-запроса синхронизации Gmail
 * перед обновлением папки Черновиков, что решает проблему обрыва сети (net::ERR_FAILED).
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "GmailMessenger";

    private static final String LOGIN_URL = "https://mail.google.com/";
    private static final String PREFIX = "GM1|";

    private static final long POLL_MS = 45_000L;
    private static final long AUTH_CHECK_DELAY_MS = 1_500L;
    private static final long AUTH_RETRY_MS = 2_000L;
    private static final int VERIFY_MAX_TRIES = 5;

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
    private final Set<String> sentSubjects = new HashSet<>();

    private boolean loggedIn = false;
    private boolean authCheckScheduled = false;
    private boolean busy = false;
    private boolean pollScheduled = false;

    private String pendingMessageText = null;
    private String pendingSubject = null;
    private int verifyTriesLeft = 0;
    private boolean composeInjected = false;

    private String dynamicBaseUrl = "https://mail.google.com/mail/mu/";

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
                    : "AUTH: cookies ещё нет, жду login");

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
                String url = safeUrl(request);
                if (url.contains("mail.google.com")) {
                    logStep("LOGIN_ERR: " + shortUrl(url) + " / " + errorDescription(error));
                } else {
                    Log.w(TAG, "Фаервол заблокировал фоновый ресурс при логине: " + url);
                }
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
                String url = safeUrl(request);
                if (url.contains("mail.google.com")) {
                    logStep("WORKER_ERR: " + shortUrl(url) + " / " + errorDescription(error));
                } else {
                    Log.w(TAG, "Фаервол заблокировал фоновый ресурс воркера: " + url);
                }
            }
        });
    }

    private void handleWorkerPageFinished(String url) {
        if (url != null && url.contains("/mail/mu/mp/")) {
            int idx = url.indexOf("/mp/");
            if (idx != -1) {
                int slashIdx = url.indexOf("/", idx + 4);
                if (slashIdx != -1) {
                    dynamicBaseUrl = url.substring(0, slashIdx + 1);
                    logStep("WORKER: Базовый Touch-URL обновлен: " + dynamicBaseUrl);
                }
            }
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
        verifyTriesLeft = VERIFY_MAX_TRIES;
        composeInjected = false;

        pendingMessageText = text;
        pendingSubject = PREFIX + System.currentTimeMillis() + "|" + text;
        
        sentSubjects.add(pendingSubject);

        logStep("SEND: открываю мобильный черновик: " + pendingSubject);
        setStatus("SEND: открытие compose", 0xFFFFB300);

        pushMessage(text, true, null);

        // Даем WebView команду на переход
        workerWebView.post(() -> workerWebView.loadUrl(dynamicBaseUrl + "#co"));

        // Запуск умного цикла проверки готовности страницы
        handler.postDelayed(this::tryFillCompose, 500L);
    }

    private void tryFillCompose() {
        if (composeInjected || op != Op.WAITING_COMPOSE) return;

        String currentUrl = workerWebView.getUrl();
        if (!isComposeUrl(currentUrl)) {
            logStep("SEND: Жду загрузки формы, текущий URL: " + shortUrl(currentUrl));
            handler.postDelayed(this::tryFillCompose, 1000L);
            return;
        }

        composeInjected = true;
        logStep("SEND: Форма открыта! Запускаю автозаполнение...");
        injectFillCompose(pendingMessageText);
    }

    private void injectFillCompose(String text) {
        String escapedTo = JSONObject.quote("me");
        String escapedSub = JSONObject.quote(pendingSubject);
        String escapedBody = JSONObject.quote(text);

        String js = "javascript:(function(){"
                + "var toVal=" + escapedTo + ";"
                + "var subVal=" + escapedSub + ";"
                + "var bodyVal=" + escapedBody + ";"
                
                + "var toEl=document.getElementById('composeto');"
                + "var subEl=document.getElementById('cmcsubj');"
                + "var bodyEl=document.getElementById('cmcbody');"
                
                // Проверяем наличие всех трех полей на странице
                + "if(!toEl || !subEl || !bodyEl){"
                + "  var debugInputs=[];"
                + "  document.querySelectorAll('input, textarea, [contenteditable=\"true\"]').forEach(function(el) {"
                + "    debugInputs.push(el.tagName + '(id=\"' + el.id + '\", name=\"' + el.name + '\", ph=\"' + el.placeholder + '\", cls=\"' + el.className + '\")');"
                + "  });"
                + "  return 'ERR_MISSING - TO:' + (toEl?'OK':'FAIL') + ' SUB:' + (subEl?'OK':'FAIL') + ' BODY:' + (bodyEl?'OK':'FAIL') + ' | FOUND_ELEMENTS: ' + debugInputs.join(' | ');"
                + "}"
                
                // 1. Имитация полноценного ввода для Кому (composeto)
                + "toEl.focus();"
                + "toEl.value=toVal;"
                + "toEl.dispatchEvent(new KeyboardEvent('keydown', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "toEl.dispatchEvent(new Event('input', {bubbles:true}));"
                + "toEl.dispatchEvent(new Event('change', {bubbles:true}));"
                + "toEl.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "toEl.blur();"
                
                // 2. Имитация полноценного ввода для Тема (cmcsubj)
                + "subEl.focus();"
                + "subEl.value=subVal;"
                + "subEl.dispatchEvent(new KeyboardEvent('keydown', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "subEl.dispatchEvent(new Event('input', {bubbles:true}));"
                + "subEl.dispatchEvent(new Event('change', {bubbles:true}));"
                + "subEl.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "subEl.blur();"
                
                // 3. Имитация полноценного ввода для Текста (cmcbody)
                + "bodyEl.focus();"
                + "if(bodyEl.tagName==='DIV') { bodyEl.textContent=bodyVal; }"
                + "else { bodyEl.value=bodyVal; }"
                + "bodyEl.dispatchEvent(new KeyboardEvent('keydown', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "bodyEl.dispatchEvent(new Event('input', {bubbles:true}));"
                + "bodyEl.dispatchEvent(new Event('change', {bubbles:true}));"
                + "bodyEl.dispatchEvent(new KeyboardEvent('keyup', {bubbles:true, cancelable:true, key:'a', char:'a'}));"
                + "bodyEl.blur();"
                
                // 4. Имитация клика по кнопке «Назад» (id="cmcb") для сохранения
                + "var backBtn = document.getElementById('cmcb') || "
                + "              document.querySelector('[aria-label*=\"Back\" i]') || "
                + "              document.querySelector('[aria-label*=\"Назад\" i]') || "
                + "              document.querySelector('.back') || "
                + "              document.querySelector('[id*=\"back\" i]');"
                + "if (backBtn) {"
                + "  backBtn.click();"
                + "  return 'TO_OK SUB_OK BODY_OK CLICK_BACK_OK';"
                + "} else {"
                + "  window.history.back();"
                + "  return 'TO_OK SUB_OK BODY_OK FALLBACK_BACK_OK';"
                + "}"
                + "})();";

        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                String stripped = stripJsValue(value);
                logStep("SEND: результат заполнения = " + stripped);

                if (stripped.contains("ERR_MISSING")) {
                    failOperation("SEND_ERR: поля ввода не обнаружены на странице.");
                    return;
                }

                op = Op.VERIFYING_SAVE;
                
                // ДАЕМ GMAIL 5 СЕКУНД СПОКОЙСТВИЯ: Не перезагружаем вкладку, 
                // пока фоновый POST-запрос синхронизации черновика долетает до облака Google.
                logStep("SAVE: Жду 5 секунд завершения фоновой синхронизации Gmail...");
                handler.postDelayed(() -> {
                    logStep("SAVE: Синхронизация завершена. Проверяю сохраненный черновик...");
                    verifySavedDraft();
                }, 5000L);
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
        logStep("VERIFY: перехожу в черновики, попытка " + (VERIFY_MAX_TRIES - verifyTriesLeft) + "/" + VERIFY_MAX_TRIES);

        // Используем технику Cache-Buster (?r=timestamp) для форсирования ПОЛНОЙ перезагрузки страницы.
        workerWebView.loadUrl(dynamicBaseUrl + "?r=" + System.currentTimeMillis() + "#tl/Drafts");
        
        handler.postDelayed(MainActivity.this::tryVerifyDraftList, 500L);
    }

    private void tryVerifyDraftList() {
        if (op != Op.VERIFYING_SAVE) return;

        String currentUrl = workerWebView.getUrl();
        if (!isDraftsUrl(currentUrl)) {
            logStep("VERIFY: Жду открытия папки Черновики, текущий URL: " + shortUrl(currentUrl));
            handler.postDelayed(this::tryVerifyDraftList, 1000L);
            return;
        }

        logStep("VERIFY: Черновики открыты! Ищу наше сообщение...");
        injectVerifyDraftList();
    }

    private void pollDrafts() {
        if (!loggedIn) return;

        busy = true;
        op = Op.READING_DRAFTS;
        workerWebView.loadUrl(dynamicBaseUrl + "?r=" + System.currentTimeMillis() + "#tl/Drafts");
        
        handler.postDelayed(this::tryReadDraftsList, 500L);
    }

    private void tryReadDraftsList() {
        if (op != Op.READING_DRAFTS) return;

        String currentUrl = workerWebView.getUrl();
        if (!isDraftsUrl(currentUrl)) {
            logStep("READ: Жду открытия папки Черновики, текущий URL: " + shortUrl(currentUrl));
            handler.postDelayed(this::tryReadDraftsList, 1000L);
            return;
        }

        logStep("READ: Черновики открыты! Сканирую новые сообщения...");
        injectReadDrafts();
    }

    private void injectVerifyDraftList() {
        if (op != Op.VERIFYING_SAVE) return;
        if (pendingSubject == null) {
            failOperation("VERIFY_ERR: нет subject для проверки");
            return;
        }

        String js = buildDraftSearchJs(pendingSubject, true);
        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (op != Op.VERIFYING_SAVE) return;
                
                String result = stripJsValue(value);
                logStep("VERIFY: результат проверки JS = " + result);

                try {
                    JSONArray arr = new JSONArray(result);
                    if (arr.length() > 0) {
                        logStep("VERIFY_OK: черновик успешно сохранён в Gmail!");
                        finishOperationSuccess();
                    } else {
                        if (verifyTriesLeft > 0) {
                            handler.postDelayed(MainActivity.this::verifySavedDraft, 1500L);
                        } else {
                            failOperation("VERIFY_ERR: черновик не появился в списке");
                        }
                    }
                } catch (Exception e) {
                    failOperation("VERIFY_ERR: ошибка парсинга списка / " + e.getMessage());
                }
            }
        });
    }

    private void injectReadDrafts() {
        if (op != Op.READING_DRAFTS) return;

        String js = buildDraftSearchJs(PREFIX, false);
        workerWebView.evaluateJavascript(js, new ValueCallback<String>() {
            @Override
            public void onReceiveValue(String value) {
                if (op != Op.READING_DRAFTS) return;

                try {
                    String result = stripJsValue(value);
                    JSONArray arr = new JSONArray(result);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject o = arr.getJSONObject(i);
                        String th = o.optString("th", "");
                        String subj = o.optString("subj", "");

                        if (th.isEmpty() || seenThreadIds.contains(th)) continue;
                        if (sentSubjects.contains(subj)) continue;
                        
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

    private String buildDraftSearchJs(String searchStr, boolean exactMatch) {
        String escapedTarget = JSONObject.quote(searchStr);
        String escapedPrefix = JSONObject.quote(PREFIX);
        return "javascript:(function(){"
                + "var target=" + escapedTarget + ";"
                + "var prefix=" + escapedPrefix + ";"
                + "var exact=" + exactMatch + ";"
                + "var res=[];"
                + "var els=document.querySelectorAll('[data-thread-id]');"
                + "for(var i=0;i<els.length;i++){"
                + "  var el=els[i];"
                + "  var th=el.getAttribute('data-thread-id');"
                + "  var txt=(el.textContent||'').trim();"
                + "  if(th){"
                + "    if(exact){"
                + "      if(txt.indexOf(target)!==-1){ res.push({th:th,subj:target}); }"
                + "    }else{"
                + "      var idx=txt.indexOf(prefix);"
                + "      if(idx!==-1){"
                + "        var line=txt.substring(idx).split('\\n')[0].trim();"
                + "        res.push({th:th,subj:line});"
                + "      }"
                + "    }"
                + "  }"
                + "}"
                + "if(res.length===0){"
                + "  var links=document.querySelectorAll('a');"
                + "  for(var j=0;j<links.length;j++){"
                + "    var a=links[j];"
                + "    var t=(a.textContent||'').trim();"
                + "    if(exact){"
                + "      if(t.indexOf(target)===0){"
                + "        var m=a.href && a.href.match(/th=([^&]+)/);"
                + "        if(m) res.push({th:m[1],subj:t});"
                + "      }"
                + "    }else{"
                + "      if(t.indexOf(prefix)===0){"
                + "        var m=a.href && a.href.match(/th=([^&]+)/);"
                + "        if(m) res.push({th:m[1],subj:t});"
                + "      }"
                + "    }"
                + "  }"
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

        workerWebView.loadUrl(dynamicBaseUrl);
        
        // Через 3 секунды после входа инициируем автоматический тестовый черновик
        handler.postDelayed(() -> {
            logStep("AUTO: Запускаю автоматический тестовый черновик...");
            createDraftToSelf("Привет! Это автоматический тест связи сразу после входа.");
        }, 3000L);

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
        return url != null && (url.contains("#co") || url.contains("compose"));
    }

    private boolean isDraftsUrl(String url) {
        return url != null && (url.contains("v=dr") || url.contains("draft") || url.contains("#tl/Drafts"));
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
}
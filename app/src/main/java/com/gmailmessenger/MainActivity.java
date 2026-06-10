package com.gmailmessenger;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.CookieManager;
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

public class MainActivity extends AppCompatActivity {

    // ── Gmail URL constants ─────────────────────────────────────────────
    private static final String BASE    = "https://mail.google.com/mail/u/0/h/";
    private static final String DRAFTS  = BASE + "?v=dr";
    // Prefix convention: APK_<13-digit-ms>:<message>  /  SRV_<13-digit-ms>:<message>
    private static final String PFX_SND = "APK_";
    private static final String PFX_RCV = "SRV_";
    private static final int    PREFIX_LEN = 18; // "APK_" + 13 digits + ":"

    // ── Poll interval ───────────────────────────────────────────────────
    private static final long POLL_MS = 30_000L;

    // ── UI ──────────────────────────────────────────────────────────────
    private WebView       webView;
    private RecyclerView  rvMessages;
    private EditText      etMessage;
    private ImageButton   btnSend;
    private TextView      tvStatus;
    private FrameLayout   loginOverlay;
    private WebView       loginWebView;   // full-screen, shown only during login

    // ── State ───────────────────────────────────────────────────────────
    private enum Op { IDLE, NAVIGATING_COMPOSE, SAVING_DRAFT, NAVIGATING_DRAFTS }
    private Op     op             = Op.IDLE;
    private String pendingMessage = null;  // message waiting to be drafted

    private boolean isLoggedIn = false;
    private final Set<String> seenThreadIds = new HashSet<>();

    // ── Data ────────────────────────────────────────────────────────────
    private final List<Message> messages = new ArrayList<>();
    private ChatAdapter adapter;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = () -> {
        if (isLoggedIn && op == Op.IDLE) {
            checkInbox();
        }
        schedulePoll();
    };

    // ═══════════════════════════════════════════════════════════════════
    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView      = findViewById(R.id.webView);
        rvMessages   = findViewById(R.id.rvMessages);
        etMessage    = findViewById(R.id.etMessage);
        btnSend      = findViewById(R.id.btnSend);
        tvStatus     = findViewById(R.id.tvStatus);
        loginOverlay = findViewById(R.id.loginOverlay);
        loginWebView = findViewById(R.id.loginWebView);

        setupRecycler();
        setupHiddenWebView(webView);
        setupLoginWebView(loginWebView);

        btnSend.setOnClickListener(v -> onSendClicked());

        // Accept cookies so sessions survive restart
        CookieManager.getInstance().setAcceptCookie(true);

        // Start with login screen; will auto-hide once authenticated
        showLoginScreen();
        loginWebView.loadUrl(BASE);
    }

    // ── RecyclerView ────────────────────────────────────────────────────
    private void setupRecycler() {
        adapter = new ChatAdapter(messages);
        LinearLayoutManager lm = new LinearLayoutManager(this);
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);
        rvMessages.setAdapter(adapter);
    }

    // ── Login WebView (visible, full-screen during auth) ────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupLoginWebView(WebView wv) {
        applyWebSettings(wv);
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return !isGoogleUrl(req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                if (url.contains("mail.google.com") && !isLoggedIn) {
                    // Login complete — copy cookies and switch to chat mode
                    CookieManager.getInstance().flush();
                    isLoggedIn = true;
                    showChatScreen();
                    // Sync the hidden webView session
                    webView.loadUrl(BASE);
                }
            }
        });
    }

    // ── Hidden operational WebView ──────────────────────────────────────
    @SuppressLint("SetJavaScriptEnabled")
    private void setupHiddenWebView(WebView wv) {
        applyWebSettings(wv);
        wv.addJavascriptInterface(new GmailBridge(), "AndroidBridge");
        wv.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest req) {
                return !isGoogleUrl(req.getUrl().toString());
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                handlePageFinished(url);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void applyWebSettings(WebView wv) {
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        // Pretend to be a regular mobile Chrome
        s.setUserAgentString(
            "Mozilla/5.0 (Linux; Android 13; Tablet) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36");
    }

    private boolean isGoogleUrl(String url) {
        return url.contains("google.com") || url.contains("googleusercontent.com");
    }

    // ── Page-finished state machine ─────────────────────────────────────
    private void handlePageFinished(String url) {
        if (op == Op.NAVIGATING_COMPOSE && url.contains("v=cm")) {
            op = Op.SAVING_DRAFT;
            injectSaveDraft();
            return;
        }

        if (op == Op.SAVING_DRAFT) {
            // Any next page = save completed (Gmail redirects after save)
            op = Op.IDLE;
            if (pendingMessage != null) {
                final String sent = pendingMessage;
                pendingMessage = null;
                runOnUiThread(() -> pushMessage(sent, true, null));
            }
            return;
        }

        if (op == Op.NAVIGATING_DRAFTS && url.contains("v=dr")) {
            injectReadDrafts();
            op = Op.IDLE;  // JS bridge will add messages async
        }
    }

    // ── Send ─────────────────────────────────────────────────────────────
    private void onSendClicked() {
        String text = etMessage.getText().toString().trim();
        if (text.isEmpty() || !isLoggedIn || op != Op.IDLE) return;

        etMessage.setText("");
        pendingMessage = text;

        // Encode: APK_<timestamp>:<message>
        String subject = PFX_SND + System.currentTimeMillis() + ":" +
                         text.replace("\n", " ");
        String composeUrl = BASE + "?v=cm&to=me&su=" +
                            android.net.Uri.encode(subject) +
                            "&body=" + android.net.Uri.encode(text);

        op = Op.NAVIGATING_COMPOSE;
        webView.post(() -> webView.loadUrl(composeUrl));
        setStatus("отправка…", 0xFFFFB300);
    }

    // ── Inject JS: click "Save Draft" button ─────────────────────────────
    private void injectSaveDraft() {
        // In Gmail basic HTML, the Save Draft button has name="ms"
        String js = "javascript:(function(){"
                  + "  var b=document.querySelector('input[name=\"ms\"]');"
                  + "  if(b){b.click();}"
                  + "})();";
        webView.post(() -> webView.loadUrl(js));
    }

    // ── Poll inbox ────────────────────────────────────────────────────────
    private void checkInbox() {
        op = Op.NAVIGATING_DRAFTS;
        webView.post(() -> webView.loadUrl(DRAFTS));
    }

    // ── Inject JS: read draft list, return SRV_ subjects ─────────────────
    private void injectReadDrafts() {
        // Parse draft list page — subjects carry the message payload
        String js = "javascript:(function(){"
                  + "  var res=[];"
                  + "  var links=document.querySelectorAll('a');"
                  + "  for(var i=0;i<links.length;i++){"
                  + "    var a=links[i];"
                  + "    if(!a.href||a.href.indexOf('v=om')===-1) continue;"
                  + "    var subj=a.textContent.trim();"
                  + "    if(subj.indexOf('" + PFX_RCV + "')!==0) continue;"
                  + "    var m=a.href.match(/th=([^&]+)/);"
                  + "    if(m) res.push({th:m[1],subj:subj});"
                  + "  }"
                  + "  AndroidBridge.onDraftList(JSON.stringify(res));"
                  + "})();";
        webView.post(() -> webView.evaluateJavascript(js, null));
    }

    // ── JavaScript → Android bridge ───────────────────────────────────────
    private class GmailBridge {
        @JavascriptInterface
        public void onDraftList(String json) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o   = arr.getJSONObject(i);
                    String th      = o.getString("th");
                    String subj    = o.getString("subj");

                    if (seenThreadIds.contains(th)) continue;
                    seenThreadIds.add(th);

                    // Extract message text from subject after prefix
                    // Format: SRV_<13digits>:<text>
                    String text = subj.length() > PREFIX_LEN
                            ? subj.substring(PREFIX_LEN)
                            : subj;

                    final String finalText = text;
                    runOnUiThread(() -> pushMessage(finalText, false, th));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            runOnUiThread(() -> setStatus("онлайн", 0xFF4CAF50));
        }
    }

    // ── UI helpers ────────────────────────────────────────────────────────
    private void pushMessage(String text, boolean isSent, String threadId) {
        messages.add(new Message(text, isSent, threadId));
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
            schedulePoll();
        });
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_MS);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollRunnable);
    }
}

package com.dsha.whalepet;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 一次性登录：登录后拦截页面自己发出的 Authorization 头（平台接口只认它）。
 * 抓到即加密保存并退出；抓不到就留在页面上继续等。
 */
public class AccountLoginActivity extends Activity {

    private WebView web;
    private TextView hint;
    private boolean saved;
    private String lastUrl = "";
    private WebView popup;
    private android.widget.FrameLayout popupHolder;
    private LinearLayout rootView;

    private class JsBridge {
        @JavascriptInterface
        public void onApi(String url, String body) {
            if (!trustedPage()) return;
            if (url == null || body == null || body.length() < 30) return;
            AccountSession.savePage(AccountLoginActivity.this, url, body);
            boolean bad = body.contains("\"code\":4") || body.contains("INVALID_PARAM");
            Bridge.diag("acct", "page api " + trimUrl(url)
                    + (bad ? " BAD=" + body.substring(0, Math.min(200, body.length()))
                           : " len=" + body.length()));
            if (url.contains("get_user_summary") && body.contains("biz_data") && !saved) {
                saved = true;
                String ck = CookieManager.getInstance().getCookie("https://platform.deepseek.com");
                if (ck != null && ck.length() > 20) {
                    AccountSession.saveCookie(AccountLoginActivity.this, ck);
                }
                CookieManager.getInstance().flush();
                runOnUiThread(() -> {
                    Toast.makeText(AccountLoginActivity.this,
                            AccountLoginActivity.this.getString(R.string.login_toast_saved),
                            Toast.LENGTH_SHORT).show();
                    finish();
                });
            }
        }

        @JavascriptInterface
        public void onUsage(String url, String body) {
            if (!trustedPage()) return;
            if (url == null || body == null || body.length() < 50) return;
            AccountSession.saveUsage(AccountLoginActivity.this, url, body);
            Bridge.diag("acct", "page usage captured " + url.substring(0, Math.min(60, url.length()))
                    + " len=" + body.length());
        }

        @JavascriptInterface
        public void onHeaders(String json) {
            if (!trustedPage()) return;
            try {
                org.json.JSONObject o = new org.json.JSONObject(json);
                String auth = o.optString("authorization", "");
                if (auth.isEmpty()) return;
                AccountSession.saveAuth(AccountLoginActivity.this, o);
                Bridge.diag("acct", "auth captured raw len=" + auth.length()
                        + " prefix=" + (auth.startsWith("Bearer ") ? "Bearer" : "raw"));
                if (!saved) {
                    saved = true;
                    String ck = CookieManager.getInstance().getCookie("https://platform.deepseek.com");
                    if (ck != null && ck.length() > 20) AccountSession.saveCookie(AccountLoginActivity.this, ck);
                    CookieManager.getInstance().flush();
                    runOnUiThread(() -> {
                        Toast.makeText(AccountLoginActivity.this,
                            AccountLoginActivity.this.getString(R.string.login_toast_saved),
                            Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }
            } catch (Throwable t) { Log.ignored("AccountLoginActivity", t);
                // JSON 坏了就忽略
            }
        }

        @JavascriptInterface
        public void onDbg(String msg) {
            if (!trustedPage() || msg == null) return;
            // JS 会带请求 URL 过来：去掉 query，避免把 code/token 写进日志
            Bridge.diag("acct", "req " + msg.replaceAll("\\?\\S*", "?…"));
        }

        @JavascriptInterface
        public void onLocalToken(String token, String url) {
            if (!trustedPage()) return;
            if (token == null || token.length() < 10) return;
            if (url == null || url.contains("/sign_in")) return;      // 未登录页的占位值
            AccountSession.save(AccountLoginActivity.this, token);
            Bridge.diag("acct", "localStorage userToken saved len=" + token.length() + " url=" + url);
            // 不在这里关闭：真正的"已完成登录"证据是拿到 get_user_summary
        }

        @JavascriptInterface
        public void onToken(String token) {
            if (!trustedPage()) return;
            if (token == null || token.length() < 10 || saved) return;   // 平台用的是短令牌，不再要求 JWT
            saved = true;
            AccountSession.save(AccountLoginActivity.this, token);
            String ck = CookieManager.getInstance().getCookie("https://platform.deepseek.com");
            if (ck != null && ck.length() > 20) AccountSession.saveCookie(AccountLoginActivity.this, ck);
            CookieManager.getInstance().flush();
            Bridge.diag("acct", "token captured len=" + token.length());
            runOnUiThread(() -> {
                Toast.makeText(AccountLoginActivity.this,
                        AccountLoginActivity.this.getString(R.string.login_toast_saved),
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        }
    }

    /** 只认平台域名与 OAuth 跳转用到的 Google 域；其余一律交给系统浏览器。 */
    private static boolean isTrustedUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(java.util.Locale.US);
            return host.equals("deepseek.com") || host.endsWith(".deepseek.com")
                    || host.equals("google.com") || host.endsWith(".google.com")
                    || host.equals("gstatic.com") || host.endsWith(".gstatic.com")
                    || host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com");
        } catch (Throwable t) {
            return false;
        }
    }

    /** JS 桥只对平台页面开放；被导航到第三方页后，任何 onToken/onApi 调用都丢弃。 */
    private boolean trustedPage() {
        try {
            return web != null && isTrustedUrl(web.getUrl());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 日志里只留 host+path：OAuth 回调的 query 可能带 code。 */
    private static String trimUrl(String url) {
        if (url == null) return "";
        try {
            java.net.URI uri = java.net.URI.create(url);
            return uri.getHost() + (uri.getPath() == null ? "" : uri.getPath());
        } catch (Throwable t) {
            return url.length() > 80 ? url.substring(0, 80) : url;
        }
    }

    /** 非平台链接交给系统浏览器，别把 JS 桥留在第三方页面。 */
    private void openExternal(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Throwable t) { Log.ignored("AccountLoginActivity.ext", t); }
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        hint = new TextView(this);
        hint.setText(getString(R.string.login_hint));
        hint.setPadding(28, 28, 28, 16);
        root.addView(hint);

        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);
        web.getSettings().setAllowContentAccess(false);
        // Google 会拒绝带 "wv" 标记的 WebView 做 OAuth：伪装成普通 Chrome 移动端
        web.getSettings().setUserAgentString(
                "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 "
                        + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36");
        web.getSettings().setSupportMultipleWindows(true);
        web.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.addJavascriptInterface(new JsBridge(), "petBridge");
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture,
                                          android.os.Message resultMsg) {
                WebView child = new WebView(AccountLoginActivity.this);
                child.getSettings().setJavaScriptEnabled(true);
                child.getSettings().setDomStorageEnabled(true);
                child.getSettings().setUserAgentString(view.getSettings().getUserAgentString());
                CookieManager.getInstance().setAcceptThirdPartyCookies(child, true);
                child.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        if (isTrustedUrl(url)) return false;
                        openExternal(url);
                        return true;
                    }

                    @Override
                    public void onPageFinished(WebView v, String url) {
                        lastUrl = url == null ? "" : url;
                        Bridge.diag("acct", "popup page=" + trimUrl(url));
                        installHook(v);
                        if (url != null && url.contains("oauth/google/callback")) {
                            // 回调已由服务端处理并种下会话：把主页面拉回用量页
                            CookieManager.getInstance().flush();
                            runOnUiThread(() -> {
                                if (popupHolder != null) {
                                    try {
                                        rootView.removeView(popupHolder);
                                    } catch (Throwable t) { Log.ignored("AccountLoginActivity", t);
                                        // 已经移除
                                    }
                                    popupHolder = null;
                                }
                                web.loadUrl("https://platform.deepseek.com/usage");
                            });
                        }
                    }
                });
                popup = child;
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(child);
                resultMsg.sendToTarget();
                // 屏幕外挂一个极小容器，保证弹窗页面能加载（不可见，不挡操作）
                try {
                    FrameLayout holder = new FrameLayout(AccountLoginActivity.this);
                    holder.addView(child, new FrameLayout.LayoutParams(1, 1));
                    rootView.addView(holder, new LinearLayout.LayoutParams(1, 1));
                    popupHolder = holder;
                } catch (Throwable t) { Log.ignored("AccountLoginActivity", t);
                    // 挂不上也继续，多数流程仍能完成
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (isTrustedUrl(url)) return false;
                openExternal(url);
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap icon) {
                installHook(v);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                lastUrl = url == null ? "" : url;
                installHook(v);
                if (url != null && url.contains("accounts.google.com")) {
                    hint.setText(getString(R.string.login_hint_google));
                }
                String ck = CookieManager.getInstance().getCookie("https://platform.deepseek.com");
                String rest = ck == null ? "" : ck.replaceAll("(?i)aws-waf-token=[^;]*;?", "").trim();
                Bridge.diag("acct", "page=" + trimUrl(url) + " cookieLen=" + (ck == null ? 0 : ck.length())
                        + " sessionLikely=" + (rest.length() > 20));
                if (ck != null && rest.length() > 20) {
                    AccountSession.saveCookie(AccountLoginActivity.this, ck);
                    hint.setText(getString(R.string.login_hint_ok));
                }
                v.evaluateJavascript(
                        "(function(){function d(s){try{var o=[];for(var i=0;i<s.length;i++){"
                        + "var k=s.key(i);var v=s.getItem(k)||'';o.push(k+'('+v.length+')');}"
                        + "return o.join(' ');}catch(e){return 'err';}}"
                        + "petBridge.onDbg('storage L['+d(localStorage)+'] S['+d(sessionStorage)+']');"
                        + "try{var t=localStorage.getItem('userToken')||localStorage.getItem('user_token');"
                        + "if(t&&t.length>10)petBridge.onLocalToken(t);}catch(e){}})()",
                        null);
            }
        });
        root.addView(web, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        rootView = root;
        setContentView(root);
        web.loadUrl("https://platform.deepseek.com/usage");
    }

    /** 包住 fetch / XHR：记录请求 URL 与头名字（不含值），并抓 Authorization。 */
    private void installHook(WebView v) {
        String js = "(function(){if(window.__petHook)return;window.__petHook=1;"
                + "var n=0;"
                + "function send(t){try{if(t&&t.length>8)petBridge.onToken(t);}catch(e){}}"
                + "function dump(u,h){try{if(String(u).indexOf('platform.deepseek.com')<0)return;var o={};if(h){"
                + "if(typeof h.get==='function'){var ks=['authorization','x-device-id','x-device-model','x-client-bundle-id','x-client-version','x-client-locale','x-client-timezone-offset'];"
                + "for(var i=0;i<ks.length;i++){var v=h.get(ks[i]);if(v)o[ks[i]]=v;}}"
                + "else if(Object.prototype.toString.call(h)!=='[object Array]'){for(var k in h){var n=String(k).toLowerCase();"
                + "if(n==='authorization'||n==='x-device-id'||n==='x-device-model'||n==='x-client-bundle-id'||n==='x-client-version'||n==='x-client-locale'||n==='x-client-timezone-offset')o[n]=String(h[k]);}}}"
                + "if(o['authorization'])petBridge.onHeaders(JSON.stringify(o));}catch(e){}}"
                + "function note(u,h){try{if(n++>40)return;"
                + "var ns=[];if(h){"
                + "if(typeof h.get==='function'){ns.push('FORM');var t=h.get('authorization');if(t)send(String(t).split(' ').pop());}"
                + "else if(Object.prototype.toString.call(h)==='[object Array]'){for(var i=0;i<h.length;i++){var nm=String(h[i]);ns.push(nm.toLowerCase());}}"
                + "else{for(var k in h){var nm2=String(k).toLowerCase();ns.push(nm2);"
                + "if(String(u).indexOf('platform.deepseek.com')>=0&&(nm2.indexOf('authorization')>=0||nm2.indexOf('token')>=0||nm2.indexOf('auth')>=0)){send(String(h[k]).split(' ').pop());}}}}"
                + "petBridge.onDbg(String(u).slice(0,120)+' hdr='+ns.join(','));}catch(e){}}"
                + "var of=window.fetch;if(of){window.fetch=function(){try{var a=arguments;"
                + "var u=a[0];u=(u&&u.url)?u.url:String(u);note(u,a[1]&&a[1].headers);dump(u,a[1]&&a[1].headers);}catch(e){}"
                + "return of.apply(this,arguments);};}"
                + "var oo=XMLHttpRequest.prototype.open,os=XMLHttpRequest.prototype.setRequestHeader;"
                + "XMLHttpRequest.prototype.open=function(m,u){try{this.__petU=u;this.__petN=[];this.__petH={};}catch(e){}"
                + "return oo.apply(this,arguments);};"
                + "XMLHttpRequest.prototype.setRequestHeader=function(k,val){try{"
                + "var low=String(k).toLowerCase();this.__petN=this.__petN||[];this.__petN.push(low);"
                + "if(low==='authorization'||low.indexOf('x-device')===0||low.indexOf('x-client')===0){"
                + "this.__petH=this.__petH||{};this.__petH[low]=String(val);"
                + "if(low==='authorization'&&this.__petU&&String(this.__petU).indexOf('/api/v0/')>=0){send(String(val));dump(this.__petU,this.__petH);}}"
                + "}catch(e){}"
                + "return os.apply(this,arguments);};"
                + "var oldLoad=XMLHttpRequest.prototype.addEventListener;"
                + "var osend=XMLHttpRequest.prototype.send;"
                + "var origSend=osend;"
                + "XMLHttpRequest.prototype.send=function(){try{note(this.__petU,this.__petN);"
                + "var u=String(this.__petU||'');if(u.indexOf('/api/v0/')>=0){"
                + "var x=this;x.addEventListener('load',function(){try{if(x.responseText&&x.responseText.length>50){"
                + "petBridge.onApi(u,x.responseText);}}catch(e){}});}"
                + "}catch(e){}"
                + "return osend.apply(this,arguments);};"
                + "})();";
        v.evaluateJavascript(js, null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            String ck = CookieManager.getInstance().getCookie("https://platform.deepseek.com");
            if (ck != null && ck.length() > 20) {
                AccountSession.saveCookie(this, ck);
                CookieManager.getInstance().flush();
            }
        } catch (Throwable t) { Log.ignored("AccountLoginActivity", t);
            // 存不进去不影响
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (web != null) {
            web.destroy();
            web = null;
        }
    }
}

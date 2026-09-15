package com.fongmi.android.tv.server.process;

import android.text.TextUtils;

import com.fongmi.android.tv.App;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.server.impl.Process;
import com.fongmi.android.tv.setting.Setting;
import com.github.catvod.crawler.DebugLogStore;
import com.github.catvod.crawler.diagnostics.DiagnosticLogBuffer;
import com.github.catvod.crawler.diagnostics.DiagnosticReport;
import com.github.catvod.crawler.diagnostics.DiagnosticAccess;
import com.github.catvod.crawler.diagnostics.DiagnosticCapture;
import com.fongmi.android.tv.player.DiagnosticControls;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.IHTTPSession;
import fi.iki.elonen.NanoHTTPD.Response;

public class DebugLogs implements Process {
    private static final java.util.concurrent.atomic.AtomicBoolean archiveBusy = new java.util.concurrent.atomic.AtomicBoolean();
    private static final java.util.concurrent.ScheduledExecutorService exportTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "diagnostic-export-expiry"); thread.setDaemon(true); return thread;
    });

    @Override
    public boolean isRequest(IHTTPSession session, String url) {
        return url.startsWith("/debug/diag/") || url.startsWith("/debug/logs") || url.startsWith("/debug/mpd") || url.startsWith("/debug/stream") || url.startsWith("/debug/clear") || url.startsWith("/debug/enable") || url.startsWith("/debug/disable");
    }

    @Override
    public Response doResponse(IHTTPSession session, String url, Map<String, String> files) {
        if (url.startsWith("/debug/diag/")) return diagnosticAction(session, url, files);
        if (url.startsWith("/debug/enable") || url.startsWith("/debug/disable") || url.startsWith("/debug/clear")) {
            if (session.getMethod() != NanoHTTPD.Method.POST) return diagnosticMessage(Response.Status.METHOD_NOT_ALLOWED, "请在调试页配对后操作");
            if (!authorized(session)) return diagnosticMessage(Response.Status.FORBIDDEN, "请使用 App 显示的配对码授权操作");
        }
        if (url.startsWith("/debug/enable")) {
            Setting.putDebugLog(true);
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT, NanoHTTPD.MIME_HTML, ""), "/debug/logs");
        }
        if (url.startsWith("/debug/disable")) {
            Setting.putDebugLog(false);
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT, NanoHTTPD.MIME_HTML, ""), "/debug/logs");
        }
        if (url.startsWith("/debug/clear")) {
            DebugLogStore.clear();
            if (DebugLogStore.isEnabled()) Setting.logDebugEnvironment("clear");
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.REDIRECT, NanoHTTPD.MIME_HTML, ""), "/debug/logs");
        }
        if (url.startsWith("/debug/stream")) return stream(session);
        if (url.startsWith("/debug/mpd")) return mpd();
        if (url.startsWith("/debug/logs.txt")) return download();
        return page();
    }

    private Response mpd() {
        try {
            File file = new File(App.get().getCacheDir(), "youtube-mpd.xml");
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/dash+xml", new FileInputStream(file), file.length()), null);
        } catch (Exception e) {
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "MPD unavailable"), null);
        }
    }

    private Response page() {
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html());
        return noCache(response, null);
    }

    private Response download() {
        DiagnosticLogBuffer.Export export = DiagnosticReport.text(DebugLogStore.export());
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "text/plain; charset=utf-8", export.input, export.length);
        response.addHeader("Content-Disposition", "attachment; filename=webhtv-debug-log.txt");
        response.addHeader("X-Content-Type-Options", "nosniff");
        response.addHeader("X-Diagnostic-Completeness", export.partial ? "partial" : "declared-window");
        return noCache(response, null);
    }

    private boolean originAllowed(IHTTPSession session) {
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (String address : new String[]{Server.get().getAddress("/debug/logs"), Server.get().getAddress(false)}) {
            try { hosts.add(java.net.URI.create(address).getRawAuthority().toLowerCase(java.util.Locale.ROOT)); } catch (RuntimeException ignored) {}
        }
        return DiagnosticAccess.sameOrigin(session.getHeaders().get("origin"), session.getHeaders().get("host"), hosts);
    }

    private boolean authorized(IHTTPSession session) {
        String auth = session.getHeaders().get("authorization");
        return originAllowed(session) && auth != null && auth.startsWith("Bearer ") && DiagnosticControls.ACCESS.authorize(auth.substring(7));
    }

    private Response diagnosticAction(IHTTPSession session, String url, Map<String, String> files) {
        if (url.equals("/debug/diag/status") && session.getMethod() == NanoHTTPD.Method.GET)
            return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", DiagnosticControls.status().toString()), null);
        if (session.getMethod() != NanoHTTPD.Method.POST) return diagnosticMessage(Response.Status.METHOD_NOT_ALLOWED, "此操作需要 POST");
        if (!originAllowed(session)) return diagnosticMessage(Response.Status.FORBIDDEN, "请求来源不匹配");
        try {
            if (Long.parseLong(session.getHeaders().getOrDefault("content-length", "0")) > 2048) return diagnosticMessage(Response.Status.BAD_REQUEST, "请求过长");
            String raw = files.getOrDefault("postData", "{}");
            if (raw.length() > 2048) return diagnosticMessage(Response.Status.BAD_REQUEST, "请求过长");
            JsonObject data = JsonParser.parseString(raw.isEmpty() ? "{}" : raw).getAsJsonObject();
            if (url.equals("/debug/diag/pair")) {
                String token = DiagnosticControls.ACCESS.pair(field(data, "code"));
                if (token == null) return diagnosticMessage(Response.Status.FORBIDDEN, "配对码无效、已过期或尝试过多，请查看 App");
                JsonObject response = new JsonObject(); response.addProperty("token", token); response.addProperty("expiresInSeconds", 900);
                return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", response.toString()), null);
            }
            if (!authorized(session)) return diagnosticMessage(Response.Status.FORBIDDEN, "未配对、授权已过期或操作过快");
            switch (url) {
                case "/debug/diag/mark" -> DiagnosticControls.mark(field(data, "symptom"));
                case "/debug/diag/deep" -> {
                    if (!data.has("consent") || !data.get("consent").getAsBoolean()) return diagnosticMessage(Response.Status.BAD_REQUEST, "需要确认限时统计");
                    DiagnosticControls.startDepth(data.has("seconds") ? data.get("seconds").getAsInt() : 60);
                }
                case "/debug/diag/stop" -> DiagnosticCapture.stop("user-stopped");
                case "/debug/diag/compare" -> DiagnosticControls.compare(field(data, "parameter"), field(data, "old"), field(data, "new"), field(data, "note"));
                case "/debug/diag/export" -> { return archive(); }
                default -> { return diagnosticMessage(Response.Status.NOT_FOUND, "未找到操作"); }
            }
            return diagnosticMessage(Response.Status.OK, "已记录");
        } catch (RuntimeException error) {
            return diagnosticMessage(Response.Status.BAD_REQUEST, error instanceof IllegalStateException || error instanceof IllegalArgumentException ? error.getMessage() : "无效请求");
        }
    }

    private String field(JsonObject value, String key) { return value.has(key) && value.get(key).isJsonPrimitive() ? value.get(key).getAsString() : ""; }

    private Response diagnosticMessage(Response.Status status, String message) {
        JsonObject value = new JsonObject(); value.addProperty("ok", status == Response.Status.OK); value.addProperty("message", message);
        return noCache(NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", value.toString()), null);
    }

    private Response archive() {
        if (!DebugLogStore.isEnabled()) return diagnosticMessage(Response.Status.BAD_REQUEST, "请先开启调试日志");
        if (!archiveBusy.compareAndSet(false, true)) return diagnosticMessage(Response.Status.SERVICE_UNAVAILABLE, "已有诊断包正在导出，请稍后重试");
        DiagnosticLogBuffer.Export source = DebugLogStore.export();
        try {
            try (java.io.InputStream ignored = source.openAgain()) { /* Reject a non-repeatable snapshot before HTTP 200. */ }
            java.io.PipedInputStream input = new java.io.PipedInputStream(64 << 10);
            java.io.PipedOutputStream output = new java.io.PipedOutputStream(input);
            java.util.concurrent.ScheduledFuture<?> expiry = exportTimer.schedule(() -> {
                try { input.close(); output.close(); } catch (java.io.IOException ignored) {}
            }, 60, java.util.concurrent.TimeUnit.SECONDS);
            Thread worker = new Thread(() -> {
                try (source; output) { DiagnosticReport.archive(source, output); }
                catch (java.io.IOException | RuntimeException ignored) { DebugLogStore.collectorFailure(); }
                finally { expiry.cancel(false); archiveBusy.set(false); }
            }, "diagnostic-report-export");
            worker.setDaemon(true); worker.start();
            Response response = NanoHTTPD.newChunkedResponse(Response.Status.OK, "application/zip", input);
            response.addHeader("Content-Disposition", "attachment; filename=webhtv-av-report.zip");
            response.addHeader("X-Content-Type-Options", "nosniff");
            return noCache(response, null);
        } catch (java.io.IOException | RuntimeException error) {
            try { source.close(); } catch (java.io.IOException ignored) {}
            archiveBusy.set(false);
            return diagnosticMessage(Response.Status.INTERNAL_ERROR, "诊断包生成失败，请重试 TXT 下载");
        }
    }

    private Response stream(IHTTPSession session) {
        if (session.getParms().containsKey("afterSeq")) return incrementalStream(session);
        long version = DebugLogStore.version();
        boolean unchanged = version == paramLong(session, "v", -1);
        String text = unchanged ? null : DebugLogStore.text();
        Response response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", "{\"enabled\":" + DebugLogStore.isEnabled() + ",\"size\":" + DebugLogStore.size() + ",\"bytes\":" + DebugLogStore.bytes() + ",\"version\":" + version + ",\"text\":" + (unchanged ? "null" : "\"" + json(text) + "\"") + "}");
        return noCache(response, null);
    }

    private Response incrementalStream(IHTTPSession session) {
        DiagnosticLogBuffer.Snapshot snapshot = DebugLogStore.incremental(paramLong(session, "afterSeq", -1),
                session.getParms().get("run"), paramLong(session, "generation", -1));
        JsonObject result = new JsonObject();
        result.addProperty("enabled", DebugLogStore.isEnabled());
        if (snapshot == null) {
            result.addProperty("reset", true); result.addProperty("text", "调试日志未开启\n");
            result.addProperty("runId", ""); result.addProperty("generation", -1);
            result.addProperty("newestSeq", -1); result.addProperty("size", 0); result.addProperty("bytes", 0);
        } else {
            result.addProperty("runId", snapshot.runId()); result.addProperty("generation", snapshot.generation());
            result.addProperty("version", snapshot.version()); result.addProperty("oldestSeq", snapshot.oldestSeq());
            result.addProperty("newestSeq", snapshot.newestSeq()); result.addProperty("reset", snapshot.reset());
            result.addProperty("gap", snapshot.gap()); result.addProperty("text", snapshot.text());
            result.addProperty("size", snapshot.lines().size()); result.addProperty("bytes", snapshot.health().get("diskBytes").getAsLong());
            result.add("health", snapshot.health());
        }
        return noCache(NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", result.toString()), null);
    }

    private long paramLong(IHTTPSession session, String key, long fallback) {
        try {
            return Long.parseLong(session.getParms().get(key));
        } catch (Exception e) {
            return fallback;
        }
    }

    private Response noCache(Response response, String location) {
        response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.addHeader("Pragma", "no-cache");
        if (!TextUtils.isEmpty(location)) response.addHeader("Location", location);
        return response;
    }

    private String html() {
        String logs = escape(DebugLogStore.text());
        String localUrl = Server.get().getAddress("/debug/logs");
        String lanUrl = Server.get().getAddress(false) + "/debug/logs";
        boolean enabled = DebugLogStore.isEnabled();
        return "<!doctype html>"
                + "<html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,viewport-fit=cover\">"
                + "<title>调试日志</title>"
                + "<style>" + css() + "</style></head><body>"
                + "<main><section class=\"topbar\"><h1>调试日志</h1><a href=\"/debug/logs\">刷新</a><a id=\"download\" href=\"/debug/logs.txt\" download=\"webhtv-debug-log.txt\">下载</a><a href=\"/debug/clear\">清空</a><a href=\"" + (enabled ? "/debug/disable" : "/debug/enable") + "\">" + (enabled ? "关闭" : "开启") + "</a><span id=\"meta\" class=\"meta\" data-version=\"" + DebugLogStore.version() + "\">" + (enabled ? "开启" : "关闭") + " · " + DebugLogStore.size() + " 行 · " + DebugLogStore.bytes() / 1024 + " KB</span></section>"
                + "<details class=\"info\"><summary>地址和说明</summary><p class=\"hint\">本页增量显示最近的日志窗口；下载包含保留的轮转日志、会话快照和完整性说明。开启后记录环境、WebHome、HTTP 服务、爬虫请求和播放链路；关闭会自动清空。音视频就绪回调不代表用户实际看见或听见。</p>"
                + "<div class=\"addr\"><a href=\"" + escape(localUrl) + "\">本机地址：" + escape(localUrl) + "</a><a href=\"" + escape(lanUrl) + "\">局域网地址：" + escape(lanUrl) + "</a></div></details>"
                + diagnosticHtml()
                + "<section class=\"tools\"><div class=\"chips\"><button class=\"chip on\" data-mode=\"all\">全部</button><button class=\"chip\" data-mode=\"diagnostic\">音视频诊断</button><button class=\"chip\" data-mode=\"proxy\">代理</button><button class=\"chip\" data-mode=\"player\">播放</button><button class=\"chip\" data-mode=\"webhome\">WebHome</button><button class=\"chip\" data-mode=\"console\">Console</button><button class=\"chip\" data-mode=\"webview\">WebView</button><button class=\"chip\" data-mode=\"api\">站源</button><button class=\"chip\" data-mode=\"pan\">网盘</button><button class=\"chip\" data-mode=\"server\">服务</button><button class=\"chip\" data-mode=\"sync\">同步</button><button class=\"chip\" data-mode=\"startup\">启动</button><button class=\"chip\" data-mode=\"error\">错误</button></div>"
                + "<div class=\"search\"><input id=\"filter\" placeholder=\"过滤关键词，例如 tmdb、夸克、timeout\"><label class=\"simple\"><input id=\"simple\" type=\"checkbox\" autocomplete=\"off\"><span>解释</span></label><button id=\"pause\">暂停</button></div><div id=\"summary\" class=\"summary\"></div></section>"
                + "<div id=\"logs\" class=\"logs\"></div><pre id=\"raw\" class=\"fallback\">" + logs + "</pre></main>"
                + "<script>" + scriptEnhanced() + diagnosticScript() + "</script>"
                + "</body></html>";
    }

    private String css() {
        return "html,body{box-sizing:border-box;width:100%;max-width:100%;margin:0;overflow-x:hidden;background:#f4f6f8;color:#1f2328;font:14px/1.5 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;}*,*:before,*:after{box-sizing:inherit;min-width:0}body{position:relative}"
                + ".topbar{box-sizing:border-box;display:flex;flex-wrap:wrap;gap:8px;align-items:center;width:100%;max-width:100%;overflow:hidden;margin:0 0 8px;padding:8px;background:#fff;border:1px solid #d8dee4;border-radius:8px;box-shadow:0 2px 10px rgba(31,35,40,.04)}"
                + "h1{margin:0 10px 0 0;font-size:17px;font-weight:650;white-space:nowrap}.meta{margin-left:auto;color:#656d76;font-size:12px;white-space:nowrap}"
                + "a,button{appearance:none;border:1px solid #d0d7de;border-radius:7px;background:#fff;color:#24292f;padding:6px 9px;text-decoration:none;font:inherit;cursor:pointer;white-space:nowrap}button.on,.chip.on{background:#0969da;border-color:#0969da;color:#fff}a:active,button:active{background:#eaeef2}main{box-sizing:border-box;width:100%;max-width:1280px;margin:0 auto;padding:8px;overflow-x:hidden}.info{box-sizing:border-box;max-width:100%;overflow:hidden;margin:0 0 8px;padding:7px 9px;background:#fff;border:1px solid #d8dee4;border-radius:8px}.info summary{cursor:pointer;color:#57606a;font-size:12px}.hint{margin:8px 0;color:#656d76;font-size:12px}"
                + ".addr{display:grid;grid-template-columns:minmax(0,1fr);gap:6px;margin:0;color:#57606a;font-size:12px}.addr a{display:block;min-width:0;max-width:100%;overflow:hidden;white-space:normal;overflow-wrap:anywhere;word-break:break-all;padding:7px 9px;background:#f6f8fa}"
                + ".tools{box-sizing:border-box;width:100%;max-width:100%;overflow:hidden;margin:0 0 8px;padding:8px;background:#fff;border:1px solid #d8dee4;border-radius:8px;box-shadow:0 2px 10px rgba(31,35,40,.04)}.chips{display:flex;flex-wrap:wrap;gap:6px;overflow:hidden;padding-bottom:2px}.chip{flex:0 0 auto}.search{display:grid;grid-template-columns:minmax(0,1fr) auto auto;gap:6px;align-items:center;margin-top:6px}input{box-sizing:border-box;width:100%;min-width:0;border:1px solid #d0d7de;border-radius:7px;padding:7px 9px;font:inherit}.simple{display:flex;align-items:center;gap:4px;color:#57606a;font-size:12px;white-space:nowrap}.summary{margin-top:6px;color:#57606a;font-size:12px;white-space:normal;overflow-wrap:anywhere;word-break:break-all}"
                + ".logs{box-sizing:border-box;display:grid;gap:8px;width:100%;max-width:100%;min-width:0;overflow:hidden}.fallback{box-sizing:border-box;max-width:100%;overflow:hidden;margin:0;background:#fff;border:1px solid #d8dee4;border-radius:8px;padding:10px;color:#57606a;font:12px/1.5 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-all}.entry{box-sizing:border-box;width:100%;max-width:100%;min-width:0;overflow:hidden;background:#fff;border:1px solid #d8dee4;border-radius:8px;padding:9px 10px}.entry.ok{border-left:4px solid #1a7f37}.entry.warn{border-left:4px solid #bf8700}.entry.err{border-left:4px solid #cf222e}.entry.raw{border-left:4px solid #8c959f}.top{display:flex;gap:8px;align-items:center;max-width:100%;min-width:0;overflow:hidden}.badge{flex:0 0 auto;border-radius:999px;padding:2px 7px;background:#eaeef2;color:#57606a;font-size:12px}.entry.ok .badge{background:#dafbe1;color:#116329}.entry.err .badge{background:#ffebe9;color:#cf222e}.title{min-width:0;font-weight:650;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.time{margin-left:auto;color:#8c959f;font-size:12px;white-space:nowrap}.detail{box-sizing:border-box;max-width:100%;min-width:0;overflow:hidden;margin-top:5px;color:#57606a;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-all}.rawline{display:block;box-sizing:border-box;width:100%;max-width:100%;min-width:0;overflow:hidden;margin-top:6px;padding-top:6px;border-top:1px dashed #d8dee4;color:#6e7781;font:12px/1.45 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-all}body.simple .rawline{display:none}"
                + "@media(max-width:680px){.topbar{padding:7px 8px}h1{font-size:16px}.meta{flex-basis:100%;margin-left:0}.addr{grid-template-columns:1fr}.search{grid-template-columns:minmax(0,1fr) auto auto}.title{white-space:normal}.time{display:none}.entry{padding:8px}.detail{font-size:13px}}";
    }

    private String scriptEnhanced() {
        return "const rawEl=document.getElementById('raw'),logs=document.getElementById('logs'),meta=document.getElementById('meta'),summary=document.getElementById('summary'),filter=document.getElementById('filter'),simple=document.getElementById('simple'),pause=document.getElementById('pause'),download=document.getElementById('download');"
                + "let raw=rawEl.textContent,mode='all',paused=false,stick=true,lastVersion=Number(meta.dataset.version||0),lastSeq=-1,lastRun='',lastGeneration=-1;simple.checked=false;document.body.classList.remove('simple');addEventListener('scroll',()=>{resetX();stick=(innerHeight+scrollY)>=(document.body.scrollHeight-80)},{passive:true});setInterval(resetX,500);"
                + "document.querySelectorAll('.chip').forEach(b=>b.onclick=()=>{document.querySelectorAll('.chip').forEach(x=>x.classList.remove('on'));b.classList.add('on');mode=b.dataset.mode;render()});filter.oninput=render;simple.onchange=()=>{document.body.classList.toggle('simple',simple.checked);render();resetX()};pause.onclick=()=>{paused=!paused;pause.textContent=paused?'继续':'暂停';pause.classList.toggle('on',paused)};"
                + "download.onclick=()=>{paused=true;pause.textContent='继续';pause.classList.add('on')};"
                + "function esc(s){return String(s||'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]))}"
                + "function resetX(){try{const y=scrollY||pageYOffset||0;(document.scrollingElement||document.documentElement).scrollLeft=0;document.documentElement.scrollLeft=0;document.body.scrollLeft=0;if((document.scrollingElement||document.documentElement).scrollLeft||document.body.scrollLeft)scrollTo(0,y)}catch(e){}}"
                + "function part(s,k){const i=s.indexOf(k);if(i<0)return'';let v=s.slice(i+k.length),e=v.length;[' ',',',']'].forEach(c=>{const p=v.indexOf(c);if(p>=0&&p<e)e=p});return v.slice(0,e)}"
                + "function between(s,a,b){const i=s.indexOf(a);if(i<0)return'';const j=s.indexOf(b,i+a.length);return j<0?s.slice(i+a.length):s.slice(i+a.length,j)}"
                + "function proxyName(s){return between(s,'proxy=[',']').replace('SOCKS @ ','SOCKS ').replace('/<unresolved>','')}"
                + "function parse(line){const a=line.indexOf(' ['),b=line.indexOf('] ',a+2),c=line.indexOf(': ',b+2);return{line,time:a>0?line.slice(0,a):'',thread:a>0&&b>0?line.slice(a+2,b):'',tag:b>0&&c>0?line.slice(b+2,c):'',msg:c>0?line.slice(c+2):line}}"
                + "function base(r){const e={kind:'raw',state:'raw',badge:r.tag||'日志',title:r.tag||'原始日志',detail:r.msg||r.line,raw:r.line,time:r.time};if(r.tag==='av-diag'){try{e.diag=JSON.parse(r.msg)}catch(ignore){}}return e}"
                + "function explain(r){const text=(r.tag+': '+r.msg),low=text.toLowerCase();let e=base(r);if(r.tag==='av-diag'){try{const d=JSON.parse(r.msg);if(d.schemaVersion===1&&typeof d.event==='string'){e.kind='diagnostic';e.state=d.level==='error'?'err':d.level==='warn'?'warn':'raw';e.badge='音视频诊断';e.title=d.event;e.detail=JSON.stringify(d);return e}}catch(ignore){}}if(r.tag==='webview-console'||r.tag==='webhome-console'){e.kind='console';e.state=(low.includes('error')||low.includes('exception')||low.includes('uncaught'))?'err':(low.includes('warning')||low.includes('warn'))?'warn':'ok';e.badge='Console';e.title=r.tag==='webhome-console'?'WebHome 控制台输出':'网页控制台输出';e.detail=r.msg;return e}if(r.tag==='server'){e.kind='server';e.state='raw';e.badge='服务';e.title='App 本机 HTTP 服务收到请求';e.detail=r.msg;return e}if(low.includes('error')||low.includes('exception')||low.includes('failed')||low.includes('timeout')||low.includes('失败')||low.includes('崩溃')||low.includes('异常')){e.kind='error';e.state='err';e.badge='错误';e.title='发现错误或异常';return e}"
                + "if(r.tag==='startup'){e.kind='startup';e.state='ok';e.badge='启动';e.title='启动阶段耗时';e.detail=r.msg;return e}"
                + "if(r.tag==='debug'){e.kind='server';e.state='ok';e.badge='调试';e.title=r.msg.includes('ready')?'调试日志服务已准备':'调试日志状态变化';e.detail=r.msg;return e}"
                + "if(r.tag==='env'){e.kind='startup';e.state='ok';e.badge='环境';e.title='设备和系统环境';e.detail=r.msg;return e}"
                + "if(r.tag==='web-resource'){e.kind='server';e.state=r.msg.includes('->')?'ok':'raw';e.badge='资源';e.title=r.msg.includes('->')?'Web 资源代理返回响应':'Web 资源代理发起请求';e.detail=r.msg;return e}"
                + "if(r.tag==='sync'){e.kind='sync';e.state='ok';e.badge='同步';e.title=r.msg.includes('archive')?'正在打包同步目录':r.msg.includes('restore')?'正在恢复同步目录':'一键同步';e.detail=r.msg;return e}"
                + "if(r.tag==='pan-check'||r.tag==='pan-check-net'){e.kind='pan';e.state=r.msg.includes('state=valid')||r.msg.includes('-> 200')?'ok':'raw';e.badge='网盘';e.title=r.tag==='pan-check-net'?(r.msg.includes('->')?'网盘检测网络响应':'网盘检测网络请求'):'网盘链接检测';e.detail=r.msg;return e}"
                + "if(r.tag==='webview'||r.tag==='webview-parse'||r.tag==='webhome-webview'){e.kind='webview';e.state=(low.includes('error')||low.includes('gone')||low.includes('crash'))?'err':'ok';e.badge='WebView';e.title=r.msg.includes('provider')?'当前 WebView 内核':r.msg.includes('resource error')?'网页资源加载失败':r.msg.includes('page finished')?'网页加载完成':r.msg.includes('page started')?'网页开始加载':'WebView 事件';e.detail=r.msg;return e}"
                + "if(r.tag&&r.tag.startsWith('webhome')){e.kind='webhome';e.state=r.msg.includes('-> 200')||r.msg.includes('invoke')?'ok':'raw';e.badge='WebHome';e.title=r.tag==='webhome-net'?(r.msg.includes('->')?'WebHome 网络响应':'WebHome 网络请求'):'WebHome 开放能力调用';e.detail=r.msg;return e}"
                + "if(['home','homeVideo','category','detail','search','action'].includes(r.tag)){e.kind='api';e.state='raw';e.badge='站源';e.title=r.tag==='search'?'站源搜索':r.tag==='detail'?'站源详情':r.tag==='category'?'站源分类':r.tag==='action'?'站源动作':'站源首页';e.detail=r.msg;return e}"
                + "if(r.tag==='SpiderDebug'){e.kind='api';e.state=low.includes('成功')||low.includes('通过')?'ok':'raw';e.badge='接口';e.title=low.includes('代理程序')?'接口代理程序':'接口插件日志';e.detail=r.msg;return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('app proxy enabled')){e.kind='proxy';e.state='ok';e.badge='代理';e.title='壳代理已启用';e.detail='已加载 '+part(r.msg,'rules=')+' 条规则，默认代理 '+part(r.msg,'defaultUrl=');return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('app proxy disabled')){e.kind='proxy';e.state='warn';e.badge='代理';e.title='壳代理已关闭';e.detail='配置仍可保留，但当前不会代理请求';return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('select hit')){e.kind='proxy';e.state='ok';e.badge='代理命中';e.title='请求命中壳代理';e.detail=(part(r.msg,'host=')||part(r.msg,'uri='))+' 命中规则 '+(part(r.msg,'rule=')||'-')+'，使用 '+proxyName(r.msg);return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('local-target')){e.kind='proxy';e.state='warn';e.badge='直连';e.title='本机服务直连';e.detail='访问 127.0.0.1 这类 App 本机服务，按设计不走壳代理';return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('request uri=/proxy')){e.kind='server';e.state='raw';e.badge='服务';e.title='App 内置代理接口收到请求';e.detail=r.msg;return e}"
                + "if(r.tag==='proxy'&&r.msg.includes('response do=')){e.kind='server';e.state=r.msg.includes('status=200')?'ok':'warn';e.badge='服务';e.title='App 内置代理接口返回响应';e.detail=r.msg;return e}"
                + "if(r.tag==='okhttp-player'&&r.msg.includes('connectStart')){e.kind='player';e.state=r.msg.includes('proxy=SOCKS')?'ok':'warn';e.badge='播放';e.title=r.msg.includes('proxy=SOCKS')?'播放器通过壳代理连接':'播放器直连';e.detail=(part(r.msg,'url=')||'')+' · '+(between(r.msg,'proxy=',',')||'');return e}"
                + "if(r.tag==='okhttp-player'&&r.msg.includes('connectionAcquired')){e.kind='player';e.state=r.msg.includes('via proxy')?'ok':'warn';e.badge='播放';e.title=r.msg.includes('via proxy')?'播放器连接已走代理':'播放器连接已建立';e.detail=part(r.msg,'url=')||r.msg;return e}"
                + "if(r.tag==='okhttp-player'&&r.msg.includes('response')){e.kind='player';e.state='ok';e.badge='响应';e.title='播放器收到响应';e.detail='状态 '+(part(r.msg,'code=')||'-')+' · '+(part(r.msg,'contentType=')||'')+' · '+(part(r.msg,'url=')||'');return e}"
                + "if(r.tag==='okhttp-player'&&r.msg.includes('start')){e.kind='player';e.state='raw';e.badge='播放';e.title='播放器开始请求';e.detail=part(r.msg,'url=')||r.msg;return e}"
                + "if(['player','player-engine','playback-flow','exo-source'].includes(r.tag)){e.kind='player';e.state=low.includes('error')?'err':'ok';e.badge='播放';e.title=r.tag==='playback-flow'?'播放页面/服务链路':r.tag==='player-engine'?'播放器内核事件':r.tag==='exo-source'?'媒体源创建':'播放解析/状态';e.detail=r.msg;return e}return e}"
                + "function pass(e,key){if(!diagPass(e))return false;const all=(e.raw+' '+e.title+' '+e.detail).toLowerCase();if(key&&!all.includes(key))return false;if(mode==='all')return !(e.kind==='server'&&e.state==='raw');if(mode==='error')return e.kind==='error'||e.state==='err'||e.diag&&e.diag.level==='fatal';return e.kind===mode}"
                + "function render(){try{const key=filter.value.trim().toLowerCase();const rows=raw.split('\\n').filter(Boolean).map(parse).map(explain);let shown=0,hit=0,err=0,playerProxy=0,webview=0,consoleCount=0,api=0;const html=[];rows.forEach(e=>{if(e.kind==='proxy'&&e.title.includes('命中'))hit++;if(e.kind==='error'||e.state==='err')err++;if(e.kind==='player'&&(e.title.includes('代理')||e.raw.includes('via proxy')))playerProxy++;if(e.kind==='webview')webview++;if(e.kind==='console')consoleCount++;if(e.kind==='api')api++;if(!pass(e,key))return;shown++;html.push('<div class=\"entry '+e.state+'\"><div class=\"top\"><span class=\"badge\">'+esc(e.badge)+'</span><span class=\"title\">'+esc(e.title)+'</span><span class=\"time\">'+esc(e.time)+'</span></div><div class=\"detail\">'+esc(e.detail)+'</div><code class=\"rawline\">'+esc(e.raw)+'</code></div>')});logs.innerHTML=html.join('')||'<div class=\"entry raw\"><div class=\"detail\">没有匹配日志</div></div>';summary.textContent='显示 '+shown+'/'+rows.length+' 行 · 错误 '+err+' 条 · 代理命中 '+hit+' 次 · 播放代理链路 '+playerProxy+' 次 · Console '+consoleCount+' 条 · WebView '+webview+' 条 · 站源 '+api+' 条';rawEl.hidden=true;resetX()}catch(err){rawEl.hidden=false;logs.innerHTML='<div class=\"entry err\"><div class=\"detail\">日志页面渲染失败，已显示原始日志：'+esc(err&&err.message?err.message:err)+'</div></div>';summary.textContent='渲染失败 · 已显示原始日志';resetX()}}"
                + "async function poll(){try{if(!paused){const r=await fetch('/debug/stream?afterSeq='+lastSeq+'&run='+encodeURIComponent(lastRun)+'&generation='+lastGeneration,{cache:'no-store'});const j=await r.json();lastSeq=j.newestSeq;lastRun=j.runId;lastGeneration=j.generation;const h=j.health||{};meta.textContent=(j.enabled?'开启':'关闭')+' · '+Math.ceil((j.bytes||0)/1024)+' KB'+(h.completeness==='partial'?' · 日志不完整，详见下载说明':'');if(j.reset||j.gap||j.text){raw=(j.reset||j.gap?'':raw)+(j.text||'');if(raw.length>524288){const cut=raw.indexOf('\\n',raw.length-524288);raw=cut<0?'':raw.slice(cut+1)}rawEl.textContent=raw;render();if(stick)scrollTo(0,document.body.scrollHeight)}}}catch(e){meta.textContent='日志连接失败，稍后重试'}setTimeout(poll,1500)}render();poll();";
    }

    private String diagnosticHtml() {
        return """
            <section class="tools" id="diagnostic-controls">
              <p class="hint">原样复现后标记现象，再下载日志。深度统计最多 120 秒，只记录数值，不保存画面或声音。</p>
              <div class="chips"><input id="diag-code" maxlength="6" inputmode="numeric" autocomplete="off" style="width:150px" placeholder="App 显示的配对码"><button id="diag-pair">配对操作</button><span id="diag-status" role="status"></span></div>
              <div class="chips" style="margin-top:8px"><select id="diag-symptom" aria-label="故障现象"><option>黑屏</option><option>画面不动</option><option>无声</option><option>断音</option><option>音画不同步</option><option>其他</option></select><button id="diag-mark">标记此刻故障</button><button id="diag-deep">深度统计 60 秒</button><button id="diag-stop">停止深度统计</button><button id="diag-zip">下载诊断包</button></div>
              <details style="margin-top:8px"><summary>记录一次单参数对照</summary><div class="chips"><select id="diag-parameter"><option value="decoder">解码方式</option><option value="renderer">渲染方式</option><option value="surface">画面尺寸/输出</option><option value="audio-output">音频输出</option><option value="audio-effects">音效</option><option value="network">网络</option><option value="player">播放器</option><option value="other">其他</option></select><input id="diag-old" maxlength="200" placeholder="修改前"><input id="diag-new" maxlength="200" placeholder="修改后"><input id="diag-note" maxlength="200" placeholder="观察到的现象"><button id="diag-compare">记录对照步骤</button></div><p class="hint">这里只记录你的对照步骤，播放设置仍在原设置页面修改。</p></details>
              <div class="chips" style="margin-top:8px"><select id="diag-trace" aria-label="播放记录"><option value="">所有播放记录</option></select><select id="diag-attempt" aria-label="播放尝试"><option value="">所有尝试</option></select><select id="diag-domain" aria-label="音视频链路"><option value="">所有链路</option><option value="video">视频</option><option value="audio">音频</option></select><select id="diag-priority" aria-label="优先级"><option value="">所有优先级</option><option value="critical">关键事件</option><option value="error">错误</option></select></div>
              <p id="diag-feedback" class="hint" role="status"></p>
            </section>
            """;
    }

    private String diagnosticScript() {
        return """
            function diagEl(id){return document.getElementById('diag-'+id)}
            function diagSay(text){diagEl('feedback').textContent=text}
            function diagToken(){try{return sessionStorage.getItem('webhtv-diagnostic-token')||''}catch(e){return''}}
            async function diagPost(path,data,binary){const r=await fetch(path,{method:'POST',headers:{'Content-Type':'application/json','Authorization':'Bearer '+diagToken()},body:JSON.stringify(data||{}),cache:'no-store'});if(!r.ok){let j;try{j=await r.json()}catch(e){}throw Error(j&&j.message||'操作失败，请重试')}return binary?r.blob():r.json()}
            function diagRun(action){return Promise.resolve().then(action).catch(e=>diagSay(e.message||'操作失败'))}
            diagEl('pair').onclick=()=>diagRun(async()=>{const j=await diagPost('/debug/diag/pair',{code:diagEl('code').value.trim()});try{sessionStorage.setItem('webhtv-diagnostic-token',j.token)}catch(e){throw Error('浏览器未允许会话存储，请使用本机 App 操作')}diagEl('code').value='';diagSay('已配对，操作授权有效 15 分钟')});
            diagEl('mark').onclick=()=>diagRun(async()=>{await diagPost('/debug/diag/mark',{symptom:diagEl('symptom').value});diagSay('已标记，继续记录后 15 秒；随后下载可包含故障前后上下文')});
            diagEl('deep').onclick=()=>{if(confirm('开启本次播放的 60 秒深度统计？只记录低分辨率画面和 PCM 数值，不保存图像或声音；到期自动停止。'))diagRun(async()=>{await diagPost('/debug/diag/deep',{seconds:60,consent:true});diagSay('已开启限时统计')})};
            diagEl('stop').onclick=()=>diagRun(async()=>{await diagPost('/debug/diag/stop');diagSay('深度统计已停止')});
            diagEl('compare').onclick=()=>diagRun(async()=>{await diagPost('/debug/diag/compare',{parameter:diagEl('parameter').value,old:diagEl('old').value,new:diagEl('new').value,note:diagEl('note').value});diagSay('已记录对照步骤；实际生效结果以播放事件为准')});
            diagEl('zip').onclick=()=>diagRun(async()=>{diagSay('正在生成诊断包…');const blob=await diagPost('/debug/diag/export',{},true);if(!blob.size)throw Error('诊断包为空，请重试 TXT 下载');const url=URL.createObjectURL(blob),a=document.createElement('a');a.href=url;a.download='webhtv-av-report.zip';document.body.appendChild(a);a.click();a.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);diagSay('诊断包已生成，含日志、可读报告、结构化事件和校验信息')});
            document.querySelectorAll('a[href="/debug/enable"],a[href="/debug/disable"],a[href="/debug/clear"]').forEach(a=>a.onclick=e=>{e.preventDefault();diagRun(async()=>{const r=await fetch(a.getAttribute('href'),{method:'POST',headers:{Authorization:'Bearer '+diagToken()}});if(!r.ok){const j=await r.json();throw Error(j.message)}location.reload()})});
            function diagPass(e){const trace=diagEl('trace').value,attempt=diagEl('attempt').value,domain=diagEl('domain').value,priority=diagEl('priority').value;if(!trace&&!attempt&&!domain&&!priority)return true;const d=e.diag;if(!d)return false;if(trace&&d.trace!==trace||attempt&&String(d.attemptId)!==attempt)return false;const name=d.event||'',p=d.observed&&d.observed.property&&d.observed.property.value||'';if(domain==='video'&&!/video|surface|display|pixel/.test(name+' '+p))return false;if(domain==='audio'&&!/audio|pcm|volume|mute|avsync/.test(name+' '+p))return false;if(priority==='critical'&&d.priority!=='critical'&&!/error|fatal|warn/.test(d.level))return false;if(priority==='error'&&!/error|fatal/.test(d.level))return false;return true}
            ['trace','attempt','domain','priority'].forEach(id=>diagEl(id).onchange=render);
            function diagOptions(id,values,label){const el=diagEl(id),selected=el.value,all=Array.from(values).slice(-100);if(selected&&!all.includes(selected))all.push(selected);const signature=all.join('|');if(el.dataset.signature===signature)return;el.dataset.signature=signature;el.textContent='';const first=document.createElement('option');first.value='';first.textContent=label;el.appendChild(first);all.forEach(value=>{const option=document.createElement('option');option.value=value;option.textContent=value;el.appendChild(option)});el.value=selected}
            function diagFilters(){const traces=new Set(),attempts=new Set();raw.split('\\n').forEach(line=>{const r=parse(line);if(r.tag!=='av-diag')return;try{const d=JSON.parse(r.msg);if(d.trace&&d.trace!=='none')traces.add(d.trace);if(d.attemptId)attempts.add(String(d.attemptId))}catch(e){}});diagOptions('trace',traces,'所有播放记录');diagOptions('attempt',attempts,'所有尝试')}
            async function diagStatus(){try{const r=await fetch('/debug/diag/status',{cache:'no-store'}),j=await r.json();diagEl('status').textContent=(j.engine||'暂无播放')+(j.deepRemainingMs>0?' · 深度统计剩余 '+Math.ceil(j.deepRemainingMs/1000)+' 秒':' · 标准记录');diagFilters()}catch(e){}setTimeout(diagStatus,3000)}diagStatus();
            """;
    }

    private String json(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    private String escape(String text) {
        if (TextUtils.isEmpty(text)) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}

#!/usr/bin/env python3
"""Render the actual Java-generated log page and check it with an existing Playwright install."""

import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME", ""))
    parser.add_argument("--playwright", help="Path to an installed Playwright module; omit to generate only")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    java = Path(args.java_home) / "bin"
    source = (root / "app/src/main/java/com/fongmi/android/tv/server/process/DebugLogs.java").read_text()
    methods = source[source.index("    private String html() {"):source.rindex("}")]
    rows = [
        "quickjs: [DEBUG] initialized",
        "quickjs: [INFO] no error occurred",
        "quickjs: [WARN] careful",
        "quickjs: [ERROR] explicit failure",
        "python-spider: [INFO] module-import-output",
        "python-spider: [ERROR] ERROR:broken",
        "python-spider: [STDERR] ordinary stderr",
        'python-spider: [INFO] <img src=x onerror="window.injected=true">',
        "webview-console: original console remains",
        'av-diag: {"schemaVersion":1,"event":"audio.output","level":"info","trace":"fixture","attemptId":1}',
    ] + [f"player: fixture row {i} " + "long-text-" * 10 for i in range(300)]
    raw = "".join("2026-09-15 16:00:00 [fixture] " + row + "\n" for row in rows)
    fixture = """
public class PageFixture {
    static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
    static class DebugLogStore {
        static boolean isEnabled() { return true; }
        static int version() { return 1; }
        static int bytes() { return 12345; }
        static String text() { return RAW; }
    }
    static class Server {
        static Server get() { return new Server(); }
        String getAddress(String path) { return "http://127.0.0.1:9978" + path; }
        String getAddress(boolean local) { return "http://192.0.2.1:9978"; }
    }
    public static void main(String[] args) throws java.io.IOException {
        System.out.write(new PageFixture().html().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
"""
    fixture += "    static final String RAW = " + json.dumps(raw, ensure_ascii=True) + ";\n"
    fixture += methods + "}\n"
    (out / "PageFixture.java").write_text(fixture)
    subprocess.run([str(java / "javac"), "-d", str(out), str(out / "PageFixture.java")], check=True)
    with (out / "logs.html").open("w") as target:
        subprocess.run([str(java / "java"), "-cp", str(out), "PageFixture"], stdout=target, check=True)
    assert "调试日志" in (out / "logs.html").read_text(encoding="utf-8"), "Fixture must preserve UTF-8 UI text"
    (out / "browser.cjs").write_text(BROWSER)
    print(f"Generated actual DebugLogs HTML: {out / 'logs.html'}", flush=True)
    if args.playwright:
        subprocess.run(["node", str(out / "browser.cjs"), args.playwright, str(out)], check=True)


BROWSER = r"""
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {chromium}=require(process.argv[2]);
const out=process.argv[3],html=fs.readFileSync(path.join(out,'logs.html'),'utf8');
(async()=>{
  const browser=await chromium.launch({headless:true,executablePath:process.env.WEBHTV_CHROMIUM||undefined});
  const metrics=[];
  try {
    for(const [width,height] of [[320,640],[390,844],[844,390],[1440,900]]) {
      const page=await browser.newPage({viewport:{width,height}});
      page.setDefaultTimeout(5000);
      const errors=[],requests=[];
      page.on('pageerror',e=>errors.push(e.message));
      await page.route('**/*',route=>{
        const req=route.request(),pathname=new URL(req.url()).pathname;
        requests.push({pathname,method:req.method()});
        if(pathname==='/debug/logs')return route.fulfill({contentType:'text/html',body:html});
        if(pathname==='/debug/stream')return route.fulfill({json:{enabled:true,runId:'fixture',generation:1,newestSeq:310,text:'',bytes:12345,health:{}}});
        if(pathname==='/debug/diag/status')return route.fulfill({json:{engine:'MPV',deepRemainingMs:0}});
        if(pathname==='/debug/diag/pair')return route.fulfill({json:{token:'fixture-token'}});
        if(req.method()==='POST'&&!req.headers().authorization?.includes('fixture-token'))return route.fulfill({status:403,json:{message:'请先使用 App 显示的配对码授权'}});
        return route.fulfill({json:{}});
      });
      await page.goto('http://webhtv.test/debug/logs');
      await page.waitForFunction(()=>document.querySelectorAll('#logs .entry').length===310);
      const g=await page.evaluate(()=>{
        const rect=q=>{const r=document.querySelector(q).getBoundingClientRect();return{x:r.x,y:r.y,right:r.right,bottom:r.bottom,width:r.width,height:r.height}};
        const tabs=document.querySelector('.tabs');
        return{header:rect('.console-head'),actions:rect('.primary-actions'),brand:rect('.brand'),search:rect('#filter'),pause:rect('#pause'),tools:rect('#tools-open'),
          pageWidth:document.documentElement.scrollWidth,tabYs:[...document.querySelectorAll('.chip')].map(e=>e.getBoundingClientRect().y),
          tabWidth:tabs.clientWidth,tabScrollWidth:tabs.scrollWidth,searchHidden:document.getElementById('search-panel').hidden};
      });
      console.log('layout',width,JSON.stringify(g));
      assert(g.pageWidth<=width,'page overflow');
      assert(g.header.height<=(width<800?100:85),'default header is not two compact rows');
      assert(g.brand.right<=g.actions.x,'brand overlaps primary actions');
      assert(g.actions.right<=width&&g.pause.right<=g.tools.x,'primary actions overlap');
      assert(g.pause.height>=(width<800?44:40),'touch target too small');
      assert.equal(new Set(g.tabYs).size,1,'category tabs wrap');
      if(width<800){assert(g.searchHidden);assert(g.tabScrollWidth>g.tabWidth)}
      else {assert(!g.searchHidden);assert(g.search.width<=260,'desktop search too wide')}
      await page.screenshot({path:path.join(out,'logs-'+width+'.png')});
      await page.evaluate(()=>scrollTo(0,1200));
      await page.waitForFunction(()=>Math.abs(document.querySelector('.console-head').getBoundingClientRect().y)<1);
      await page.locator('#tools-open').click();
      await page.waitForFunction(()=>document.getElementById('log-drawer').open);
      const bounds=await page.locator('#log-drawer').boundingBox();
      assert(bounds.x>=0&&bounds.y>=0&&bounds.x+bounds.width<=width+1&&bounds.y+bounds.height<=height+1,'drawer outside viewport');
      assert.equal(await page.locator('[role=tabpanel]:visible').count(),1);
      await page.locator('#tool-tab-filters').focus();
      await page.keyboard.press('End');
      assert.equal(await page.locator('#tool-tab-utilities').getAttribute('aria-selected'),'true');
      await page.locator('#info-toggle').click();
      assert(await page.locator('#address-panel').isVisible());
      assert(await page.locator('#info-toggle').evaluate(e=>e.previousElementSibling.getAttribute('href')==='/debug/disable'));
      await page.evaluate(()=>{
        const nodes=[...document.querySelectorAll('#log-drawer button,#log-drawer a[href],#log-drawer input,#log-drawer select')].filter(e=>!e.disabled&&e.tabIndex>=0&&e.getClientRects().length);
        nodes[nodes.length-1].focus();
      });
      await page.keyboard.press('Tab');
      assert(await page.evaluate(()=>document.getElementById('log-drawer').contains(document.activeElement)),'focus escaped modal');
      await page.keyboard.press('Escape');
      await page.waitForFunction(()=>!document.body.classList.contains('drawer-open'));
      assert.equal(await page.evaluate(()=>document.activeElement.id),'tools-open');
      await page.locator('[data-mode=console]').click();
      assert.equal(await page.locator('#logs .entry').count(),9);
      assert.equal(await page.locator('#logs .entry.err').count(),2);
      assert.equal(await page.locator('#logs img').count(),0);
      assert.equal(await page.evaluate(()=>!!window.injected),false);
      await page.locator('[data-mode=error]').click();
      assert.equal(await page.locator('#logs .entry').count(),2);
      await page.locator('[data-mode=console]').click();
      if(width<800)await page.locator('#search-toggle').click();
      await page.locator('#filter').fill('MODULE-IMPORT');
      assert.equal(await page.locator('#logs .entry').count(),1);
      assert.equal(await page.locator('#filter-count').textContent(),'1');
      if(width<800){await page.locator('#search-hide').click();assert(await page.locator('#search-toggle').evaluate(e=>e.classList.contains('on')))}
      await page.locator('#pause').click();
      const before=requests.filter(r=>r.pathname==='/debug/stream').length;
      await page.evaluate(()=>poll());
      assert.equal(requests.filter(r=>r.pathname==='/debug/stream').length,before,'pause still polls logs');
      await page.locator('#tools-open').click();
      await page.locator('#tool-tab-filters').click();
      await page.locator('#simple').check();
      assert.equal(await page.locator('#logs .rawline').evaluate(e=>getComputedStyle(e).display),'none');
      await page.locator('#filter-reset').click();
      assert.equal(await page.locator('#logs .entry').count(),310);
      await page.locator('#diag-domain').selectOption('audio');
      assert.equal(await page.locator('#logs .entry').count(),1);
      await page.locator('#tool-tab-diagnostics').click();
      await page.screenshot({path:path.join(out,'drawer-'+width+'.png')});
      await page.locator('#diag-mark').click();
      await page.waitForFunction(()=>document.getElementById('diag-feedback').textContent.includes('配对'));
      await page.locator('#diag-code').fill('123456');
      await page.locator('#diag-pair').click();
      await page.waitForFunction(()=>document.getElementById('diag-feedback').textContent.includes('已配对'));
      await page.locator('#tools-close').click();
      await page.waitForFunction(()=>!document.getElementById('log-drawer').open);
      if(width===1440){
        await page.evaluate(()=>document.getElementById('log-drawer').showModal=undefined);
        await page.locator('#tools-open').click();
        assert.equal(await page.locator('main').getAttribute('aria-hidden'),'true');
        await page.keyboard.press('Escape');
        await page.waitForFunction(()=>!document.body.classList.contains('drawer-open'));
        assert.equal(await page.locator('main').getAttribute('aria-hidden'),null);
      }
      assert.deepEqual(errors,[],'JavaScript errors');
      metrics.push({width,height,headerHeight:g.header.height,searchHidden:g.searchHidden,drawer:bounds});
      await page.close();
    }
    fs.writeFileSync(path.join(out,'browser-result.json'),JSON.stringify({passed:true,metrics},null,2));
    console.log('PASS: actual Java HTML, four viewports, two-row fixed header, touch targets, horizontal categories, Console severity, search/pause, modal bounds/focus/Escape, tabs, pairing, filters and legacy dialog fallback');
  } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
"""


if __name__ == "__main__":
    main()

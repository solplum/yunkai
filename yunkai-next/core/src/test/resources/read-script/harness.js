// 读脚本前置脚本的测试台:用一套极简的假 DOM(只有测试用到的接口)装上前置脚本,逐条跑用例,把结果按 JSON 打印出来。
// 用法:node harness.js <前置脚本> <用例 JSON>;用例为 [{name, code, args?, resultChars?}]。
'use strict';
const fs = require('fs');
const prelude = fs.readFileSync(process.argv[2], 'utf8');
const cases = JSON.parse(fs.readFileSync(process.argv[3], 'utf8'));
const write = process.stdout.write.bind(process.stdout);
const stringify = JSON.stringify;

const log = { mutations: 0, clicks: 0, navigations: 0, requests: [] };

class EventTarget {}
class Node extends EventTarget {
  constructor() { super(); this._kids = []; this._attrs = {}; this.parentNode = null; this._text = null; }
  get childNodes() { return new NodeList(this._kids); }
  get textContent() { return this._text != null ? this._text : this._kids.map((k) => k.textContent).join(''); }
  set textContent(v) { log.mutations++; this._text = String(v); this._kids = []; }
  appendChild(c) { log.mutations++; this._kids.push(c); c.parentNode = this; return c; }
  removeChild(c) { log.mutations++; this._kids = this._kids.filter((k) => k !== c); return c; }
  contains(o) { return o === this || this._kids.some((k) => k.contains(o)); }
  hasChildNodes() { return this._kids.length > 0; }
  _all() { const out = []; for (const k of this._kids) { out.push(k); out.push(...k._all()); } return out; }
  _match(sel) { return this._all().filter((e) => e._is(sel)); }
}
// 和浏览器一样,查找方法在 Element、Document 上,不在 Node 上
const finders = {
  querySelectorAll(sel) { return new NodeList(this._match(sel)); },
  querySelector(sel) { return this._match(sel)[0] || null; },
};
class Element extends Node {
  constructor(tag, attrs, text) { super(); this.tagName = tag.toUpperCase(); Object.assign(this._attrs, attrs || {}); if (text != null) this._text = text; }
  _is(sel) {
    for (const part of sel.split(',').map((s) => s.trim())) {
      const m = /^([a-z*]+)?(?:\[([a-z-]+)(?:([\^]?=)([^\]]*))?\])?$/.exec(part);
      if (!m) throw new Error('bad selector ' + part);
      if (m[1] && m[1] !== '*' && m[1] !== this.tagName.toLowerCase()) continue;
      if (m[2] && !(m[2] in this._attrs)) continue;
      return true;
    }
    return false;
  }
  getAttribute(n) { return n in this._attrs ? this._attrs[n] : null; }
  setAttribute(n, v) { log.mutations++; this._attrs[n] = String(v); }
  hasAttribute(n) { return n in this._attrs; }
  click() { log.clicks++; }
  focus() { log.mutations++; }
  cloneNode(deep) {
    const C = this.constructor;
    const c = new C(this.tagName.toLowerCase(), Object.assign({}, this._attrs), this._text);
    if (deep) for (const k of this._kids) { const kc = k.cloneNode(true); c._kids.push(kc); kc.parentNode = c; }
    return c;
  }
  get children() { return new NodeList(this._kids); }
  get innerHTML() { return this._text != null ? this._text : this._kids.map((k) => k.outerHTML).join(''); }
  set innerHTML(v) { log.mutations++; this._text = String(v); }
  get outerHTML() {
    const t = this.tagName.toLowerCase();
    const a = Object.keys(this._attrs).map((k) => ' ' + k + '="' + this._attrs[k] + '"').join('');
    return '<' + t + a + '>' + this.innerHTML + '</' + t + '>';
  }
}
Object.assign(Element.prototype, finders);
class HTMLElement extends Element {}
class HTMLInputElement extends HTMLElement {
  get type() { return this._attrs.type || 'text'; }
  get value() { return this._value != null ? this._value : (this._attrs.value || ''); }
  set value(v) { log.mutations++; this._value = String(v); }
}
class HTMLTextAreaElement extends HTMLElement {}
class Attr {}
class NodeList {
  constructor(a) { a.forEach((x, i) => { this[i] = x; }); this.length = a.length; }
  item(i) { return this[i] || null; }
}
NodeList.prototype[Symbol.iterator] = Array.prototype.values;
NodeList.prototype.forEach = Array.prototype.forEach;
NodeList.prototype.values = Array.prototype.values;
class Document extends Node {
  constructor() { super(); this._title = 'Shop'; }
  get title() { return this._title; }
  set title(v) { log.mutations++; this._title = v; }
  get body() { return this._kids[0]; }
  get cookie() { return 'sid=secret-cookie'; }
  createElement(t) { return new HTMLElement(t); }
}

Object.assign(Document.prototype, finders);
const doc = new Document();
const body = new HTMLElement('body');
doc._kids.push(body);
const add = (parent, el) => { parent._kids.push(el); el.parentNode = parent; return el; };
const list = add(body, new HTMLElement('ul'));
add(list, new HTMLElement('li', { 'data-id': '1' }, 'apple'));
add(list, new HTMLElement('li', { 'data-id': '2' }, 'pear'));
const form = add(body, new HTMLElement('form'));
add(form, new HTMLInputElement('input', { type: 'password', value: 'hunter2' }));
add(form, new HTMLInputElement('input', { type: 'hidden', name: 'csrf', value: 'tok-123' }));
add(form, new HTMLInputElement('input', { type: 'text', name: 'q', value: 'shoes' }));

class Location {
  constructor() { this.href = 'https://shop.test/list'; this.origin = 'https://shop.test'; }
  assign() { log.navigations++; }
  replace() { log.navigations++; }
}
const location = new Location();
Object.defineProperty(location, 'toString', { value: function () { return this.href; } });
Object.defineProperty(location, 'valueOf', { value: function () { return this; } });

// 会发请求的页面接口:真的被调到就记一笔
class XMLHttpRequest { constructor() { log.requests.push({ via: 'xhr' }); } open(m, u) { log.requests.push({ via: 'xhr', method: m, url: u }); } send() {} }
class WebSocket { constructor(u) { log.requests.push({ via: 'ws', url: u }); } }
class EventSource { constructor(u) { log.requests.push({ via: 'sse', url: u }); } }
class Image { constructor() { log.requests.push({ via: 'img' }); } }
const navigator = { userAgent: 'test', sendBeacon(u) { log.requests.push({ via: 'beacon', url: u }); return true; } };

Object.assign(globalThis, {
  XMLHttpRequest, WebSocket, EventSource, Image,
  Node, Element, HTMLElement, HTMLInputElement, HTMLTextAreaElement, Attr, NodeList, Document, EventTarget,
  document: doc, location,
  getComputedStyle() { return { display: 'block' }; },
  open() { log.navigations++; },
  fetch: async (url, opts) => {
    log.requests.push({ url, method: opts && opts.method, credentials: opts && opts.credentials, redirect: opts && opts.redirect });
    // 不跟随跳转时浏览器给的是 opaqueredirect 响应
    if (url.endsWith('/moved')) return { type: 'opaqueredirect', status: 0, ok: false, url: '', body: null, headers: new Headers() };
    return new Response(stringify({ items: [1, 2], token: 'abc' }), { headers: { 'content-type': 'application/json' } });
  },
});

// Node 自带的 navigator 只有 getter:换成假的
Object.defineProperty(globalThis, 'navigator', { value: navigator, configurable: true, writable: true });

(0, eval)(prelude);
const run = globalThis.__mikaReadRun;

(async () => {
  const out = {};
  for (const c of cases) {
    const before = { mutations: log.mutations, clicks: log.clicks, navigations: log.navigations, requests: log.requests.length };
    const lim = { resultChars: c.resultChars || 5000, sleepMs: 50 };
    let r;
    try { r = await run(c.code, c.args == null ? null : c.args, lim); } catch (e) { r = { ok: false, error: 'harness', message: String(e) }; }
    out[c.name] = Object.assign({}, r, {
      mutations: log.mutations - before.mutations, clicks: log.clicks - before.clicks,
      navigations: log.navigations - before.navigations, requests: log.requests.slice(before.requests),
    });
  }
  write(stringify(out));
})();

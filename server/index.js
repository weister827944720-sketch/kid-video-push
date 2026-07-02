import http from 'node:http';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { URL } from 'node:url';
import { fileURLToPath } from 'node:url';

const port = process.env.PORT || 3000;
const appDir = path.dirname(fileURLToPath(import.meta.url));
const dataFile = path.join(appDir, 'videos.json');
const domDebugFile = path.join(appDir, 'dom-debug.json');
const adminPassword = process.env.ADMIN_PASSWORD || '1234';

const server = http.createServer(async (req, res) => {
  try {
    setCorsHeaders(res);

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    const requestUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'GET' && requestUrl.pathname === '/') {
      sendHtml(res, 200, renderHome(await readVideos(), ''));
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/admin') {
      if (isAdminAuthed(req)) {
        sendHtml(res, 200, renderAdmin(await readVideos(), ''));
      } else {
        sendHtml(res, 200, renderAdminLogin(''));
      }
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/admin/login') {
      const form = await readFormBody(req);
      if (String(form.password || '') === adminPassword) {
        res.setHeader('Set-Cookie', 'kid_admin=1; HttpOnly; SameSite=Lax; Path=/; Max-Age=86400');
        redirect(res, '/admin');
      } else {
        sendHtml(res, 403, renderAdminLogin('密码错误'));
      }
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/admin/delete') {
      if (!isAdminAuthed(req)) {
        sendHtml(res, 403, renderAdminLogin('请先输入管理密码'));
        return;
      }
      const form = await readFormBody(req);
      const id = String(form.id || '');
      const videos = await readVideos();
      const next = videos.filter((item) => item.id !== id);
      await writeVideos(next);
      sendHtml(res, 200, renderAdmin(next, videos.length === next.length ? '没有找到要删除的链接' : '已删除'));
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/admin/logout') {
      res.setHeader('Set-Cookie', 'kid_admin=; HttpOnly; SameSite=Lax; Path=/; Max-Age=0');
      redirect(res, '/admin');
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/push') {
      const form = await readFormBody(req);
      const rawText = String(form.text || '').trim();
      const item = await addVideoFromText(rawText);
      sendHtml(res, 200, renderHome(await readVideos(), `已保存：${item.shareUrl}`));
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/api/videos') {
      res.setHeader('Cache-Control', 'no-store');
      sendJson(res, 200, await readVideos());
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/api/videos') {
      const body = await readJsonBody(req);
      const rawText = String(body?.text || body?.shareText || body?.shareUrl || '').trim();
      const item = await addVideoFromText(rawText, String(body?.title || '视频分享链接').trim());
      sendJson(res, 201, item);
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/debug/dom') {
      const body = await readJsonBody(req);
      const entry = {
        receivedAt: new Date().toISOString(),
        ...body
      };
      await fs.writeFile(domDebugFile, `${JSON.stringify(entry, null, 2)}\n`, 'utf8');
      sendJson(res, 201, { ok: true, savedAt: entry.receivedAt });
      return;
    }

    if (req.method === 'GET' && requestUrl.pathname === '/debug/dom') {
      const data = await readDomDebug();
      if (requestUrl.searchParams.get('raw') === '1') {
        sendJson(res, 200, data || {});
      } else {
        sendHtml(res, 200, renderDomDebug(data));
      }
      return;
    }

    if (req.method === 'DELETE' && requestUrl.pathname.startsWith('/api/videos/')) {
      const id = decodeURIComponent(requestUrl.pathname.slice('/api/videos/'.length));
      const videos = await readVideos();
      const next = videos.filter((item) => item.id !== id);
      await writeVideos(next);
      sendJson(res, 200, { deleted: videos.length - next.length });
      return;
    }

    sendJson(res, 404, { error: 'not found' });
  } catch (error) {
    if (req.url === '/' || req.url === '/push') {
      sendHtml(res, 400, renderHome(await safeReadVideos(), `保存失败：${error.message || 'unknown error'}`));
    } else {
      sendJson(res, 400, { error: error.message || 'bad request' });
    }
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`Kid video push server listening on http://0.0.0.0:${port}`);
});

async function addVideoFromText(rawText, title = '视频分享链接') {
  const shareUrl = extractSupportedUrl(rawText);
  if (!shareUrl) throw new Error('没有找到抖音或哔哩哔哩链接');

  const videos = await readVideos();
  const existing = videos.find((item) => item.shareUrl === shareUrl);
  if (existing) return existing;

  const item = {
    id: new Date().toISOString().replace(/[-:.TZ]/g, ''),
    source: detectSource(shareUrl),
    title,
    shareUrl,
    status: 'link-only',
    videoUrl: null,
    createdAt: new Date().toISOString()
  };

  videos.unshift(item);
  await writeVideos(videos);
  return item;
}

function extractDouyinUrl(text) {
  const source = String(text || '');
  const shortMatch = source.match(/https?:\/\/v\.douyin\.com\/[^\s，,。]+\//i);
  if (shortMatch) return shortMatch[0];

  const videoMatch = source.match(/https?:\/\/(?:www\.)?douyin\.com\/video\/\d+/i);
  if (videoMatch) return videoMatch[0];

  const anyDouyin = source.match(/https?:\/\/[^\s，,。]*douyin\.com\/[^\s，,。]*/i);
  return anyDouyin ? anyDouyin[0].replace(/[，,。\s]+$/g, '') : null;
}

function extractBilibiliUrl(text) {
  const source = String(text || '');
  const videoMatch = source.match(/https?:\/\/(?:www\.|m\.)?bilibili\.com\/[^\s，,。]*/i);
  if (videoMatch) return videoMatch[0].replace(/[，,。\s]+$/g, '');

  const shortMatch = source.match(/https?:\/\/b23\.tv\/[^\s，,。]*/i);
  return shortMatch ? shortMatch[0].replace(/[，,。\s]+$/g, '') : null;
}

function extractSupportedUrl(text) {
  return extractDouyinUrl(text) || extractBilibiliUrl(text);
}

function detectSource(url) {
  const source = String(url || '').toLowerCase();
  if (source.includes('douyin.com')) return 'douyin';
  if (source.includes('bilibili.com') || source.includes('b23.tv')) return 'bilibili';
  return 'unknown';
}

function renderHome(videos, message) {
  const rows = videos.map((item) => `
    <li>
      <div class="url">${escapeHtml(item.shareUrl)}</div>
      <div class="meta">${escapeHtml(item.source || 'unknown')} · ${escapeHtml(item.createdAt || '')}</div>
    </li>
  `).join('');

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>聚合视频推送后台</title>
  <style>
    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #111; color: #f5f5f5; }
    main { max-width: 760px; margin: 0 auto; padding: 24px; }
    h1 { font-size: 24px; }
    textarea { width: 100%; min-height: 140px; box-sizing: border-box; border-radius: 12px; border: 1px solid #444; background: #1b1b1b; color: #fff; padding: 14px; font-size: 16px; }
    button { margin-top: 12px; width: 100%; border: 0; border-radius: 999px; padding: 14px 18px; font-size: 17px; font-weight: 700; background: #1d9bf0; color: #fff; }
    a { color: #8ecbff; }
    .message { margin: 14px 0; padding: 12px; border-radius: 10px; background: #173b22; color: #9dffb2; }
    ul { padding: 0; list-style: none; }
    li { margin: 10px 0; padding: 12px; border-radius: 10px; background: #1d1d1d; }
    .url { word-break: break-all; }
    .meta { margin-top: 6px; color: #999; font-size: 13px; }
  </style>
</head>
<body>
  <main>
    <h1>聚合视频推送后台</h1>
    <p><a href="/debug/dom">查看 WebView DOM 调试数据</a></p>
    <form method="post" action="/push">
      <textarea name="text" placeholder="粘贴抖音或哔哩哔哩分享文字，例如：https://v.douyin.com/xxxx/ 或 https://www.bilibili.com/video/BV... 或 https://b23.tv/xxxx"></textarea>
      <button type="submit">推送到平板</button>
    </form>
    ${message ? `<div class="message">${escapeHtml(message)}</div>` : ''}
    <h2>已推送 ${videos.length} 条</h2>
    <ul>${rows}</ul>
  </main>
</body>
</html>`;
}

function renderAdminLogin(message) {
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>管理登录</title>
  <style>
    body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #111; color: #fff; }
    main { width: min(440px, 88vw); }
    h1 { font-size: 24px; }
    input { width: 100%; box-sizing: border-box; border-radius: 12px; border: 1px solid #444; background: #1b1b1b; color: #fff; padding: 14px; font-size: 18px; }
    button { margin-top: 14px; width: 100%; border: 0; border-radius: 999px; padding: 14px 18px; font-size: 17px; font-weight: 700; background: #1d9bf0; color: #fff; }
    .message { margin: 14px 0; padding: 12px; border-radius: 10px; background: #401b1b; color: #ffb4b4; }
    a { color: #8ecbff; }
  </style>
</head>
<body>
  <main>
    <h1>管理/删除视频连接</h1>
    ${message ? `<div class="message">${escapeHtml(message)}</div>` : ''}
    <form method="post" action="/admin/login">
      <input name="password" type="password" inputmode="numeric" placeholder="请输入管理密码" autocomplete="current-password" />
      <button type="submit">进入管理</button>
    </form>
    <p><a href="/">返回推送后台</a></p>
  </main>
</body>
</html>`;
}

function renderAdmin(videos, message) {
  const rows = videos.map((item) => `
    <li>
      <div class="title">${escapeHtml(item.title || '视频分享链接')}</div>
      <div class="meta">${escapeHtml(item.source || 'unknown')} · ${escapeHtml(item.createdAt || '')}</div>
      <div class="url">${escapeHtml(item.shareUrl || '')}</div>
      <form method="post" action="/admin/delete" onsubmit="return confirm('确定删除这个视频连接？');">
        <input type="hidden" name="id" value="${escapeHtml(item.id || '')}" />
        <button class="delete" type="submit">删除此链接</button>
      </form>
    </li>
  `).join('');

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>管理视频连接</title>
  <style>
    body { margin: 0; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; background: #111; color: #f5f5f5; }
    main { max-width: 820px; margin: 0 auto; padding: 22px; }
    h1 { font-size: 24px; }
    a { color: #8ecbff; }
    .message { margin: 14px 0; padding: 12px; border-radius: 10px; background: #173b22; color: #9dffb2; }
    ul { padding: 0; list-style: none; }
    li { margin: 12px 0; padding: 14px; border-radius: 12px; background: #1d1d1d; }
    .title { font-size: 17px; font-weight: 700; }
    .url { margin-top: 8px; word-break: break-all; color: #ddd; }
    .meta { margin-top: 6px; color: #999; font-size: 13px; }
    .delete { margin-top: 12px; width: 100%; border: 0; border-radius: 999px; padding: 12px 16px; font-size: 16px; font-weight: 700; background: #d33; color: #fff; }
    .toolbar { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
    .logout button { border: 1px solid #444; border-radius: 999px; padding: 8px 12px; background: #222; color: #fff; }
  </style>
</head>
<body>
  <main>
    <h1>管理/删除视频连接</h1>
    <div class="toolbar"><a href="/">返回推送后台</a><form class="logout" method="post" action="/admin/logout"><button type="submit">退出管理</button></form></div>
    ${message ? `<div class="message">${escapeHtml(message)}</div>` : ''}
    <h2>共 ${videos.length} 条</h2>
    <ul>${rows}</ul>
  </main>
</body>
</html>`;
}

function renderDomDebug(data) {
  const body = data ? JSON.stringify(data, null, 2) : '还没有采集到 DOM。安装新版 APK 后打开一个视频页面，等待几秒再刷新这里。';
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>WebView DOM 调试</title>
  <style>
    body { margin: 0; background: #111; color: #eee; font-family: ui-monospace, SFMono-Regular, Consolas, monospace; }
    main { padding: 20px; }
    a { color: #8ecbff; }
    pre { white-space: pre-wrap; word-break: break-word; background: #1d1d1d; padding: 16px; border-radius: 12px; }
  </style>
</head>
<body>
  <main>
    <p><a href="/">返回后台</a> | <a href="/debug/dom?raw=1">查看 JSON</a></p>
    <pre>${escapeHtml(body)}</pre>
  </main>
</body>
</html>`;
}

function escapeHtml(value) {
  return String(value).replace(/[&<>"]/g, (char) => ({
    '&': '&amp;',
    '<': '&lt;',
    '>': '&gt;',
    '"': '&quot;'
  }[char]));
}

function setCorsHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function parseCookies(req) {
  return Object.fromEntries(String(req.headers.cookie || '').split(';').map((part) => {
    const index = part.indexOf('=');
    if (index < 0) return ['', ''];
    return [part.slice(0, index).trim(), decodeURIComponent(part.slice(index + 1).trim())];
  }).filter(([key]) => key));
}

function isAdminAuthed(req) {
  return parseCookies(req).kid_admin === '1';
}

function redirect(res, location) {
  res.writeHead(303, { Location: location });
  res.end();
}

function sendJson(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}

function sendHtml(res, status, html) {
  res.writeHead(status, { 'Content-Type': 'text/html; charset=utf-8' });
  res.end(html);
}

async function readJsonBody(req) {
  const text = await readTextBody(req);
  return text ? JSON.parse(text) : {};
}

async function readFormBody(req) {
  const text = await readTextBody(req);
  const params = new URLSearchParams(text);
  return Object.fromEntries(params.entries());
}

async function readTextBody(req) {
  const chunks = [];
  for await (const chunk of req) chunks.push(chunk);
  return Buffer.concat(chunks).toString('utf8');
}

async function safeReadVideos() {
  try {
    return await readVideos();
  } catch {
    return [];
  }
}

async function readDomDebug() {
  try {
    return JSON.parse(await fs.readFile(domDebugFile, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return null;
    throw error;
  }
}

async function readVideos() {
  try {
    return JSON.parse(await fs.readFile(dataFile, 'utf8'));
  } catch (error) {
    if (error.code === 'ENOENT') return [];
    throw error;
  }
}

async function writeVideos(videos) {
  await fs.writeFile(dataFile, `${JSON.stringify(videos, null, 2)}\n`, 'utf8');
}

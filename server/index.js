import http from 'node:http';
import { promises as fs } from 'node:fs';
import path from 'node:path';
import { URL } from 'node:url';

const port = process.env.PORT || 3000;
const dataFile = path.join(process.cwd(), 'videos.json');

const server = http.createServer(async (req, res) => {
  try {
    setCorsHeaders(res);

    if (req.method === 'OPTIONS') {
      res.writeHead(204);
      res.end();
      return;
    }

    const requestUrl = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);

    if (req.method === 'GET' && requestUrl.pathname === '/api/videos') {
      sendJson(res, 200, await readVideos());
      return;
    }

    if (req.method === 'POST' && requestUrl.pathname === '/api/videos') {
      const body = await readJsonBody(req);
      const shareUrl = String(body?.shareUrl || '').trim();
      const title = String(body?.title || '抖音分享链接').trim();

      if (!/^https?:\/\//i.test(shareUrl)) {
        sendJson(res, 400, { error: 'shareUrl must be an http(s) url' });
        return;
      }

      const videos = await readVideos();
      const existing = videos.find((item) => item.shareUrl === shareUrl);
      if (existing) {
        sendJson(res, 200, existing);
        return;
      }

      const item = {
        id: new Date().toISOString().replace(/[-:.TZ]/g, ''),
        source: 'douyin',
        title,
        shareUrl,
        status: 'link-only',
        videoUrl: null,
        createdAt: new Date().toISOString()
      };

      videos.unshift(item);
      await writeVideos(videos);
      sendJson(res, 201, item);
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
    sendJson(res, 500, { error: error.message || 'internal error' });
  }
});

server.listen(port, '0.0.0.0', () => {
  console.log(`Kid video push server listening on http://0.0.0.0:${port}`);
});

function setCorsHeaders(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,DELETE,OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');
}

function sendJson(res, status, value) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8' });
  res.end(JSON.stringify(value));
}

async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  return text ? JSON.parse(text) : {};
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

# Backend Bilibili Link Support Plan

## Goal

Update the backend paste/push service so the web form accepts both Douyin and Bilibili links. After the user submits a Bilibili link and the app captures DOM/debug data, handle Bilibili frontend playback/cleanup in a separate follow-up change.

## Current Findings

- The active web backend is `server/index.js`.
- The failure message `保存失败：没有找到抖音链接` comes from `server/index.js:addVideoFromText()`.
- Current backend extraction only calls `extractDouyinUrl(rawText)`.
- The Android app already has partial support for Bilibili URL recognition, but frontend playback cleanup for Bilibili is not implemented yet.
- `server/server.py` also exists, but it is not the source of the current web form error. It should optionally be kept consistent for future use.

## Supported Bilibili URL Forms

Add recognition for these forms:

- `https://www.bilibili.com/video/BV...`
- `https://m.bilibili.com/video/BV...`
- `https://b23.tv/...`

Keep existing Douyin support:

- `https://v.douyin.com/.../`
- `https://www.douyin.com/video/...`
- other `*.douyin.com/*` links

## Implementation Tasks

1. Update `server/index.js` link extraction.
   - Keep `extractDouyinUrl(text)`.
   - Add `extractBilibiliUrl(text)`.
   - Add `extractSupportedUrl(text)` that returns the first Douyin or Bilibili URL found.
   - Add `detectSource(url)` returning `douyin`, `bilibili`, or `unknown`.

2. Update `addVideoFromText()` in `server/index.js`.
   - Replace `extractDouyinUrl(rawText)` with `extractSupportedUrl(rawText)`.
   - Change error from `没有找到抖音链接` to `没有找到抖音或哔哩哔哩链接`.
   - Set `source` using `detectSource(shareUrl)` instead of hardcoded `douyin`.
   - Keep duplicate detection by exact `shareUrl`.

3. Update backend web UI text in `server/index.js`.
   - Page title/header can remain or be updated to neutral wording.
   - Change textarea placeholder from Douyin-only to Douyin/Bilibili examples.
   - Optional: display source beside each saved row for easier verification.

4. Optionally update `server/server.py` for consistency.
   - Add a small `detect_source(url)` helper.
   - Set item `source` based on URL instead of hardcoded `douyin`.
   - Default title can become `视频分享链接`.
   - This is optional because current web form is Node-backed.

5. Validation.
   - Test backend extraction locally, at minimum with:
     - `https://www.bilibili.com/video/BV1jv4y1R79G?spm_id_from=333.788.recommend_more_video.2&trackid=web_related_0.router-related-2589621-7nw4b.1782962663075.208&vd_source=e3c46733f557ded87e9d4032a214ee93`
     - `【信息茧房？原来减肥早有系统性解决方案了....-哔哩哔哩】 https://b23.tv/bqGfttj`
     - an existing Douyin short link
   - Verify `/push` no longer returns `没有找到抖音链接` for Bilibili input.
   - Verify `/api/videos` includes Bilibili items with `source: "bilibili"`.
   - Verify Android app receives the Bilibili item in the aggregate list.

## Explicitly Out Of Scope For This Change

- Bilibili WebView DOM cleanup/fullscreen playback optimization.
- Bilibili-specific auto-play hiding logic.
- Bilibili login UX changes in the Android app.
- Changing the Android tab filtering behavior.

These should be handled after the user successfully submits a Bilibili link and the app captures DOM/debug data for analysis.

## Risks And Notes

- `b23.tv` short links may redirect. The backend should store the short link first; resolving redirects is not required for this step.
- Exact duplicate detection means a `b23.tv` short link and its resolved `bilibili.com/video/...` URL may be stored as separate items. This is acceptable for the first backend fix unless canonicalization is explicitly requested later.
- Existing stored Douyin items with `source: "douyin"` should remain unchanged.

# Clipboard Auto-Push And Admin Delete Plan

## Goal

Improve the current App + backend workflow so daily video pushing does not require manually pasting links into the backend page, and add a password-gated backend management page for deleting saved video links.

## Decisions

- No new App is needed.
- The existing Android App will continue to handle clipboard/share text and push links to the existing backend.
- The backend management/delete UI will be a webpage served by `server/index.js`.
- The App `我的` tab will only provide an entry button to this backend management page.
- Password `1234` protects opening the management page, not every individual delete click.
- After password success, the backend sets a simple admin cookie and shows the list with delete buttons.
- Bilibili login remains in `我的`.
- Douyin login remains out of scope because mobile Douyin login is not useful here.

## Current Code Context

Android:

- `app/src/main/java/com/kidvideopush/app/MainActivity.java`
- App already reads clipboard on startup:
  - `readClipboardText()`
  - `extractSupportedUrl()`
  - `showPushMode()`
- App already supports direct share intent:
  - `Intent.ACTION_SEND`
- App currently shows `检测到视频链接\n正在推送给平板...` and success text `已推送到平板\n<link>`.
- App does not currently remember the last clipboard link it already pushed, so reopening with the same clipboard can trigger push mode again.
- `我的` currently renders a local HTML page with Bilibili login button only.

Backend:

- `server/index.js` is the active web backend.
- Current APIs:
  - `GET /api/videos`
  - `POST /api/videos`
  - `DELETE /api/videos/:id`
  - `GET /`
  - `POST /push`
  - `GET/POST /debug/dom`
- Video items include:
  - `id`
  - `source`
  - `title`
  - `shareUrl`
  - `createdAt`
- `title` may still be generic (`视频分享链接`) for many items; real author/title enrichment is out of scope for this change.

## Android Tasks

1. Add local last-pushed clipboard tracking.
   - Use `SharedPreferences`.
   - Store a key such as `lastPushedClipboardUrl`.
   - On startup:
     - Read clipboard.
     - Extract supported URL.
     - If no supported URL: call `showPlayerMode()`.
     - If URL equals saved `lastPushedClipboardUrl`: call `showPlayerMode()`.
     - If URL is new: call `showPushMode(clipboardText, true)` or equivalent.

2. Keep share-intent behavior separate.
   - `ACTION_SEND` should still push immediately.
   - It can also update `lastPushedClipboardUrl` on success if the extracted URL matches, but it should not block share-intent pushes merely because it matches the clipboard key.

3. Update push UX text.
   - Initial text: `检测到视频链接\n正在推送...`
   - Success text: `推送成功\n<link>`
   - Delay close after success: about `1500ms`.
   - Failure text: `推送失败\n<reason>` and do not auto-close unless existing behavior is intentionally kept.

4. Save the last pushed clipboard URL only after successful backend push.
   - Do not save on failure.
   - Do not save when no supported URL is found.

5. Add management entry in `我的` page.
   - Keep Bilibili login button.
   - Add a second button under it: `管理/删除视频连接`.
   - Link target: `${SERVER_URL}/admin`, concretely `http://n.dujiaoxian.online:35039/admin`.
   - The page is loaded inside the existing WebView.

6. Version bump.
   - Increment `versionCode` and `versionName` in `app/build.gradle.kts`.

## Backend Tasks (`server/index.js`)

1. Add admin password constant.
   - Use `const ADMIN_PASSWORD = process.env.ADMIN_PASSWORD || '1234';`

2. Add cookie parsing helper.
   - Parse `req.headers.cookie` into a simple object.
   - Admin auth cookie can be `kid_admin=1`.

3. Add admin auth helper.
   - `isAdminAuthed(req)` returns true when cookie has `kid_admin=1`.

4. Add routes.
   - `GET /admin`
     - If authenticated: render management list.
     - If not authenticated: render password form.
   - `POST /admin/login`
     - Read form body.
     - If `password === ADMIN_PASSWORD`:
       - Set cookie: `kid_admin=1; HttpOnly; SameSite=Lax; Path=/; Max-Age=86400`
       - Redirect to `/admin`.
     - Else render login page with error `密码错误`.
   - `POST /admin/delete`
     - Require `isAdminAuthed(req)`.
     - Read form body with `id`.
     - Delete matching item from `videos.json`.
     - Redirect back to `/admin`.
   - Optional: `POST /admin/logout`
     - Clear cookie and redirect to `/admin`.

5. Render admin login page.
   - Simple mobile-friendly form.
   - Password input.
   - Submit button.
   - Error display if wrong password.

6. Render admin list page.
   - Show each item with:
     - `source`
     - `title || '视频分享链接'`
     - `shareUrl`
     - `createdAt`
     - Delete button
   - Delete button submits POST `/admin/delete` with hidden `id`.
   - Include a confirmation prompt in the form button or inline JS if desired; if avoiding JS, make button label explicit: `删除此链接`.
   - Keep HTML escaping via existing `escapeHtml()`.

7. Keep existing `DELETE /api/videos/:id` unchanged.
   - Existing API remains for internal/API use.
   - New web admin deletion uses cookie-gated `POST /admin/delete`.

8. Optional backend consistency.
   - `server/server.py` does not need admin page unless it is actually deployed.
   - Do not spend effort on Python admin UI unless explicitly requested.

## Edge Cases

- Same clipboard link after successful push:
  - App should open player normally, not push and auto-close again.
- Backend duplicate link:
  - Existing backend returns existing item; App can treat this as success and store last pushed clipboard URL.
- Clipboard read denied or unavailable:
  - App should open player normally.
- Wrong admin password:
  - Show password page with error; do not reveal list.
- User directly hits `/admin/delete` without auth:
  - Return/redirect to login page, no deletion.
- Deleting missing ID:
  - Redirect back to admin with a neutral message or show deleted count; no crash.

## Validation

Android validation:

- Copy a Douyin link, open App:
  - Shows `检测到视频链接\n正在推送...`.
  - On success shows `推送成功`.
  - Closes after about 1.5s.
- Reopen App without changing clipboard:
  - Opens player normally; does not auto-close.
- Copy a different Bilibili link, open App:
  - Pushes successfully and closes.
- Share intent still pushes.
- `我的` tab shows:
  - Bilibili login button.
  - Management/delete button.

Backend validation:

- `GET /admin` without cookie shows password page.
- Wrong password does not show list.
- Password `1234` shows list.
- List displays source/title/link/time.
- Delete button removes item from `videos.json`.
- After deletion, `/api/videos` no longer returns that item.

## Deployment Notes

- The NAS uses `/docker/kid-video-push` as the live backend directory.
- After backend changes are pushed, the updated `server/index.js` must be copied/synced to `/docker/kid-video-push/index.js` on NAS and the container/service must be restarted.
- Android APK must be rebuilt and installed for the clipboard de-duplication and `我的` page button changes.

## Out Of Scope

- Extracting real author/title from Douyin or Bilibili pages.
- Bilibili/Douyin playback UI tuning.
- Multi-user accounts or strong security. Password/cookie auth is intentionally simple for personal NAS use.

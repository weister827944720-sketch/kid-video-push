# Kid Video Push

MVP 原型：家长从抖音分享链接到 Android App，App 上传到本地服务端，平板端刷新列表并用受控 WebView 播放。

## 服务端

Python 版本，推荐优先使用：

```bash
cd server
python server.py
```

Node.js 版本：

```bash
cd server
npm start
```

服务端默认监听 `http://0.0.0.0:3000`，数据保存在 `server/videos.json`。
Python 版只依赖 Python 标准库；Node.js 版只依赖 Node.js 内置模块，不需要安装 npm 依赖。

## Android App

```bash
./gradlew assembleDebug
```

模拟器默认服务器地址是 `http://10.0.2.2:3000`。

真机或平板需要把 App 首页的服务器地址改成电脑局域网 IP，例如 `http://192.168.1.10:3000`。

## 当前限制

- 当前版本是链接直推实验版，WebView 里通过 CSS/JS 隐藏干扰元素，不保证长期稳定。
- 正式版建议服务端解析并转存 MP4，然后平板端用原生播放器播放服务器视频地址。

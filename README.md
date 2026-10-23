# 电视机本地小代理（tvproxy）

给 [网页电视机](https://app.benfen.net/tv/) 补跨域头的本地小工具：
很多直播源本身是活的，只因为没给浏览器跨域许可（CORS）、或用的是 http，
在网页里播不了。这个小代理在**你自己的手机/电脑本地**运行，
电视机播台时把流先过它一遍——它负责拉流、补上跨域头再交给浏览器。
流只在你本机转一圈，不经过任何别人的服务器。

## 在手机上用（应用版，推荐）

下载 **本地代理.apk**（工具页 https://app.benfen.net/proxy/ 或本仓库 Releases 的 `apk-v1.0`）装上，点图标启动即可：它用安卓前台服务常驻，不会被系统冻结。启动后回电视机打开「本地代理」开关，看完在应用里或通知里点停止。

## 在手机上用（Termux，备选）

1. 装 **Termux**：在 F-Droid 里搜 Termux 安装（不要用 Google Play 里的旧版）。
2. 打开 Termux，粘贴这一整条回车——装环境、下载、启动一次走完：

   ```
   curl -sL https://app.benfen.net/proxy/install.sh | bash
   ```

3. 看到「电视机本地小代理已启动 ✓」就放着别关（可以切走，别划掉 Termux）。
4. 打开电视机 → 设置 → 「本地代理」打开开关（看到"已连接"就是通了）→ 回到频道点台。
5. 以后再看：在 Termux 里输入 `tvproxy` 回车即可（或用 Termux:Widget 桌面小部件点「电视机代理」）。不看了回 Termux 按 Ctrl+C 停掉。

## 在电脑上用（Windows，双击）

1. 到本仓库的 [Releases](https://github.com/benfennet/local-proxy/releases) 下载 `tvproxy.exe`。
2. 双击运行。第一次运行 Windows 可能弹安全提醒，点「更多信息 → 仍要运行」即可（程序源码就这一个文件，公开可查；提醒是因为没买代码签名证书）。
3. 它会自动打开电视机页面；在电视机设置里打开「本地代理」开关（看到"已连接"即通）。保持代理窗口开着，关掉就停。

没有 exe 或用 macOS/Linux：装有 Python 3 时，下载 `tvproxy.py` 运行 `python tvproxy.py`，效果一样。

## 它做了什么

- 只监听 `127.0.0.1:8899`，只有本机能访问，局域网和外网都进不来；
- 转发直播列表和分片，给每个响应补 `Access-Control-Allow-Origin` 等跨域头；
- 自动改写 m3u8 列表里的每一行地址，让分片也走代理；
- 纯 Python 标准库写的，全部代码就这一个文件，可以自己打开看。

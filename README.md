# 电视机本地小代理（tvproxy）

给 [网页电视机](https://app.benfen.net/tv/) 补跨域头的本地小工具：
很多直播源本身是活的，只因为没给浏览器跨域许可（CORS）、或用的是 http，
在网页里播不了。这个小代理在**你自己的手机/电脑本地**运行，
电视机播台时把流先过它一遍——它负责拉流、补上跨域头再交给浏览器。
流只在你本机转一圈，不经过任何别人的服务器。

## 在手机上用（Termux）

1. 装 **Termux**：在 F-Droid 里搜 Termux 安装（不要用 Google Play 里的旧版）。
2. 打开 Termux，粘贴这一整行回车（装 Python + 下载脚本）：

   ```
   pkg install -y python && curl -sL https://cdn.jsdelivr.net/gh/benfennet/local-proxy@main/tvproxy.py -o tvproxy.py
   ```

3. 启动代理（以后每次看电视前跑这一行）：

   ```
   python tvproxy.py
   ```

   看到「电视机本地小代理已启动」就放着别关窗口（可以切走，别划掉 Termux）。
4. 打开电视机 → 设置 → 「本地代理」打开开关（地址已预填 `http://127.0.0.1:8899`）→ 回到频道点台。
5. 不看了：回 Termux 按 Ctrl+C 停掉即可（或直接划掉 Termux）。

小提示：Termux 里先跑一次 `termux-wake-lock` 能减少看电视时被系统杀后台（没有这个命令就跳过，不影响使用）。

## 在电脑上用

装有 Python 3 即可：下载 `tvproxy.py` 后运行 `python tvproxy.py`，
然后在**同一台电脑的浏览器**里打开电视机并打开代理开关。

## 它做了什么

- 只监听 `127.0.0.1:8899`，只有本机能访问，局域网和外网都进不来；
- 转发直播列表和分片，给每个响应补 `Access-Control-Allow-Origin` 等跨域头；
- 自动改写 m3u8 列表里的每一行地址，让分片也走代理；
- 纯 Python 标准库写的，全部代码就这一个文件，可以自己打开看。

#!/data/data/com.termux/files/usr/bin/bash
# 电视机本地代理 · Termux 一键安装并启动
# 用法：在 Termux 里粘贴这一条回车，其余自动完成：
#   curl -sL https://app.benfen.net/proxy/install.sh | bash
echo "== 电视机本地代理 · 一键安装 =="
if ! command -v python >/dev/null 2>&1; then
  echo "第一次使用，正在安装 Python（只需这一次，请稍等）…"
  pkg install -y python || { echo "Python 安装失败，请检查网络后重新粘贴命令。"; exit 1; }
fi
echo "正在下载代理程序…"
curl -sL https://cdn.jsdelivr.net/gh/benfennet/local-proxy@main/tvproxy.py -o "$HOME/tvproxy.py" \
  || { echo "下载失败，请检查网络后重试。"; exit 1; }
# 快捷启动命令：以后在 Termux 里输入 tvproxy 即可启动
cat > "$PREFIX/bin/tvproxy" << 'EOS'
#!/data/data/com.termux/files/usr/bin/bash
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock
exec python "$HOME/tvproxy.py"
EOS
chmod +x "$PREFIX/bin/tvproxy"
# 给 Termux:Widget 放一个桌面快捷方式（装了才有用，没装也无妨）
mkdir -p "$HOME/.shortcuts"
cp "$PREFIX/bin/tvproxy" "$HOME/.shortcuts/电视机代理"
echo ""
echo "✓ 装好了。以后每次看电视前，在 Termux 里输入 tvproxy 回车就能启动；"
echo "  装了 Termux:Widget 的，桌面小部件里点「电视机代理」也行。"
echo "✓ 现在先帮你启动这一次：保持 Termux 开着，回电视机里打开「本地代理」开关。"
echo ""
exec tvproxy

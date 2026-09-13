"""Live view: repeatedly screencap the device and serve a self-refreshing page. It depends on
nothing but adb, which is all the container has - see start.sh."""
import http.server, socketserver, subprocess, threading, time

ADB = ["/sdk/platform-tools/adb", "-s", "emulator-5554"]
latest = {"png": b"", "at": 0.0}

def grab():
    while True:
        try:
            png = subprocess.run(ADB + ["exec-out", "screencap", "-p"],
                                 capture_output=True, timeout=20).stdout
            if png.startswith(b"\x89PNG"):
                latest["png"], latest["at"] = png, time.time()
        except Exception:
            pass
        time.sleep(1.0)

PAGE = b"""<!doctype html><title>android-agent</title>
<style>html,body{margin:0;background:#111;height:100%;display:flex;align-items:center;
justify-content:center;font:13px system-ui;color:#888}
img{max-height:98vh;max-width:98vw;border-radius:10px;box-shadow:0 0 40px #000}</style>
<img id=s><script>
const s=document.getElementById('s');
setInterval(()=>{s.src='/frame.png?t='+Date.now()},1000);s.src='/frame.png';
</script>"""

class H(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def do_GET(self):
        if self.path.startswith("/frame.png"):
            png = latest["png"]
            if not png:
                self.send_error(503); return
            self.send_response(200)
            self.send_header("Content-Type", "image/png")
            self.send_header("Content-Length", str(len(png)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers(); self.wfile.write(png)
        else:
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers(); self.wfile.write(PAGE)

threading.Thread(target=grab, daemon=True).start()
socketserver.ThreadingTCPServer.allow_reuse_address = True
socketserver.ThreadingTCPServer(("0.0.0.0", 6081), H).serve_forever()

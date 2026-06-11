#!/usr/bin/env python3
"""Claude Code RGB WiFi hook for ESP32-C3.

Extended from the serial version with WiFi (HTTP) support.
Communication priority: HTTP first, serial fallback.
Only uses Python stdlib - no pip dependencies on any platform.

mDNS auto-discovery: when CLAUDE_RGB_HOST is not set, the hook
automatically resolves claude-rgb.local via mDNS to find the ESP32.
No router lookup needed!

Environment variables:
  CLAUDE_RGB_HOST  - ESP32 IP address (optional, mDNS auto-discovery if unset)
  CLAUDE_RGB_PORT  - Serial port (fallback, same as original)
  CLAUDE_RGB_MODE  - auto | http | serial (default: auto)
  CLAUDE_RGB_LOG   - Optional log file path
"""
import argparse
import glob
import http.client
import json
import os
import socket
import sys
import time

IS_WINDOWS = sys.platform == "win32"

if IS_WINDOWS:
    import ctypes
    import ctypes.wintypes as wt

    try:
        import winreg
    except ImportError:
        winreg = None
else:
    import termios

from typing import Any, Dict, Optional


# --- Constants ---

if IS_WINDOWS:
    DEFAULT_PORT = "COM3"
else:
    DEFAULT_PORT = "/dev/cu.usbmodem1201"

DEFAULT_BAUD = 115200
DEFAULT_HOST = "192.168.4.1"
DEFAULT_HTTP_PORT = 80
HTTP_TIMEOUT_SEC = 1
MDNS_HOSTNAME = "claude-rgb.local"
# Cache resolved mDNS IP to avoid repeated lookups across hook invocations.
# Stored as module-level mutable so it persists within a single process run.
_mdns_cached_ip: Optional[str] = None

VALID_STATES = {
    "idle",
    "done",
    "running",
    "tool",
    "ask",
    "error",
}

PORT_PATTERNS_POSIX = [
    # macOS
    "/dev/cu.usbmodem*",
    "/dev/cu.usbserial*",
    "/dev/cu.wchusbserial*",
    "/dev/cu.SLAB_USBtoUART*",
    # Linux
    "/dev/ttyACM*",
    "/dev/ttyUSB*",
]


# ============================================================
# Windows serial via ctypes (kernel32.dll)
# ============================================================

if IS_WINDOWS:
    kernel32 = ctypes.windll.kernel32

    _GENERIC_READ = 0x80000000
    _GENERIC_WRITE = 0x40000000
    _OPEN_EXISTING = 3
    _INVALID_HANDLE_VALUE = ctypes.c_void_p(-1).value

    class _DCB(ctypes.Structure):
        _fields_ = [
            ("DCBlength", wt.DWORD),
            ("BaudRate", wt.DWORD),
            ("fFlags", wt.DWORD),
            ("wReserved", wt.WORD),
            ("XonLim", wt.WORD),
            ("XoffLim", wt.WORD),
            ("ByteSize", wt.BYTE),
            ("Parity", wt.BYTE),
            ("StopBits", wt.BYTE),
            ("XonChar", ctypes.c_char),
            ("XoffChar", ctypes.c_char),
            ("ErrorChar", ctypes.c_char),
            ("EofChar", ctypes.c_char),
            ("EvtChar", ctypes.c_char),
            ("wReserved1", wt.WORD),
        ]

    class _COMMTIMEOUTS(ctypes.Structure):
        _fields_ = [
            ("ReadIntervalTimeout", wt.DWORD),
            ("ReadTotalTimeoutMultiplier", wt.DWORD),
            ("ReadTotalTimeoutConstant", wt.DWORD),
            ("WriteTotalTimeoutMultiplier", wt.DWORD),
            ("WriteTotalTimeoutConstant", wt.DWORD),
        ]

    def _win_open_port(port: str):
        if port.startswith("COM") and len(port) > 4:
            win_port = f"\\\\.\\{port}"
        else:
            win_port = port

        handle = kernel32.CreateFileW(
            win_port,
            _GENERIC_READ | _GENERIC_WRITE,
            0,
            None,
            _OPEN_EXISTING,
            0,
            None,
        )
        if handle == _INVALID_HANDLE_VALUE or handle is None:
            return None
        return handle

    def _win_close_port(handle) -> None:
        kernel32.CloseHandle(handle)

    def _win_configure_port(handle, baud: int) -> bool:
        dcb = _DCB()
        dcb.DCBlength = ctypes.sizeof(_DCB)

        if not kernel32.GetCommState(handle, ctypes.byref(dcb)):
            return False

        dcb.BaudRate = baud
        dcb.ByteSize = 8
        dcb.Parity = 0
        dcb.StopBits = 0
        dcb.fFlags = 0x01

        if not kernel32.SetCommState(handle, ctypes.byref(dcb)):
            return False

        timeouts = _COMMTIMEOUTS()
        timeouts.ReadIntervalTimeout = 50
        timeouts.ReadTotalTimeoutMultiplier = 0
        timeouts.ReadTotalTimeoutConstant = 50
        timeouts.WriteTotalTimeoutMultiplier = 0
        timeouts.WriteTotalTimeoutConstant = 1000

        if not kernel32.SetCommTimeouts(handle, ctypes.byref(timeouts)):
            return False

        return True

    def _win_write_serial(state: str, port: str, baud: int) -> bool:
        handle = _win_open_port(port)
        if handle is None:
            log(f"failed to open port {port}")
            return False

        try:
            if not _win_configure_port(handle, baud):
                log(f"failed to configure port {port}")
                return False

            payload = f"STATE:{state}\n".encode("utf-8")
            written = wt.DWORD()

            kernel32.WriteFile(
                handle, payload, len(payload), ctypes.byref(written), None
            )
            time.sleep(0.04)
            kernel32.WriteFile(
                handle, payload, len(payload), ctypes.byref(written), None
            )

            log(f"sent STATE:{state} to {port}")
            return True

        finally:
            _win_close_port(handle)


# ============================================================
# Logging
# ============================================================

def log(message: str) -> None:
    log_path = os.environ.get("CLAUDE_RGB_LOG", "")
    if not log_path:
        return

    log_path = os.path.expanduser(log_path)

    try:
        os.makedirs(os.path.dirname(log_path), exist_ok=True)
        with open(log_path, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n")
    except Exception:
        pass


# ============================================================
# Serial port helpers (unchanged from original)
# ============================================================

def scan_ports() -> list[str]:
    if IS_WINDOWS:
        return _scan_ports_windows()
    return _scan_ports_posix()


def _scan_ports_posix() -> list[str]:
    ports: list[str] = []
    for pattern in PORT_PATTERNS_POSIX:
        ports.extend(glob.glob(pattern))
    return sorted(set(ports))


def _scan_ports_windows() -> list[str]:
    ports: list[str] = []

    if winreg is not None:
        try:
            key = winreg.OpenKey(
                winreg.HKEY_LOCAL_MACHINE,
                r"HARDWARE\DEVICEMAP\SERIALCOMM",
            )
            i = 0
            while True:
                try:
                    _, value, _ = winreg.EnumValue(key, i)
                    if isinstance(value, str) and value.startswith("COM"):
                        ports.append(value)
                    i += 1
                except OSError:
                    break
            winreg.CloseKey(key)
        except Exception:
            pass

    return sorted(ports)


def pick_serial_port(cli_port: Optional[str] = None) -> Optional[str]:
    if cli_port:
        return cli_port

    env_port = os.environ.get("CLAUDE_RGB_PORT")
    if env_port:
        return env_port

    if not IS_WINDOWS and os.path.exists(DEFAULT_PORT):
        return DEFAULT_PORT

    ports = scan_ports()
    if ports:
        return ports[0]

    return None


def baud_to_termios(baud: int) -> int:
    if baud == 9600:
        return termios.B9600
    if baud == 19200:
        return termios.B19200
    if baud == 38400:
        return termios.B38400
    if baud == 57600:
        return termios.B57600
    if baud == 115200:
        return termios.B115200
    return termios.B115200


def configure_serial(fd: int, baud: int) -> None:
    attrs = termios.tcgetattr(fd)

    attrs[0] &= ~(
        termios.IGNBRK
        | termios.BRKINT
        | termios.PARMRK
        | termios.ISTRIP
        | termios.INLCR
        | termios.IGNCR
        | termios.ICRNL
        | termios.IXON
    )

    attrs[1] &= ~termios.OPOST

    attrs[2] &= ~termios.CSIZE
    attrs[2] |= termios.CS8
    attrs[2] &= ~termios.PARENB
    attrs[2] &= ~termios.CSTOPB

    if hasattr(termios, "CREAD"):
        attrs[2] |= termios.CREAD
    if hasattr(termios, "CLOCAL"):
        attrs[2] |= termios.CLOCAL

    attrs[3] &= ~(termios.ECHO | termios.ECHONL | termios.ICANON | termios.ISIG)

    if hasattr(termios, "IEXTEN"):
        attrs[3] &= ~termios.IEXTEN

    speed = baud_to_termios(baud)
    attrs[4] = speed
    attrs[5] = speed

    attrs[6][termios.VMIN] = 0
    attrs[6][termios.VTIME] = 5

    termios.tcsetattr(fd, termios.TCSANOW, attrs)


def _write_serial_posix(state: str, port: str, baud: int) -> bool:
    payload = f"STATE:{state}\n".encode("utf-8")

    try:
        fd = os.open(port, os.O_RDWR | os.O_NOCTTY | os.O_NONBLOCK)

        try:
            configure_serial(fd, baud)

            os.write(fd, payload)
            time.sleep(0.04)
            os.write(fd, payload)

            try:
                termios.tcdrain(fd)
            except Exception:
                pass

            log(f"sent STATE:{state} to {port}")
            return True

        finally:
            os.close(fd)

    except Exception as e:
        log(f"serial write failed: port={port}, error={repr(e)}")
        return False


def write_state_to_serial(
    state: str, port: Optional[str] = None, baud: int = DEFAULT_BAUD
) -> bool:
    state = state.strip().lower()

    if state not in VALID_STATES:
        log(f"Invalid state: {state}")
        return False

    picked_port = pick_serial_port(port)

    if not picked_port:
        log("No serial port found")
        return False

    if IS_WINDOWS:
        return _win_write_serial(state, picked_port, baud)
    else:
        return _write_serial_posix(state, picked_port, baud)


# ============================================================
# mDNS auto-discovery
# ============================================================

def resolve_mdns() -> Optional[str]:
    """Resolve claude-rgb.local via mDNS to an IP address.

    Uses stdlib socket.getaddrinfo which leverages:
    - macOS: Bonjour (built-in)
    - Linux: Avahi (needs apt install avahi-daemon)
    - Windows 10+: native mDNS

    Returns IP string or None on failure.  Result is cached for the
    lifetime of this process.
    """
    global _mdns_cached_ip

    if _mdns_cached_ip is not None:
        return _mdns_cached_ip

    try:
        # socket.getaddrinfo resolves .local names via mDNS on most platforms
        results = socket.getaddrinfo(
            MDNS_HOSTNAME, DEFAULT_HTTP_PORT,
            socket.AF_INET, socket.SOCK_STREAM,
        )
        if results:
            _mdns_cached_ip = results[0][4][0]
            log(f"mDNS resolved {MDNS_HOSTNAME} -> {_mdns_cached_ip}")
            return _mdns_cached_ip
    except Exception as e:
        log(f"mDNS resolution failed for {MDNS_HOSTNAME}: {repr(e)}")

    return None


def resolve_host(host: Optional[str] = None) -> str:
    """Resolve the ESP32 host: explicit host > env var > mDNS > default."""
    if host:
        return host

    env_host = os.environ.get("CLAUDE_RGB_HOST", "")
    if env_host:
        return env_host

    mdns_ip = resolve_mdns()
    if mdns_ip:
        return mdns_ip

    return DEFAULT_HOST


# ============================================================
# WiFi (HTTP) communication - NEW
# ============================================================

def write_state_http(
    state: str,
    host: Optional[str] = None,
    port: int = DEFAULT_HTTP_PORT,
    timeout: float = HTTP_TIMEOUT_SEC,
) -> bool:
    """Send state to ESP32 via HTTP GET /state/{state}.

    Returns True on success (HTTP 200 + {"ok":true}),
    False on any failure (timeout, connection refused, etc).
    """
    state = state.strip().lower()

    if state not in VALID_STATES:
        log(f"Invalid state for HTTP: {state}")
        return False

    target_host = resolve_host(host)

    try:
        conn = http.client.HTTPConnection(
            target_host, port, timeout=timeout
        )
        conn.request("GET", f"/state/{state}")
        resp = conn.getresponse()
        body = resp.read()
        conn.close()

        if resp.status == 200:
            log(f"HTTP OK: STATE:{state} -> {target_host}")
            return True

        log(f"HTTP error: status={resp.status}, body={body!r}")
        return False

    except Exception as e:
        log(f"HTTP failed: host={target_host}, error={repr(e)}")
        return False


def get_esp_status(
    host: Optional[str] = None,
    port: int = DEFAULT_HTTP_PORT,
    timeout: float = HTTP_TIMEOUT_SEC,
) -> Optional[Dict[str, Any]]:
    """Query ESP32 status via GET /status. Returns parsed JSON or None."""
    target_host = resolve_host(host)

    try:
        conn = http.client.HTTPConnection(
            target_host, port, timeout=timeout
        )
        conn.request("GET", "/status")
        resp = conn.getresponse()
        body = resp.read().decode("utf-8")
        conn.close()

        if resp.status == 200:
            return json.loads(body)
        return None

    except Exception:
        return None


# ============================================================
# Unified write: HTTP first, serial fallback
# ============================================================

def write_state(
    state: str,
    mode: str = "auto",
    serial_port: Optional[str] = None,
    baud: int = DEFAULT_BAUD,
    http_host: Optional[str] = None,
    http_port: int = DEFAULT_HTTP_PORT,
) -> bool:
    """Send state to ESP32. Auto mode tries HTTP first, falls back to serial."""
    state = state.strip().lower()

    if state not in VALID_STATES:
        log(f"Invalid state: {state}")
        return False

    if mode == "http":
        return write_state_http(state, host=http_host, port=http_port)

    if mode == "serial":
        return write_state_to_serial(state, port=serial_port, baud=baud)

    # mode == "auto": try HTTP first, fallback serial
    if write_state_http(state, host=http_host, port=http_port):
        return True

    log("HTTP failed, falling back to serial")
    return write_state_to_serial(state, port=serial_port, baud=baud)


# ============================================================
# Hook event mapping (unchanged)
# ============================================================

def read_hook_input() -> Dict[str, Any]:
    try:
        raw = sys.stdin.read()

        if not raw.strip():
            return {}

        data = json.loads(raw)

        if isinstance(data, dict):
            return data

        return {}

    except Exception as e:
        log(f"failed to read hook input: {repr(e)}")
        return {}


def state_from_hook(data: Dict[str, Any]) -> Optional[str]:
    event = data.get("hook_event_name", "")
    tool_name = data.get("tool_name", "")
    notification_type = data.get("notification_type", "")

    if event == "SessionStart":
        return "idle"

    if event == "SessionEnd":
        return "idle"

    if event == "UserPromptSubmit":
        return "running"

    if event == "PreToolUse":
        if tool_name in {"AskUserQuestion", "ExitPlanMode"}:
            return "ask"
        return "tool"

    if event == "PostToolUse":
        return "running"

    if event == "PostToolUseFailure":
        return "error"

    if event == "PermissionRequest":
        return "ask"

    if event == "PermissionDenied":
        return "error"

    if event == "Notification":
        if notification_type == "permission_prompt":
            return "ask"

        if notification_type == "elicitation_dialog":
            return "ask"

        if notification_type == "idle_prompt":
            return None

        return None

    if event == "Stop":
        return "done"

    if event == "StopFailure":
        return "error"

    return None


# ============================================================
# CLI
# ============================================================

def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Claude Code RGB WiFi hook for ESP32-C3"
    )

    parser.add_argument(
        "state",
        nargs="?",
        help="Manual test state: idle, done, running, tool, ask, error",
    )

    parser.add_argument(
        "--port",
        default=None,
        help=f"Serial port, default from CLAUDE_RGB_PORT or {DEFAULT_PORT}",
    )

    parser.add_argument(
        "--baud",
        type=int,
        default=DEFAULT_BAUD,
        help="Serial baud rate, default 115200",
    )

    parser.add_argument(
        "--host",
        default=None,
        help=f"ESP32 WiFi IP address, default from CLAUDE_RGB_HOST or {DEFAULT_HOST}",
    )

    parser.add_argument(
        "--http-port",
        type=int,
        default=DEFAULT_HTTP_PORT,
        help=f"ESP32 HTTP port, default {DEFAULT_HTTP_PORT}",
    )

    parser.add_argument(
        "--mode",
        choices=["auto", "http", "serial"],
        default=None,
        help="Communication mode, default from CLAUDE_RGB_MODE or 'auto'",
    )

    parser.add_argument(
        "--scan",
        action="store_true",
        help="List candidate serial ports",
    )

    parser.add_argument(
        "--status",
        action="store_true",
        help="Query current ESP32 status via HTTP",
    )

    parser.add_argument(
        "--discover",
        action="store_true",
        help="Auto-discover ESP32 via mDNS (claude-rgb.local)",
    )

    parser.add_argument(
        "--print-input",
        action="store_true",
        help="Debug: print stdin JSON parsed from Claude Code hook",
    )

    return parser.parse_args()


def resolve_mode(cli_mode: Optional[str]) -> str:
    if cli_mode:
        return cli_mode
    env_mode = os.environ.get("CLAUDE_RGB_MODE", "auto")
    if env_mode in ("auto", "http", "serial"):
        return env_mode
    return "auto"


def main() -> int:
    args = parse_args()

    if args.scan:
        ports = scan_ports()
        for port in ports:
            print(port)
        if not ports:
            print("No serial ports found.", file=sys.stderr)
        return 0

    if args.status:
        result = get_esp_status(host=args.host, port=args.http_port)
        if result:
            print(json.dumps(result, ensure_ascii=False, indent=2))
        else:
            print("Failed to query ESP32 status.", file=sys.stderr)
            return 1
        return 0

    if args.discover:
        print(f"Resolving {MDNS_HOSTNAME} via mDNS...")
        ip = resolve_mdns()
        if ip:
            print(f"Found ESP32 at {ip} ({MDNS_HOSTNAME})")
            # Verify by querying /status
            status = get_esp_status(host=ip, port=args.http_port)
            if status:
                print(f"Status: {json.dumps(status, ensure_ascii=False, indent=2)}")
            else:
                print("Warning: device resolved but HTTP /status failed", file=sys.stderr)
        else:
            print(f"Could not resolve {MDNS_HOSTNAME}", file=sys.stderr)
            print("Hints:", file=sys.stderr)
            print("  - Make sure ESP32 is powered on and connected to WiFi", file=sys.stderr)
            print("  - macOS: Bonjour is built-in", file=sys.stderr)
            print("  - Linux: install avahi-daemon (sudo apt install avahi-daemon)", file=sys.stderr)
            print("  - Windows 10+: mDNS is built-in", file=sys.stderr)
            return 1
        return 0

    mode = resolve_mode(args.mode)

    # Manual test mode
    if args.state:
        state = args.state.strip().lower()

        if state not in VALID_STATES:
            print(f"Invalid state: {state}", file=sys.stderr)
            print(f"Valid states: {', '.join(sorted(VALID_STATES))}", file=sys.stderr)
            return 1

        ok = write_state(
            state,
            mode=mode,
            serial_port=args.port,
            baud=args.baud,
            http_host=args.host,
            http_port=args.http_port,
        )

        if not ok:
            print(f"Failed to send state (mode={mode}).", file=sys.stderr)
            return 1

        return 0

    # Claude Code hook mode
    data = read_hook_input()

    if args.print_input:
        print(json.dumps(data, ensure_ascii=False, indent=2), file=sys.stderr)

    state = state_from_hook(data)

    if state:
        write_state(
            state,
            mode=mode,
            serial_port=args.port,
            baud=args.baud,
            http_host=args.host,
            http_port=args.http_port,
        )

    return 0


if __name__ == "__main__":
    raise SystemExit(main())

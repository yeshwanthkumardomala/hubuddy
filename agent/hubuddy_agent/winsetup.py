"""Windows conveniences: start with Windows and firewall rules. Commands are built here so they can be tested on any OS."""
import subprocess
import sys

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
VALUE = "Hubuddy"
RULE_TCP, RULE_UDP = "Hubuddy phones (TCP)", "Hubuddy discovery (UDP)"


def firewall_commands(tcp_port: int, udp_port: int, exe: str | None = None) -> list[list[str]]:
    """Private-profile rules only: phones on a public network are never allowed in."""
    def rule(name, proto, port):
        cmd = ["netsh", "advfirewall", "firewall", "add", "rule", f"name={name}", "dir=in", "action=allow", "profile=private",
               f"protocol={proto}", f"localport={int(port)}"]
        if exe:
            cmd.append(f"program={exe}")
        return cmd
    delete = [["netsh", "advfirewall", "firewall", "delete", "rule", f"name={n}"] for n in (RULE_TCP, RULE_UDP)]
    return delete + [rule(RULE_TCP, "TCP", tcp_port), rule(RULE_UDP, "UDP", udp_port)]


def elevated_script(commands: list[list[str]]) -> str:
    """One PowerShell line that runs each fixed command with administrator rights (the user sees the Windows prompt)."""
    def q(s):  # PowerShell single-quote escaping
        return "'" + s.replace("'", "''") + "'"
    inner = "; ".join("& " + " ".join(q(a) for a in c) for c in commands)
    return f"Start-Process powershell -Verb RunAs -WindowStyle Hidden -Wait -ArgumentList '-NoProfile','-Command',{q(inner)}"


def allow_through_firewall(tcp_port: int, udp_port: int) -> bool:
    if sys.platform != "win32":
        return False
    exe = sys.executable if getattr(sys, "frozen", False) else None
    try:
        r = subprocess.run(["powershell", "-NoProfile", "-Command", elevated_script(firewall_commands(tcp_port, udp_port, exe))],
                           capture_output=True, timeout=120)
        return r.returncode == 0
    except (OSError, subprocess.SubprocessError):
        return False


def launch_command() -> str:
    if getattr(sys, "frozen", False):
        return f'"{sys.executable}" --minimized'
    return f'"{sys.executable}" -m hubuddy_agent.launcher --minimized'


def autostart_enabled() -> bool:
    if sys.platform != "win32":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as k:
            winreg.QueryValueEx(k, VALUE)
            return True
    except OSError:
        return False


def set_autostart(on: bool) -> bool:
    if sys.platform != "win32":
        return False
    import winreg
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as k:
            if on:
                winreg.SetValueEx(k, VALUE, 0, winreg.REG_SZ, launch_command())
            else:
                try:
                    winreg.DeleteValue(k, VALUE)
                except FileNotFoundError:
                    pass
        return True
    except OSError:
        return False

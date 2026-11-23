"""CPU, memory, disk, network throughput, optional GPU (via nvidia-smi) and temperatures."""
import logging
import os
import platform
import subprocess
import sys
import time

log = logging.getLogger("hubuddy.system")

try:
    import psutil
except ImportError:  # the agent still starts; stats just report as unavailable
    psutil = None

GPU_QUERY = "name,utilization.gpu,temperature.gpu,memory.used,memory.total"
GPU_RETRY_S = 60


def read_nvidia(run=subprocess.run) -> dict | None:
    """First NVIDIA GPU's readings, or None when there is no NVIDIA driver / tool."""
    try:
        out = run(["nvidia-smi", f"--query-gpu={GPU_QUERY}", "--format=csv,noheader,nounits"],
                  capture_output=True, text=True, timeout=4, check=True).stdout
        name, util, temp, used, total = [x.strip() for x in out.strip().splitlines()[0].split(",")]
        res = {"gpu_name": name[:40]}
        if util.isdigit():
            res["gpu"] = int(util)
        if temp.isdigit():
            res["gpu_temp"] = int(temp)
        if used.replace(".", "", 1).isdigit() and total.replace(".", "", 1).isdigit():
            res["vram_used"] = round(float(used) / 1024, 2)
            res["vram_total"] = round(float(total) / 1024, 1)
        return res
    except (OSError, subprocess.SubprocessError, ValueError, IndexError):
        return None


def _cpu_name() -> str:
    name = os.environ.get("PROCESSOR_IDENTIFIER") or platform.processor() or platform.machine()
    return name.replace("(R)", "").replace("(TM)", "").strip()[:40]


def _system_drive() -> str:
    if sys.platform == "win32":
        return os.environ.get("SystemDrive", "C:") + "\\"
    return "/"


class SystemCollector:
    def __init__(self, drive: str | None = None, gpu_reader=read_nvidia, clock=time.monotonic):
        self.drive = drive or _system_drive()
        self.gpu_reader, self.clock = gpu_reader, clock
        self.cpu_name = _cpu_name()
        self._gpu: dict | None = None
        self._gpu_at = -GPU_RETRY_S
        self._gpu_missing = False
        self._net = psutil.net_io_counters() if psutil else None
        self._t = clock()
        if psutil:
            psutil.cpu_percent(None)  # prime the counter

    @property
    def available(self) -> bool:
        return psutil is not None

    def sample(self) -> dict | None:
        if psutil is None:
            return None
        now = self.clock()
        net = psutil.net_io_counters()
        dt = max(now - self._t, 0.001)
        down = (net.bytes_recv - self._net.bytes_recv) / dt / 1024
        up = (net.bytes_sent - self._net.bytes_sent) / dt / 1024
        self._net, self._t = net, now
        vm = psutil.virtual_memory()
        try:
            disk = psutil.disk_usage(self.drive).percent
        except OSError:
            disk = None
        out = {
            "cpu": round(psutil.cpu_percent(None), 1),
            "ram_used": round(vm.used / 2**30, 2),
            "ram_total": round(vm.total / 2**30, 1),
            "disk": disk,
            "net_down": round(max(down, 0.0), 1),
            "net_up": round(max(up, 0.0), 1),
            "cpu_name": self.cpu_name,
        }
        temp = self._cpu_temp()
        if temp is not None:
            out["cpu_temp"] = temp
        out.update(self._gpu_sample(now))
        return out

    def _gpu_sample(self, now: float) -> dict:
        # Spawning nvidia-smi is comparatively slow, so it is asked every 5 s and, when absent, only once a minute.
        interval = GPU_RETRY_S if self._gpu_missing else 5
        if now - self._gpu_at >= interval:
            self._gpu_at = now
            self._gpu = self.gpu_reader()
            self._gpu_missing = self._gpu is None
        return dict(self._gpu or {})

    @staticmethod
    def _cpu_temp() -> float | None:
        try:
            temps = psutil.sensors_temperatures()  # not available on Windows
        except (AttributeError, OSError):
            return None
        vals = [t.current for group in temps.values() for t in group if t.current]
        return round(max(vals), 1) if vals else None

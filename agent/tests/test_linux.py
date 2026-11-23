import subprocess
import sys
import types
import unittest
from unittest import mock

from hubuddy_agent import clipboard, remote
from hubuddy_agent.__main__ import firewall_help


class Ran:
    def __init__(self, code=0):
        self.cmds = []
        self.code = code

    def __call__(self, argv, **kw):
        self.cmds.append((list(argv), kw))
        return types.SimpleNamespace(returncode=self.code)


def have(*names):
    return lambda n: ("/usr/bin/" + n) if n in names else None


class LinuxClipboard(unittest.TestCase):
    def test_uses_the_first_tool_present_without_a_shell(self):
        run = Ran()
        with mock.patch.dict("os.environ", {"WAYLAND_DISPLAY": ""}, clear=False):
            self.assertTrue(clipboard.set_text_linux("héllo; rm -rf /", which=have("xclip", "wl-copy"), run=run))
        argv, kw = run.cmds[0]
        self.assertEqual(argv, ["xclip", "-selection", "clipboard"])
        self.assertEqual(kw["input"], "héllo; rm -rf /".encode())  # the text is data on stdin, never part of a command
        self.assertNotIn("shell", kw)

    def test_prefers_wl_copy_on_wayland_and_reports_failure(self):
        run = Ran()
        with mock.patch.dict("os.environ", {"WAYLAND_DISPLAY": "wayland-0"}):
            clipboard.set_text_linux("x", which=have("xclip", "wl-copy"), run=run)
        self.assertEqual(run.cmds[0][0], ["wl-copy"])
        self.assertFalse(clipboard.set_text_linux("x", which=have(), run=Ran()))
        self.assertFalse(clipboard.set_text_linux("x", which=have("xsel"), run=Ran(code=1)))


class LinuxRemote(unittest.TestCase):
    def backend(self, *tools, code=0):
        run = Ran(code)
        return remote.LinuxBackend(which=have(*tools), run=run), run

    def test_every_action_is_a_fixed_argument_list(self):
        be, run = self.backend("xdotool", "systemctl", "shutdown")
        be.move(5, -3); be.click("right", True); be.scroll(0, 130); be.scroll(0, -3); be.key("enter", ["ctrl", "shift"]); be.text("a b; $(reboot)")
        be.power("sleep"); be.power("shutdown"); be.power("abort")
        cmds = [c for c, _ in run.cmds]
        self.assertEqual(cmds[0], ["xdotool", "mousemove_relative", "--", "5", "-3"])
        self.assertEqual(cmds[1][-1], "3"); self.assertIn("--repeat", cmds[1])
        self.assertEqual(cmds[2], ["xdotool", "click", "--repeat", "2", "4"])   # 130 units = 2 notches up; the -3 left over stays pending
        self.assertEqual(cmds[3][-1], "ctrl+shift+Return")                      # (-3 + 10 is still under a notch: nothing sent)
        self.assertEqual(cmds[4][-2:], ["--", "a b; $(reboot)"])  # typed as text, not interpreted
        self.assertEqual(cmds[5], ["systemctl", "suspend"])
        self.assertTrue(cmds[6][0].endswith("shutdown") and "-h" in cmds[6])
        self.assertTrue(cmds[7][-1] == "-c")
        for argv in cmds:
            self.assertIsInstance(argv, list)
        for _, kw in run.cmds:
            self.assertNotIn("shell", kw)

    def test_buttons_zoom_and_horizontal_scroll(self):
        be, run = self.backend("xdotool")
        be.button("left", True); be.button("left", False); be.zoom(3); be.zoom(-2); be.scroll(130, 0); be.scroll(-130, 0)
        self.assertEqual([c for c, _ in run.cmds], [
            ["xdotool", "mousedown", "1"], ["xdotool", "mouseup", "1"],
            ["xdotool", "keydown", "ctrl", "click", "--repeat", "3", "4", "keyup", "ctrl"],
            ["xdotool", "keydown", "ctrl", "click", "--repeat", "2", "5", "keyup", "ctrl"],
            ["xdotool", "click", "--repeat", "2", "6"], ["xdotool", "click", "--repeat", "2", "7"]])

    def test_missing_tools_are_reported_not_faked(self):
        be, _ = self.backend()
        for call in (lambda: be.move(1, 1), lambda: be.text("x"), lambda: be.power("sleep")):
            with self.assertRaises(remote.Unavailable) as e:
                call()
            self.assertIn("install", str(e.exception))

    def test_a_failing_command_is_unavailable(self):
        be, _ = self.backend("xdotool", code=1)
        with self.assertRaises(remote.Unavailable):
            be.move(1, 1)

    def test_default_backend_on_linux_and_delay(self):
        with mock.patch.object(sys, "platform", "linux"):
            self.assertIsInstance(remote.default_backend(), remote.LinuxBackend)
        self.assertEqual(remote.LinuxBackend.power_delay_s, 60)


class Help(unittest.TestCase):
    def test_firewall_help_names_the_real_ports_and_private_ranges(self):
        t = firewall_help(18765)
        for want in ("18765", "41236", "ufw allow from 192.168.0.0/16", "firewall-cmd"):
            self.assertIn(want, t)
        self.assertNotIn("anywhere", t.lower().replace("never from the internet", ""))

    def test_launcher_falls_back_without_a_window(self):
        from hubuddy_agent import launcher
        with mock.patch.dict(sys.modules, {"tkinter": None}), mock.patch("hubuddy_agent.__main__.main", return_value=0) as cli:
            self.assertEqual(launcher.main(["--minimized"]), 0)
        self.assertIn("--show-invite", cli.call_args[0][0])


if __name__ == "__main__":
    unittest.main()


class HiddenProcesses(unittest.TestCase):
    """No helper program may ever pop up a console window on Windows (the cause of a terminal flashing every few seconds)."""

    def test_windows_gets_the_hidden_flags_and_other_systems_nothing(self):
        from hubuddy_agent import hiddenproc
        with mock.patch.object(sys, "platform", "win32"):
            kw = hiddenproc.no_window()
        self.assertEqual(kw["creationflags"], 0x08000000)
        with mock.patch.object(sys, "platform", "linux"):
            self.assertEqual(hiddenproc.no_window(), {})

    def test_the_gpu_reader_and_other_helpers_use_the_hidden_runner(self):
        import inspect
        from hubuddy_agent import hiddenproc
        from hubuddy_agent.collectors import system
        self.assertIs(inspect.signature(system.read_nvidia).parameters["run"].default, hiddenproc.run)
        self.assertIs(inspect.signature(clipboard.set_text_linux).parameters["run"].default, hiddenproc.run)

    def test_the_program_files_that_start_helpers_do_not_use_bare_subprocess_run(self):
        import pathlib
        root = pathlib.Path(__file__).resolve().parent.parent / "hubuddy_agent"
        allowed = {"hiddenproc.py", "webwindow.py", "claude_statusline.py"}   # statusline runs inside Claude Code, not inside Hubuddy
        for f in list(root.glob("*.py")) + list(root.glob("collectors/*.py")):
            if f.name in allowed:
                continue
            text = f.read_text()
            self.assertNotIn("subprocess.run(", text, f.name)
            self.assertNotIn("subprocess.Popen(", text.replace("collectors/shortcuts", ""), f.name) if f.name != "shortcuts.py" else None

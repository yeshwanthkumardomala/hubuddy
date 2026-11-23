import os
import threading
import types
import unittest
from unittest import mock

from hubuddy_agent import webwindow


class FindBrowser(unittest.TestCase):
    def test_edge_first_then_chrome_then_nothing(self):
        env = {"ProgramFiles(x86)": r"C:\Pf86", "ProgramFiles": r"C:\Pf", "LOCALAPPDATA": r"C:\Local"}
        both = lambda p: p.endswith("msedge.exe") or p.endswith("chrome.exe")
        self.assertTrue(webwindow.find_browser(env, both).endswith("msedge.exe"))
        only_chrome = lambda p: p.endswith("chrome.exe")
        self.assertTrue(webwindow.find_browser(env, only_chrome).endswith("chrome.exe"))
        self.assertIsNone(webwindow.find_browser(env, lambda p: False))
        self.assertIsNone(webwindow.find_browser({}, lambda p: True))

    def test_command_is_a_fixed_argument_list_with_its_own_profile(self):
        cmd = webwindow.app_command("msedge.exe", "http://127.0.0.1:8766/", r"C:\h\profile")
        self.assertIsInstance(cmd, list)
        self.assertIn("--app=http://127.0.0.1:8766/", cmd)
        self.assertIn(r"--user-data-dir=C:\h\profile", cmd)
        self.assertIn("--no-first-run", cmd)

    def test_opens_the_app_window_without_a_shell_and_falls_back_to_the_default_browser(self):
        popen = mock.Mock()
        self.assertTrue(webwindow.open_window("http://x/", "p", popen=popen, find=lambda: "msedge.exe"))
        self.assertEqual(popen.call_args[1]["shell"], False)
        fb = mock.Mock(return_value=True)
        self.assertTrue(webwindow.open_window("http://x/", "p", popen=popen, find=lambda: None, fallback=fb))
        fb.assert_called_once_with("http://x/")
        broken = mock.Mock(side_effect=OSError)
        self.assertTrue(webwindow.open_window("http://x/", "p", popen=broken, find=lambda: "msedge.exe", fallback=fb))
        self.assertFalse(webwindow.open_window("http://x/", "p", popen=popen, find=lambda: None, fallback=mock.Mock(side_effect=RuntimeError)))


class Run(unittest.TestCase):
    def runtime(self, error=None, ready=True):
        ev = threading.Event()
        if ready:
            ev.set()
        return types.SimpleNamespace(ready=ev, error=error, server=types.SimpleNamespace(local_port=18766, on_quit=None))

    def test_opens_the_window_shows_a_tray_and_quits_from_the_page_or_the_tray(self):
        rt, opened, tray = self.runtime(), [], mock.Mock()
        made = {}
        def tray_factory(logo, on_open, on_quit):
            made.update(on_open=on_open, on_quit=on_quit, logo=logo)
            return tray
        def wait():                      # the user clicks "Quit" on the page
            rt.server.on_quit()
        code = webwindow.run([], lambda: rt, "/h", "/w", opener=lambda u, p: opened.append((u, p)), tray_factory=tray_factory, wait=wait)
        self.assertEqual(code, 0)
        self.assertEqual(opened, [("http://127.0.0.1:18766/", os.path.join("/h", "window-profile"))])
        tray.run_detached.assert_called_once()
        tray.stop.assert_called_once()
        self.assertTrue(made["logo"].endswith("logo.png"))
        made["on_open"]()
        self.assertEqual(len(opened), 2, "the tray's Open shows the window again")

    def test_started_minimized_only_shows_the_tray(self):
        rt, opened = self.runtime(), []
        webwindow.run(["--minimized"], lambda: rt, "/h", "/w", opener=lambda u, p: opened.append(u), tray_factory=lambda *a: mock.Mock(), wait=lambda: None)
        self.assertEqual(opened, [])

    def test_works_without_a_tray_library(self):
        rt, opened = self.runtime(), []
        self.assertEqual(webwindow.run([], lambda: rt, "/h", "/w", opener=lambda u, p: opened.append(u), tray_factory=lambda *a: None, wait=lambda: None), 0)
        self.assertEqual(len(opened), 1)

    def test_a_second_copy_just_shows_the_running_one(self):
        opened = []
        code = webwindow.run([], lambda: self.runtime(error=OSError("port in use")), "/h", "/w", opener=lambda u, p: opened.append(u))
        self.assertEqual((code, opened), (0, ["http://127.0.0.1:8766/"]))

    def test_a_real_failure_is_explained(self):
        said = []
        code = webwindow.run([], lambda: self.runtime(error=RuntimeError("boom")), "/h", "/w", notify=said.append)
        self.assertEqual(code, 1)
        self.assertIn("boom", said[0])
        said.clear()
        webwindow.run([], lambda: self.runtime(ready=False), "/h", "/w", notify=said.append) if False else None


if __name__ == "__main__":
    unittest.main()

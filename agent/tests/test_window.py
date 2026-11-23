"""Runs the Hubuddy window's code against a stand-in for Tk, so typing mistakes and wrong calls show up without a screen.

The stand-in accepts every widget and records what was created, which is enough to check the logic (startup, pairing, phone
list, switches, closing). How the window looks is checked separately by the CI smoke test on a real Windows desktop.
"""
import os
import pathlib
import tempfile
import time
import types
import unittest
from unittest import mock

from helpers import decode_invite, Phone


class Widget:
    created: list = []

    def __init__(self, *a, **k):
        self.opts = dict(k)
        self.kind = type(self).__name__
        Widget.created.append(self)

    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        def call(*a, **k):
            if name == "configure":
                self.opts.update(k)
            return None
        return call

    def winfo_children(self): return []
    def subsample(self, a, b=None): return self
    def state(self, flags): self.opts["state"] = list(flags)


class Root(Widget):
    def __init__(self):
        super().__init__(); self.jobs = {}; self.n = 0; self.clip = ""; self.destroyed = False; self.iconified = False; self.geometry_text = ""
    def after(self, ms, fn): self.n += 1; self.jobs[self.n] = fn; return self.n
    def after_cancel(self, i): self.jobs.pop(i, None)
    def clipboard_clear(self): self.clip = ""
    def clipboard_append(self, t): self.clip += t
    def destroy(self): self.destroyed = True
    def iconify(self): self.iconified = True
    def geometry(self, g=None): self.geometry_text = g
    def winfo_screenwidth(self): return 1920
    def winfo_screenheight(self): return 1080
    def mainloop(self): pass


class Var:
    def __init__(self, value=None): self.v = value
    def get(self): return self.v
    def set(self, v): self.v = v


class FakeModule:
    """Any widget class you ask for exists."""
    Tk = Root
    BooleanVar = Var
    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        return type(name, (Widget,), {})


def fake_tk():
    mb = types.SimpleNamespace(answer=True, showinfo=mock.Mock(), showerror=mock.Mock(), showwarning=mock.Mock())
    mb.askyesno = lambda *a, **k: mb.answer
    mb.askyesnocancel = lambda *a, **k: mb.answer
    return FakeModule(), FakeModule(), mb


def texts():
    return [w.opts.get("text", "") for w in Widget.created if w.opts.get("text")]


class WindowTests(unittest.TestCase):
    def setUp(self):
        from hubuddy_agent import launcher
        self.launcher = launcher
        Widget.created = []
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self.tmp.cleanup)
        env = mock.patch.dict(os.environ, {"HOME": self.tmp.name, "USERPROFILE": self.tmp.name})
        env.start(); self.addCleanup(env.stop)
        self.rt = launcher.Runtime(pathlib.Path(self.tmp.name) / "h", local_port=0)
        self.addCleanup(lambda: self.rt.loop.call_soon_threadsafe(self.rt.loop.stop))
        self.tk, self.ttk, self.mb = fake_tk()
        self.app = launcher.App(self.tk, self.ttk, self.mb, self.rt, dark=True)
        self.assertTrue(self.rt.ready.wait(20))
        self.app.wait_ready()

    def pair_phone(self, name="Vivo"):
        inv = self.app.srv.svc.pairing.invites.issue(self.app.srv.invite_details())
        d = decode_invite(inv["invitation"])
        p = Phone(self.app.srv.port, d["ca"], d["hostId"])
        s, b = p.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"], "device": name}, token=False)
        self.assertEqual(s, 200)
        p.token = b["token"]
        return p

    def test_opens_with_a_splash_then_shows_the_main_window(self):
        self.assertIsNone(self.rt.error)
        self.assertIn("Starting…", texts())                      # the splash was drawn first
        for want in ("Show QR code", "Pair with a PIN", "Copy invitation", "Forget all phones", "Quit Hubuddy"):
            self.assertIn(want, texts())
        self.assertIsNotNone(self.app.srv)
        self.assertIn("Ready", self.app.pill.opts["text"])

    def test_startup_never_waits_for_the_server(self):
        # While the server is not ready the window keeps polling instead of blocking.
        rt = types.SimpleNamespace(ready=mock.Mock(), error=None, discovery_error=None, server=None, svc=None, events=None, local_port=0)
        rt.ready.is_set.return_value = False
        app = self.launcher.App(self.tk, self.ttk, self.mb, rt, dark=False)
        jobs = len(app.root.jobs)
        app.wait_ready()
        self.assertEqual(len(app.root.jobs), jobs + 1, "it rescheduled itself instead of waiting")
        self.assertFalse(app.root.destroyed)
        app.on_close()
        self.assertTrue(app.root.destroyed, "closing during startup just closes")

    def test_a_start_up_failure_is_explained(self):
        rt = types.SimpleNamespace(ready=mock.Mock(), error=RuntimeError("boom"), discovery_error=None, server=None, svc=None, events=None, local_port=0)
        rt.ready.is_set.return_value = True
        app = self.launcher.App(self.tk, self.ttk, self.mb, rt)
        app.wait_ready()
        self.assertIn("boom", self.mb.showerror.call_args[0][1])
        rt.error = OSError("address in use")
        with mock.patch.object(self.launcher, "open_page") as op:
            app2 = self.launcher.App(self.tk, self.ttk, self.mb, rt)
            app2.wait_ready()
            op.assert_called_once()

    def test_qr_and_pin_draw_and_count_down_once(self):
        a = self.app
        a.show_qr()
        self.assertTrue(a.invitation.startswith("hubuddy-pair:"))
        self.assertIn("Valid for", a.pair_info.opts["text"])
        jobs = len(a.root.jobs)
        a.show_qr(); a.show_qr()
        self.assertEqual(len(a.root.jobs), jobs, "one countdown, however often QR is shown")
        a.show_pin()
        self.assertRegex(a.pin_big.opts["text"], r"PIN\s+\d{6}")
        self.assertRegex(a.code_big.opts["text"], r"[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")
        a.pair_until = time.time() - 1
        a.tick_pair("x")
        self.assertIn("closed", a.pair_info.opts["text"])

    def test_copy_invitation_puts_text_on_the_clipboard(self):
        self.app.copy_invite()
        self.assertTrue(self.app.root.clip.startswith("hubuddy-pair:"))

    def test_a_paired_phone_appears_and_its_remote_switch_asks_first(self):
        p = self.pair_phone("Vivo 1902")
        deadline = time.time() + 3
        while time.time() < deadline and self.rt.events.empty():
            time.sleep(0.05)
        self.app.poll()
        self.assertIn("Vivo 1902", texts())
        self.assertIn("is now paired", self.app.pair_info.opts["text"])
        sid = next(iter(self.app._rows))
        var = self.app._rows[sid]
        var.set(True); self.mb.answer = False
        self.app.toggle_remote(sid)
        self.assertFalse(var.get(), "declining the confirmation leaves it off")
        self.assertEqual(p.call("GET", "/api/remote")[1]["allowed"], False)
        self.mb.answer = True; var.set(True)
        self.app.toggle_remote(sid)
        self.assertEqual(p.call("GET", "/api/remote")[1]["allowed"], True)
        sid = next(iter(self.app._rows)); self.app._rows[sid].set(False)
        self.app.toggle_remote(sid)
        self.assertEqual(p.call("GET", "/api/remote")[1]["allowed"], False)

    def test_the_phone_list_is_only_redrawn_when_something_changed(self):
        self.pair_phone("Vivo")
        self.app.refresh()
        count = len(Widget.created)
        for _ in range(5):
            self.app.refresh()
        self.assertEqual(len(Widget.created), count, "no flicker: same data, no new widgets")

    def test_forget_and_forget_all(self):
        p = self.pair_phone()
        self.app.refresh()
        self.app.forget(next(iter(self.app._rows)))
        self.assertEqual(p.call("GET", "/api/snapshot")[0], 401)
        self.assertIn("No phones paired yet. Choose Show QR code above.", texts())
        q = self.pair_phone("Other")
        self.app.forget_all()
        self.assertEqual(q.call("GET", "/api/snapshot")[0], 401)

    def test_closing_asks_instead_of_silently_minimising(self):
        self.mb.answer = None; self.app.on_close()
        self.assertFalse(self.app.root.destroyed or self.app.root.iconified)
        self.mb.answer = False; self.app.on_close()
        self.assertTrue(self.app.root.iconified)
        self.mb.answer = True; self.app.on_close()
        self.assertTrue(self.app.root.destroyed)

    def test_firewall_and_autostart_report_cleanly_off_windows(self):
        self.app.firewall()
        self.assertTrue(self.mb.showinfo.called)
        self.app.auto.set(True); self.app.toggle_auto()
        self.assertTrue(self.mb.showwarning.called)
        self.assertFalse(self.app.auto.get())

    def test_both_colour_schemes_cover_every_colour_the_style_uses(self):
        for pal in (self.launcher.DARK, self.launcher.LIGHT):
            self.assertEqual(set(pal), set(self.launcher.DARK))
            for v in pal.values():
                self.assertRegex(v, r"^#[0-9a-f]{6}$")


if __name__ == "__main__":
    unittest.main()

"""Runs the Hubuddy window's code against a stand-in for Tk, so typing mistakes and wrong calls show up without a screen."""
import os
import sys
import tempfile
import time
import types
import unittest
from unittest import mock

from helpers import decode_invite, Phone


class Widget:
    """Accepts any call, remembers text, like a very forgiving widget."""
    def __init__(self, *a, **k): self.opts = dict(k); self.children = []
    def __getattr__(self, name):
        if name.startswith("__"):
            raise AttributeError(name)
        def call(*a, **k):
            if name == "configure":
                self.opts.update(k)
            return None
        return call
    def state(self, flags): self.opts["state"] = list(flags)


class Listbox(Widget):
    def __init__(self, *a, **k): super().__init__(); self.items = []; self.sel = ()
    def delete(self, a, b=None): self.items = []; self.sel = ()
    def insert(self, where, text): self.items.append(text)
    def curselection(self): return self.sel
    def selection_set(self, i): self.sel = (i,)


class Var:
    def __init__(self, value=None): self.v = value
    def get(self): return self.v
    def set(self, v): self.v = v


class Root(Widget):
    def __init__(self): super().__init__(); self.jobs = {}; self.n = 0; self.title_text = None; self.clip = ""; self.destroyed = False; self.iconified = False
    def after(self, ms, fn): self.n += 1; self.jobs[self.n] = fn; return self.n
    def after_cancel(self, i): self.jobs.pop(i, None)
    def clipboard_clear(self): self.clip = ""
    def clipboard_append(self, t): self.clip += t
    def destroy(self): self.destroyed = True
    def iconify(self): self.iconified = True
    def mainloop(self): pass


def fake_tk():
    tk = types.SimpleNamespace(Tk=Root, Canvas=Widget, Listbox=Listbox, BooleanVar=Var)
    ttk = types.SimpleNamespace(Frame=Widget, Label=Widget, LabelFrame=Widget, Button=Widget, Checkbutton=Widget, Scrollbar=Widget)
    mb = types.SimpleNamespace(answer=True, showinfo=mock.Mock(), showerror=mock.Mock(), showwarning=mock.Mock())
    mb.askyesno = lambda *a, **k: mb.answer
    mb.askyesnocancel = lambda *a, **k: mb.answer
    return tk, ttk, mb


class WindowTests(unittest.TestCase):
    def setUp(self):
        from hubuddy_agent import launcher
        self.launcher = launcher
        self.tmp = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self.tmp.cleanup)
        env = mock.patch.dict(os.environ, {"HOME": self.tmp.name, "USERPROFILE": self.tmp.name})
        env.start(); self.addCleanup(env.stop)
        port = mock.patch.object(launcher, "LOCAL_PORT", 0); port.start(); self.addCleanup(port.stop)
        import pathlib
        self.rt = launcher.Runtime(pathlib.Path(self.tmp.name) / "h")
        self.assertTrue(self.rt.ready.wait(20))
        self.assertIsNone(self.rt.error)
        self.addCleanup(lambda: self.rt.loop.call_soon_threadsafe(self.rt.loop.stop))
        self.tk, self.ttk, self.mb = fake_tk()
        self.w = launcher.Window(self.tk, self.ttk, self.mb, self.rt)

    def pair_phone(self, name="Vivo"):
        inv = self.w.srv.svc.pairing.invites.issue(self.w.srv.invite_details())
        d = decode_invite(inv["invitation"])
        p = Phone(self.w.srv.port, d["ca"], d["hostId"])
        s, b = p.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"], "device": name}, token=False)
        self.assertEqual(s, 200)
        p.token = b["token"]
        return p

    def test_qr_and_pin_draw_and_count_down_once(self):
        self.w.show_qr()
        self.assertTrue(self.w.invitation.startswith("hubuddy-pair:"))
        self.assertIn("Valid for", self.w.pair_info.opts["text"])
        jobs = len(self.w.root.jobs)          # the polling job plus one countdown
        self.w.show_qr(); self.w.show_qr()
        self.assertEqual(len(self.w.root.jobs), jobs, "one countdown, however often QR is shown")
        self.w.show_pin()
        self.assertRegex(self.w.pin_big.opts["text"], r"PIN\s+\d{6}")
        self.assertRegex(self.w.code_big.opts["text"], r"[0-9A-Z]{4}-[0-9A-Z]{4}-[0-9A-Z]{4}")
        self.w.pair_until = time.time() - 1
        self.w.tick_pair("x")
        self.assertIn("closed", self.w.pair_info.opts["text"])

    def test_copy_invitation_puts_text_on_the_clipboard(self):
        self.w.copy_invite()
        self.assertTrue(self.w.root.clip.startswith("hubuddy-pair:"))

    def test_a_paired_phone_appears_and_remote_control_can_be_switched(self):
        p = self.pair_phone("Vivo 1902")
        deadline = time.time() + 3
        while time.time() < deadline and self.rt.events.empty():
            time.sleep(0.05)
        self.w.poll()
        self.assertIn("Vivo 1902", self.w.box.items[0])
        self.assertIn("is now paired", self.w.pair_info.opts["text"])
        self.w.box.sel = (0,)
        self.w.sync_remote()
        self.w.remote.set(True); self.mb.answer = False
        self.w.toggle_remote()
        self.assertFalse(self.w.remote.get(), "declining the confirmation leaves it off")
        self.mb.answer = True; self.w.remote.set(True)
        self.w.toggle_remote()
        self.assertIn("remote control ON", self.w.box.items[0])
        self.assertEqual(p.call("GET", "/api/remote")[1]["allowed"], True)
        self.w.box.sel = (0,); self.w.remote.set(False)
        self.w.toggle_remote()
        self.assertEqual(p.call("GET", "/api/remote")[1]["allowed"], False)

    def test_forget_and_forget_all(self):
        p = self.pair_phone()
        self.w.refresh(); self.w.box.sel = (0,)
        self.w.forget()
        self.assertEqual(p.call("GET", "/api/snapshot")[0], 401)
        self.assertIn("No phones", self.w.box.items[0])
        q = self.pair_phone("Other")
        self.w.forget_all()
        self.assertEqual(q.call("GET", "/api/snapshot")[0], 401)

    def test_closing_asks_instead_of_silently_minimising(self):
        self.mb.answer = None; self.w.on_close()
        self.assertFalse(self.w.root.destroyed or self.w.root.iconified)
        self.mb.answer = False; self.w.on_close()
        self.assertTrue(self.w.root.iconified)
        self.mb.answer = True; self.w.on_close()
        self.assertTrue(self.w.root.destroyed)

    def test_firewall_and_autostart_report_cleanly_off_windows(self):
        self.w.firewall()
        self.assertTrue(self.mb.showinfo.called)
        self.w.auto.set(True); self.w.toggle_auto()
        self.assertTrue(self.mb.showwarning.called)
        self.assertFalse(self.w.auto.get())


if __name__ == "__main__":
    unittest.main()

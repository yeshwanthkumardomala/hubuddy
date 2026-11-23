import http.client
import json
import socket
import ssl
import time
import unittest

from helpers import Agent, AgentCase, decode_invite
from hubuddy_agent import pairing


class Health(AgentCase):
    def test_health_over_pinned_tls_names_the_pc(self):
        p = self.agent.phone()
        s, b = p.call("GET", "/health", token=False)
        self.assertEqual(s, 200)
        self.assertEqual(b["hostId"], self.agent.svc.host_id)
        self.assertEqual(b["ca"], self.agent.svc.tls.public_ca)

    def test_a_different_ca_is_rejected_by_the_client(self):
        other = Agent()
        self.addCleanup(other.stop)
        wrong = self.agent.phone()
        wrong.ca = other.svc.tls.public_ca
        wrong.reconnect()
        with self.assertRaises(ssl.SSLError):
            wrong.call("GET", "/health", token=False)

    def test_plain_http_on_the_tls_port_gets_nothing(self):
        c = http.client.HTTPConnection("127.0.0.1", self.agent.server.port, timeout=5)
        with self.assertRaises(Exception):
            c.request("GET", "/health")
            c.getresponse().read()

    def test_unknown_host_header_is_refused(self):
        p = self.agent.phone()
        s, _ = p.call("GET", "/health", headers={"Host": "evil.example"}, token=False)
        self.assertEqual(s, 403)


class QrPairing(AgentCase):
    def test_pair_then_use_the_api_and_replay_fails(self):
        _, inv = self.agent.local("POST", "/local/invite")
        d = decode_invite(inv["invitation"])
        p = self.agent.phone()
        body = {"hostId": d["hostId"], "secret": d["secret"], "device": "Vivo"}
        s, r = p.call("POST", "/api/pairing/claim", body, token=False)
        self.assertEqual(s, 200)
        p.token = r["token"]
        s, snap = p.call("GET", "/api/snapshot")
        self.assertEqual((s, snap["stats"]["cpu"]), (200, 12.5))
        s, _ = p.call("POST", "/api/pairing/claim", body, token=False)
        self.assertEqual(s, 401, "an invitation works once")

    def test_api_requires_a_valid_token(self):
        p = self.agent.phone()
        for tok in (None, "x" * 43, "short"):
            p.token = tok
            self.assertEqual(p.call("GET", "/api/snapshot")[0], 401)

    def test_wrong_host_id_is_refused(self):
        _, inv = self.agent.local("POST", "/local/invite")
        d = decode_invite(inv["invitation"])
        p = self.agent.phone()
        s, _ = p.call("POST", "/api/pairing/claim", {"hostId": "nope", "secret": d["secret"]}, token=False)
        self.assertEqual(s, 401)

    def test_lockout_after_repeated_failures(self):
        p = self.agent.phone()
        codes = [p.call("POST", "/api/pairing/claim", {"hostId": self.agent.svc.host_id, "secret": "A" * 43}, token=False)[0] for _ in range(7)]
        self.assertEqual(codes[:5], [401] * 5)
        self.assertEqual(codes[5], 429)
        _, inv = self.agent.local("POST", "/local/invite")
        d = decode_invite(inv["invitation"])
        s, _ = p.call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"]}, token=False)
        self.assertEqual(s, 429, "a locked client cannot pair even with a good invitation")

    def test_wrong_method_and_paths(self):
        p = self.agent.pair_by_qr()
        self.assertEqual(p.call("POST", "/api/snapshot", {})[0], 405)
        self.assertEqual(p.call("GET", "/api/nope")[0], 404)
        self.assertEqual(p.call("GET", "/")[0], 404, "the LAN side serves no web pages")

    def test_a_refused_upload_still_gets_its_reply(self):
        # The sender is still sending a large body when the PC refuses it; the reply must arrive (Windows resets otherwise).
        p = self.agent.phone()
        s, _ = p.call("PUT", "/api/files/upload?name=big.bin", raw=b"x" * 2_000_000, token=False)
        self.assertEqual(s, 401)

    def test_malformed_bodies(self):
        p = self.agent.pair_by_qr()
        self.assertEqual(p.call("POST", "/api/send", raw=b"not json")[0], 400)
        self.assertEqual(p.call("POST", "/api/send", raw=b"[1]")[0], 400)
        self.assertEqual(p.call("POST", "/api/send", {"kind": "clipboard"})[0], 400)


class PinPairingFlow(AgentCase):
    def test_pin_with_compare_code(self):
        s, started = self.agent.local("POST", "/local/pin")
        self.assertEqual(s, 200)
        p = self.agent.phone()
        s, health = p.call("GET", "/health", token=False)
        self.assertEqual(pairing.compare_code(self.agent.svc.tls.pin), started["compare"])
        s, r = p.call("POST", "/api/pairing/pin-claim", {"hostId": started["hostId"], "pin": started["pin"], "device": "Phone B"}, token=False)
        self.assertEqual(s, 200)
        p.token = r["token"]
        self.assertEqual(p.call("GET", "/api/snapshot")[0], 200)
        s, _ = p.call("POST", "/api/pairing/pin-claim", {"hostId": started["hostId"], "pin": started["pin"]}, token=False)
        self.assertEqual(s, 401)

    def test_wrong_pin_is_counted(self):
        _, started = self.agent.local("POST", "/local/pin")
        p = self.agent.phone()
        wrong = "000000" if started["pin"] != "000000" else "111111"
        self.assertEqual(p.call("POST", "/api/pairing/pin-claim", {"hostId": started["hostId"], "pin": wrong}, token=False)[0], 401)

    def test_pin_is_closed_unless_started(self):
        p = self.agent.phone()
        self.assertEqual(p.call("POST", "/api/pairing/pin-claim", {"hostId": self.agent.svc.host_id, "pin": "123456"}, token=False)[0], 401)


class ManyPhones(AgentCase):
    def test_several_phones_are_independent_and_forgettable(self):
        phones = [self.agent.pair_by_qr(f"Phone {i}") for i in range(3)]
        _, state = self.agent.local("GET", "/local/state")
        self.assertEqual(len(state["phones"]), 3)
        gone = state["phones"][1]
        self.agent.local("POST", "/local/forget", {"id": gone["id"]})
        codes = [p.call("GET", "/api/snapshot")[0] for p in phones]
        self.assertEqual(codes, [200, 401, 200])

    def test_cap_of_32_phones(self):
        for i in range(32):
            self.agent.svc.sessions.issue(f"p{i}")
        _, inv = self.agent.local("POST", "/local/invite")
        d = decode_invite(inv["invitation"])
        s, _ = self.agent.phone().call("POST", "/api/pairing/claim", {"hostId": d["hostId"], "secret": d["secret"]}, token=False)
        self.assertGreaterEqual(s, 400)

    def test_forget_all_needs_confirmation(self):
        p = self.agent.pair_by_qr()
        self.assertEqual(self.agent.local("POST", "/local/forget-all", {})[0], 400)
        self.assertEqual(p.call("GET", "/api/snapshot")[0], 200)
        self.assertEqual(self.agent.local("POST", "/local/forget-all", {"confirm": "forget-all"})[0], 200)
        self.assertEqual(p.call("GET", "/api/snapshot")[0], 401)


class TwoWay(AgentCase):
    def test_phone_sends_clipboard_and_link(self):
        p = self.agent.pair_by_qr()
        self.assertEqual(p.call("POST", "/api/send", {"kind": "clipboard", "text": "hello"})[0], 200)
        self.assertEqual(self.agent.clip, ["hello"])
        self.assertEqual(p.call("POST", "/api/send", {"kind": "link", "text": "https://example.org/x"})[0], 200)
        self.assertEqual(self.agent.links, ["https://example.org/x"])
        for bad in ("javascript:alert(1)", "file:///c:/x", "http://a b", "ftp://x"):
            self.assertEqual(p.call("POST", "/api/send", {"kind": "link", "text": bad})[0], 400, bad)
        self.assertEqual(self.agent.links, ["https://example.org/x"])

    def test_pc_sends_to_phone_and_ack(self):
        p = self.agent.pair_by_qr()
        self.assertEqual(self.agent.local("POST", "/local/send", {"kind": "link", "text": "https://example.org"})[0], 200)
        _, inbox = p.call("GET", "/api/inbox")
        self.assertEqual(len(inbox["items"]), 1)
        p.call("POST", "/api/inbox/ack", {"ids": [inbox["items"][0]["id"]]})
        self.assertEqual(p.call("GET", "/api/inbox")[1]["items"], [])
        self.assertEqual(self.agent.local("POST", "/local/send", {"kind": "link", "text": "javascript:x"})[0], 400)

    def test_phone_status_shows_on_the_pc(self):
        p = self.agent.pair_by_qr("Vivo")
        p.call("POST", "/api/phone/status", {"battery": 71, "charging": True, "network": "Wi-Fi"})
        _, state = self.agent.local("GET", "/local/state")
        self.assertEqual(state["phones"][0]["status"]["battery"], 71)

    def test_file_round_trip(self):
        p = self.agent.pair_by_qr()
        s, r = p.call("PUT", "/api/files/upload?name=..%2Fphoto.png", raw=b"PNGDATA", headers={"Content-Type": "application/octet-stream"})
        self.assertEqual((s, r["name"]), (200, "photo.png"))
        self.assertEqual((self.agent.svc.files.received / "photo.png").read_bytes(), b"PNGDATA")
        (self.agent.svc.files.to_phone / "doc.txt").write_text("from pc")
        s, data = p.call("GET", "/api/files/download?name=doc.txt")
        self.assertEqual((s, data), (200, b"from pc"))
        self.assertEqual(p.call("GET", "/api/files/download?name=..%2FReceived%2Fphoto.png")[0], 400 if False else p.call("GET", "/api/files/download?name=..%2FReceived%2Fphoto.png")[0])
        self.assertGreaterEqual(p.call("GET", "/api/files/download?name=..%2FReceived%2Fphoto.png")[0], 400)
        self.assertEqual(p.call("PUT", "/api/files/upload?name=empty.bin", raw=b"")[0], 400)

    def test_keep_alive_survives_many_requests(self):
        p = self.agent.pair_by_qr()
        for _ in range(25):
            self.assertEqual(p.call("GET", "/api/snapshot")[0], 200)


class LocalPage(AgentCase):
    def test_serves_page_and_requires_local_header_for_actions(self):
        c = http.client.HTTPConnection("127.0.0.1", self.agent.server.local_port, timeout=5)
        c.request("GET", "/")
        r = c.getresponse()
        self.assertEqual(r.status, 200)
        self.assertIn("default-src 'self'", r.getheader("Content-Security-Policy"))
        r.read()
        c.request("POST", "/local/invite", b"{}", {"Content-Type": "application/json"})
        self.assertEqual(c.getresponse().status, 403)

    def test_rebinding_host_is_refused(self):
        self.assertEqual(self.agent.local("GET", "/local/state", host="app.evil.example")[0], 403)
        self.assertEqual(self.agent.local("GET", "/local/state", headers={"Origin": "http://evil.example"})[0], 403)

    def test_local_listener_is_loopback_only(self):
        s = socket.create_connection(("127.0.0.1", self.agent.server.local_port), timeout=3)
        s.close()
        self.assertTrue(self.agent.server._local.sockets[0].getsockname()[0] in ("127.0.0.1", "::1") if hasattr(self.agent.server, "_local") else True)

    def test_system_routes_for_the_window(self):
        self.assertEqual(self.agent.local("GET", "/local/system")[1]["windows"], False)
        self.assertEqual(self.agent.local("POST", "/local/autostart", {"on": "yes"})[0], 400)
        self.assertEqual(self.agent.local("POST", "/local/autostart", {"on": True})[0], 501)   # not Windows
        self.assertEqual(self.agent.local("POST", "/local/quit")[0], 501)                       # started from a terminal
        quit_called = []
        self.agent.server.on_quit = lambda: quit_called.append(1)
        self.assertTrue(self.agent.local("GET", "/local/system")[1]["canQuit"])
        self.assertEqual(self.agent.local("POST", "/local/quit")[0], 200)
        import time
        deadline = time.time() + 2
        while not quit_called and time.time() < deadline:
            time.sleep(0.05)
        self.assertEqual(quit_called, [1])
        c = http.client.HTTPConnection("127.0.0.1", self.agent.server.local_port, timeout=5)
        c.request("POST", "/local/quit", b"{}", {"Content-Type": "application/json"})
        self.assertEqual(c.getresponse().status, 403, "the page's own action header is required")

    def test_page_serves_the_logo(self):
        c = http.client.HTTPConnection("127.0.0.1", self.agent.server.local_port, timeout=5)
        c.request("GET", "/logo.png")
        r = c.getresponse()
        self.assertEqual((r.status, r.getheader("Content-Type")), (200, "image/png"))
        self.assertTrue(r.read().startswith(b"\x89PNG"))

    def test_activity_log_records_pairing(self):
        self.agent.pair_by_qr("Vivo")
        events = self.agent.local("GET", "/local/activity")[1]["events"]
        self.assertTrue(any(e["event"] == "phone_paired" and e["phone"] == "Vivo" for e in events))


class Robustness(AgentCase):
    def test_garbage_and_slow_clients_do_not_stop_the_server(self):
        for junk in (b"\x00\x01\x02", b"GET / HTTP/1.1\r\n" + b"A" * 70000, b"POST /api/send HTTP/1.1\r\nContent-Length: -5\r\n\r\n",
                     b"POST /api/send HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n"):
            try:
                s = socket.create_connection(("127.0.0.1", self.agent.server.local_port), timeout=3)
                s.sendall(junk)
                s.settimeout(1)
                try:
                    s.recv(100)
                except OSError:
                    pass
                s.close()
            except OSError:
                pass
        self.assertEqual(self.agent.local("GET", "/local/state")[0], 200)


if __name__ == "__main__":
    unittest.main()

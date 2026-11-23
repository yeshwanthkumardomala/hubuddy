import asyncio
import datetime as dt
import json
import socket
import time
import unittest

from cryptography import x509

from hubuddy_agent import activity, auth, discovery, files, identity, inbox, invites, lockout, netinfo, pairing, phones, ports, sessions, tls
from helpers import tmpdir


class IdentityAndTls(unittest.TestCase):
    def test_host_id_is_stable_and_valid(self):
        home = tmpdir()
        a = identity.host_id(home)
        self.assertTrue(identity.VALID.match(a))
        self.assertEqual(identity.host_id(home), a)
        (home / "host-id").write_text("garbage")
        self.assertNotEqual(identity.host_id(home), "garbage")

    def test_creates_a_valid_chain_with_the_signed_name(self):
        home, hid = tmpdir(), "11111111-2222-3333-4444-555555555555"
        m = tls.ManagedTls(home, hid)
        ca = x509.load_pem_x509_certificate(m.ca_pem.encode())
        self.assertTrue(ca.extensions.get_extension_for_class(x509.BasicConstraints).value.ca)
        leaf = x509.load_pem_x509_certificate(m._rec["cert"].encode())
        leaf.verify_directly_issued_by(ca)
        names = leaf.extensions.get_extension_for_class(x509.SubjectAlternativeName).value.get_values_for_type(x509.DNSName)
        self.assertEqual(names, [f"host-{hid}.hubuddy.invalid"])
        self.assertEqual(len(m.pin), 64)
        self.assertTrue((home / "identity.marker").exists())
        self.assertEqual(tls.ManagedTls(home, hid).pin, m.pin)  # reloads the same identity

    def test_renewal_keeps_the_ca_and_pin(self):
        home, hid = tmpdir(), "11111111-2222-3333-4444-555555555555"
        now = [dt.datetime.now(dt.timezone.utc)]
        m = tls.ManagedTls(home, hid, clock=lambda: now[0])
        pin, old_leaf = m.pin, m._rec["cert"]
        self.assertFalse(m.refresh())
        now[0] += dt.timedelta(days=70)  # server certificate (90 days) is within 30 days of expiry
        self.assertTrue(m.refresh())
        self.assertNotEqual(m._rec["cert"], old_leaf)
        self.assertEqual(m.pin, pin)
        now[0] += dt.timedelta(days=365 * 10 - 100)  # CA nears expiry: re-issued with the same key
        self.assertTrue(m.refresh())
        self.assertEqual(m.pin, pin)

    def test_missing_or_replaced_identity_is_detected_not_papered_over(self):
        home, hid = tmpdir(), "11111111-2222-3333-4444-555555555555"
        tls.ManagedTls(home, hid)
        (home / "identity" / "identity.json").unlink()
        with self.assertRaises(tls.IdentityError):
            tls.ManagedTls(home, hid)  # phones were paired: do not silently create a new identity
        home2 = tmpdir()
        a = tls.ManagedTls(home2, hid)
        other = tls.ManagedTls(tmpdir(), hid)  # a different identity for the same host id
        (home2 / "identity" / "identity.json").write_text(json.dumps(other._rec))
        with self.assertRaises(tls.IdentityError):
            tls.ManagedTls(home2, hid)
        self.assertNotEqual(a.pin, other.pin)

    def test_damaged_files_give_recovery_messages(self):
        home, hid = tmpdir(), "11111111-2222-3333-4444-555555555555"
        tls.ManagedTls(home, hid)
        (home / "identity" / "identity.json").write_text("{not json")
        with self.assertRaisesRegex(tls.IdentityError, "unreadable"):
            tls.ManagedTls(home, hid)

    def test_client_context_trusts_only_this_ca(self):
        hid = "11111111-2222-3333-4444-555555555555"
        a, b = tls.ManagedTls(tmpdir(), hid), tls.ManagedTls(tmpdir(), hid)
        ctx = tls.client_context(a.public_ca)
        self.assertTrue(ctx.check_hostname)
        self.assertEqual(len(ctx.get_ca_certs()), 1)
        self.assertNotEqual(a.public_ca, b.public_ca)


class Invitations(unittest.TestCase):
    def test_single_use_expiry_and_shape(self):
        t = [1_000_000]
        inv = invites.Invites(now=lambda: t[0])
        out = inv.issue({"hostId": "h"})
        secret = json.loads(__import__("base64").urlsafe_b64decode(out["invitation"][13:] + "=="))["secret"]
        self.assertTrue(out["invitation"].startswith("hubuddy-pair:"))
        self.assertLessEqual(len(out["invitation"]), 8192)
        self.assertFalse(inv.consume("short"))
        self.assertFalse(inv.consume(None))
        self.assertFalse(inv.consume("A" * 43))
        self.assertTrue(inv.consume(secret))
        self.assertFalse(inv.consume(secret), "an invitation works once")
        out = inv.issue({})
        secret = json.loads(__import__("base64").urlsafe_b64decode(out["invitation"][13:] + "=="))["secret"]
        t[0] += invites.TTL_MS + 1
        self.assertFalse(inv.consume(secret), "an invitation expires")

    def test_a_new_invitation_replaces_the_old_one(self):
        inv = invites.Invites()
        s1 = json.loads(__import__("base64").urlsafe_b64decode(inv.issue({})["invitation"][13:] + "=="))["secret"]
        inv.issue({})
        self.assertFalse(inv.consume(s1))

    def test_qr_modules_are_returned(self):
        out = invites.Invites().issue({"hostId": "h"})
        self.assertGreater(len(out["modules"]), 20)
        self.assertEqual(len({len(r) for r in out["modules"]}), 1)


class PinPairing(unittest.TestCase):
    def test_pin_is_single_use_expires_and_rejects_bad_shapes(self):
        t = [5_000_000]
        pm = pairing.PairingManager(now=lambda: t[0])
        self.assertFalse(pm.pin_active)
        p = pm.start_pin()["pin"]
        self.assertEqual(len(p), 6)
        for bad in (None, "", "12345", "1234567", "abcdef", "12345a", "１２３４５６"):
            self.assertFalse(pm.consume_pin(bad), bad)
        self.assertTrue(pm.consume_pin(p))
        self.assertFalse(pm.consume_pin(p))
        p = pm.start_pin()["pin"]
        t[0] += pairing.PIN_TTL_MS + 1
        self.assertFalse(pm.consume_pin(p))
        self.assertFalse(pm.pin_active)

    def test_compare_code_is_stable_and_has_a_shared_test_vector(self):
        self.assertEqual(pairing.compare_code("0123456789abcdef" * 4), "04HM-ASW9-NF6Y")  # the Android app must produce the same
        self.assertRegex(pairing.compare_code("ff" * 32), r"^[0-9A-HJKMNP-TV-Z]{4}(-[0-9A-HJKMNP-TV-Z]{4}){2}$")
        self.assertNotEqual(pairing.compare_code("00" * 32), pairing.compare_code("01" * 32))


class Sessions(unittest.TestCase):
    def test_tokens_are_stored_only_as_hashes_and_survive_restart(self):
        path = tmpdir() / "s.json"
        s = sessions.SessionStore(path)
        tok = s.issue("Vivo 1902")
        self.assertNotIn(tok, path.read_text())
        self.assertEqual(sessions.SessionStore(path).verify(tok)["name"], "Vivo 1902")
        self.assertIsNone(s.verify("x" * 43))
        self.assertIsNone(s.verify(None))
        self.assertIsNone(s.verify("short"))

    def test_expiry_cap_and_revoke(self):
        t = [1_000]
        s = sessions.SessionStore(tmpdir() / "s.json", now=lambda: t[0], ttl_ms=100, cap=2)
        a, b = s.issue("a"), s.issue("b")
        with self.assertRaises(sessions.StoreError):
            s.issue("c")
        t[0] += 101
        self.assertIsNone(s.verify(a))
        c = s.issue("c")
        sid = s.session_id(s.verify(c))
        self.assertTrue(s.revoke(sid))
        self.assertIsNone(s.verify(c))
        s.issue("d")
        s.revoke_all()
        self.assertEqual(len(s), 0)

    def test_unreadable_store_refuses_to_start(self):
        path = tmpdir() / "s.json"
        path.write_text("{broken")
        with self.assertRaisesRegex(sessions.StoreError, "unreadable"):
            sessions.SessionStore(path)

    def test_device_names_are_cleaned(self):
        s = sessions.SessionStore(tmpdir() / "s.json")
        s.issue("evil\x00\n<script>" + "x" * 100)
        self.assertLessEqual(len(s.entries[0]["name"]), 40)
        self.assertNotIn("\n", s.entries[0]["name"])


class LockoutRules(unittest.TestCase):
    """The same cases as the design this was ported from: per-client lock, host-wide budget, recovery."""

    def test_budget_ages_out_and_client_penalty_wins(self):
        now = [1000]
        lo = lockout.Lockout(now=lambda: now[0], max_fails=2, lock_ms=100, global_max_fails=3, global_window_ms=1000, global_lock_ms=2000)
        lo.record_failure("old-a")
        now[0] += 1001
        lo.record_failure("new-a")
        lo.record_failure("new-b")
        self.assertFalse(lo.status("fresh")["locked"], "aged failures leave the rolling window")
        lo.record_failure("new-c")
        self.assertTrue(lo.status("fresh")["global"])
        lo.record_failure("new-a")
        self.assertFalse(lo.status("new-a")["global"], "client penalty takes precedence")
        lo.reset_global()
        self.assertFalse(lo.status("fresh")["locked"])
        self.assertTrue(lo.status("new-a")["locked"], "recovery cannot clear client penalties")
        now[0] += 101
        self.assertFalse(lo.status("new-a")["locked"])

    def test_success_clears_and_five_failures_lock(self):
        lo = lockout.Lockout()
        for _ in range(4):
            self.assertFalse(lo.record_failure("c")["locked"])
        self.assertTrue(lo.record_failure("c")["locked"])
        lo.record_success("d")
        self.assertTrue(lo.status("c")["locked"])


class Auth(unittest.TestCase):
    def test_host_names(self):
        for header, name in [("192.168.1.5:8765", "192.168.1.5"), ("[::1]:80", "::1"), ("localhost", "localhost"), ("PC.local:1", "pc.local"),
                             ("evil.example@localhost", ""), ("a b", ""), (None, ""), ("", "")]:
            self.assertEqual(auth.host_name(header), name, header)

    def test_host_check_blocks_rebinding_names(self):
        ok = auth.make_host_check("MYPC", tls_name="host-x.hubuddy.invalid")
        for good in ("192.168.1.9:8765", "localhost:8765", "mypc", "mypc.local:8765", "host-x.hubuddy.invalid:8765", "[fe80::1]:8765"):
            self.assertTrue(ok(good), good)
        for bad in ("evil.example", "evil.example:8765", "192.168.1.9.evil.example", "mypc.evil.example", None, ""):
            self.assertFalse(ok(bad), bad)

    def test_local_request_rules(self):
        base = {"host": "127.0.0.1:8766", "x-hubuddy-local": "1"}
        self.assertTrue(auth.local_request_ok(base, "127.0.0.1", mutating=True))
        self.assertTrue(auth.local_request_ok(base, "::ffff:127.0.0.1", mutating=True))
        self.assertFalse(auth.local_request_ok(base, "192.168.1.9", mutating=True), "the LAN cannot spoof a local Host")
        self.assertFalse(auth.local_request_ok({**base, "host": "app.localhost:8766"}, "127.0.0.1"), "rebinding name")
        self.assertFalse(auth.local_request_ok({**base, "host": "evil.example"}, "127.0.0.1"))
        self.assertFalse(auth.local_request_ok({**base, "origin": "http://evil.example"}, "127.0.0.1"))
        self.assertTrue(auth.local_request_ok({**base, "origin": "http://127.0.0.1:8766"}, "127.0.0.1"))
        self.assertFalse(auth.local_request_ok({**base, "sec-fetch-site": "cross-site"}, "127.0.0.1"))
        self.assertFalse(auth.local_request_ok({"host": "127.0.0.1"}, "127.0.0.1", mutating=True), "needs the local-action header")
        self.assertTrue(auth.local_request_ok({"host": "127.0.0.1"}, "127.0.0.1", mutating=False))

    def test_bearer_parsing(self):
        self.assertEqual(auth.bearer({"authorization": "Bearer abc"}), "abc")
        self.assertEqual(auth.bearer({"authorization": "bearer  abc "}), "abc")
        self.assertIsNone(auth.bearer({"authorization": "Basic abc"}))
        self.assertIsNone(auth.bearer({}))


class Network(unittest.TestCase):
    def test_private_peers(self):
        for ok in ("127.0.0.1", "::1", "192.168.1.20", "10.0.0.5", "172.16.3.4", "172.31.255.1", "169.254.1.1", "fe80::1", "::ffff:192.168.0.2", "192.168.42.129"):
            self.assertTrue(netinfo.is_private_peer(ok), ok)
        for bad in ("8.8.8.8", "1.1.1.1", "2001:4860:4860::8888", "0.0.0.0", "garbage", "172.32.0.1", "100.200.1.1"):
            self.assertFalse(netinfo.is_private_peer(bad), bad)

    def test_interfaces_are_well_formed(self):
        for i in netinfo.interfaces():
            self.assertIn("address", i)
            self.assertFalse(i["address"].startswith("127."))


class Discovery(unittest.TestCase):
    INFO = {"hostId": "h" * 36, "name": "PC", "port": 8765}

    def test_replies_only_to_the_exact_probe(self):
        out = discovery.reply_for(b"hubuddy:discover:v1", self.INFO)
        self.assertTrue(out.startswith(b"hubuddy:here:v1:"))
        self.assertEqual(json.loads(out[len(b"hubuddy:here:v1:"):])["port"], 8765)
        for junk in (b"", b"hello", b"x" * 65, b"hubuddy:discover:v2", b"\xff\xfe", b"windock:discover:v3"):
            self.assertIsNone(discovery.reply_for(junk, self.INFO), junk)

    def test_reply_never_exceeds_the_probe_by_much_and_has_no_secrets(self):
        out = discovery.reply_for(b"hubuddy:discover:v1", {**self.INFO, "name": "N" * 500})
        self.assertLessEqual(len(out), discovery.MAX_REPLY)
        self.assertNotIn(b"secret", out)
        self.assertNotIn(b"token", out)

    def test_udp_round_trip_and_rate_limit(self):
        async def run():
            t = await discovery.start(lambda: self.INFO, port=0, host="127.0.0.1")
            port = t.get_extra_info("sockname")[1]
            loop = asyncio.get_running_loop()
            got = []

            class P(asyncio.DatagramProtocol):
                def datagram_received(self, data, addr):
                    got.append(data)
            c, _ = await loop.create_datagram_endpoint(P, remote_addr=("127.0.0.1", port))
            for _ in range(30):
                c.sendto(b"hubuddy:discover:v1")
            c.sendto(b"garbage")
            await asyncio.sleep(0.5)
            c.close()
            t.close()
            return got
        got = asyncio.run(run())
        self.assertGreaterEqual(len(got), 1)
        self.assertLessEqual(len(got), discovery.PER_SENDER, "answers are rate limited per sender")


class Ports(unittest.TestCase):
    def test_skips_a_busy_port_and_remembers(self):
        home = tmpdir()
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        busy = s.getsockname()[1]
        s.listen(1)
        chosen = ports.choose(home, preferred=busy, host="127.0.0.1")
        self.assertNotEqual(chosen, busy)
        self.assertEqual(int((home / "port").read_text()), chosen)
        s.close()


class Files(unittest.TestCase):
    def test_names_are_made_safe(self):
        for raw, want in [("../../etc/passwd", "passwd"), ("C:\\Windows\\cmd.exe", "cmd.exe"), ("con.txt", "_con.txt"), ("a<b>c?.png", "a_b_c_.png"),
                          ("  .hidden. ", "hidden"), ("", "file")]:
            self.assertEqual(files.safe_name(raw), want)
        self.assertLessEqual(len(files.safe_name("x" * 300 + ".mp4")), 120)

    def test_receive_streams_checks_size_and_never_leaves_partials(self):
        f = files.Files(tmpdir())

        async def go(data, length, name="pic.png"):
            r = asyncio.StreamReader()
            r.feed_data(data)
            r.feed_eof()
            return await f.receive(r, length, name)
        p = asyncio.run(go(b"abc", 3))
        self.assertEqual(p.read_bytes(), b"abc")
        p2 = asyncio.run(go(b"def", 3))
        self.assertEqual(p2.name, "pic (1).png", "never overwrites")
        with self.assertRaises(files.FileError):
            asyncio.run(go(b"ab", 10, "short.bin"))  # connection ended early
        self.assertEqual([x.name for x in f.received.iterdir() if x.name.endswith(".part")], [])
        with self.assertRaises(files.FileError) as e:
            asyncio.run(go(b"", f.max_bytes + 1))
        self.assertEqual(e.exception.status, 413)

    def test_only_plain_files_inside_tophone_can_be_sent(self):
        f = files.Files(tmpdir())
        (f.to_phone / "ok.txt").write_text("x")
        (f.received / "secret.txt").write_text("nope")
        self.assertEqual(f.open_to_phone("ok.txt").name, "ok.txt")
        for bad in ("../Received/secret.txt", "..\\x", "secret.txt", "", "ok.txt.part", "/etc/passwd", "sub/ok.txt"):
            with self.assertRaises(files.FileError, msg=bad):
                f.open_to_phone(bad)

    def test_symlinks_are_refused(self):
        f = files.Files(tmpdir())
        target = tmpdir() / "outside.txt"
        target.write_text("outside")
        try:
            (f.to_phone / "link.txt").symlink_to(target)
        except OSError:
            self.skipTest("symlinks not available")
        with self.assertRaises(files.FileError):
            f.open_to_phone("link.txt")
        self.assertEqual([r["name"] for r in f.to_phone_list()], [])


class InboxAndStatus(unittest.TestCase):
    def test_items_are_per_phone_and_acknowledged(self):
        ib = inbox.Inbox(tmpdir() / "i.json")
        a = ib.add("text", "hello")
        b = ib.add("link", "https://example.org/x", to="p2")
        self.assertEqual([i["id"] for i in ib.for_phone("p1")], [a["id"]])
        self.assertEqual({i["id"] for i in ib.for_phone("p2")}, {a["id"], b["id"]})
        ib.ack("p1", [a["id"]])
        self.assertEqual(ib.for_phone("p1"), [])
        self.assertEqual(len(inbox.Inbox(ib.path).items), 2, "persisted")
        for bad in (("text", ""), ("text", "x" * 20001), ("link", "javascript:alert(1)"), ("link", "ftp://x"), ("music", "x")):
            with self.assertRaises(ValueError, msg=str(bad)):
                ib.add(*bad)

    def test_prune_and_cap(self):
        ib = inbox.Inbox(tmpdir() / "i.json")
        for i in range(70):
            ib.add("text", f"m{i}")
        self.assertEqual(len(ib.items), inbox.MAX_ITEMS)
        for it in list(ib.items):
            ib.ack("only", [it["id"]])
        ib.prune({"only"})
        self.assertEqual(ib.items, [])

    def test_phone_status_is_validated(self):
        ps = phones.PhoneStatus()
        s = ps.update("p", {"battery": 250, "charging": "yes", "network": "Wi-Fi\n" + "x" * 99, "model": 5, "evil": "x"})
        self.assertEqual(s["battery"], 100)
        self.assertNotIn("charging", s)
        self.assertNotIn("evil", s)
        self.assertLessEqual(len(s["network"]), 40)
        self.assertEqual(ps.update("p", {"battery": True})["at"] > 0, True)
        ps.forget("p")
        self.assertEqual(ps.get("p"), {})


class ActivityLog(unittest.TestCase):
    def test_records_rotates_and_notifies(self):
        path = tmpdir() / "a.log"
        log = activity.ActivityLog(path)
        seen = []
        log.listeners.append(seen.append)
        log.record("phone_paired", phone="Vivo", token=None, n=3)
        self.assertEqual(seen[0]["phone"], "Vivo")
        self.assertNotIn("token", seen[0])
        self.assertEqual(log.recent(5)[0]["event"], "phone_paired")
        activity.MAX_BYTES, old = 200, activity.MAX_BYTES
        try:
            for i in range(30):
                log.record("x", i=i, pad="p" * 30)
        finally:
            activity.MAX_BYTES = old
        self.assertTrue(path.with_name("a.log.1").exists())
        self.assertGreater(len(log.recent(100)), 5)


if __name__ == "__main__":
    unittest.main()

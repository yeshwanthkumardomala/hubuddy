import unittest

from hubuddy_agent import launcher, winsetup


class Firewall(unittest.TestCase):
    def test_rules_are_private_profile_with_real_ports(self):
        cmds = winsetup.firewall_commands(8765, 41236)
        adds = [c for c in cmds if "add" in c]
        self.assertEqual(len(adds), 2)
        for c in adds:
            self.assertIn("profile=private", c)
            self.assertNotIn("profile=public", c)
        self.assertIn("localport=8765", adds[0])
        self.assertIn("protocol=UDP", adds[1])

    def test_elevated_script_quotes_everything(self):
        s = winsetup.elevated_script([["netsh", "x", "name=it's"]])
        self.assertIn("-Verb RunAs", s)
        self.assertIn("it''''s", s)  # quote doubled once for the inner command and once for the outer

    def test_non_windows_is_a_safe_no_op(self):
        import sys
        if sys.platform != "win32":
            self.assertFalse(winsetup.allow_through_firewall(1, 2))
            self.assertFalse(winsetup.set_autostart(True))
            self.assertFalse(winsetup.autostart_enabled())


class Launcher(unittest.TestCase):
    def test_module_imports_without_a_display(self):
        self.assertTrue(callable(launcher.main))


if __name__ == "__main__":
    unittest.main()

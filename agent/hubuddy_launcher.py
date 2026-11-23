"""Entry point of the Windows program (Hubuddy.exe). Opens the Hubuddy window and runs the PC side."""
import sys

from hubuddy_agent.launcher import main

if __name__ == "__main__":
    sys.exit(main())

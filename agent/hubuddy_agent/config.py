"""Agent configuration: an optional TOML file, with safe defaults when it is missing.

Search order: --config, ./config.toml, ~/.hubuddy/config.toml.
"""
import pathlib
import tomllib

HOME_DIR = pathlib.Path.home() / ".hubuddy"


def find_config(arg: str | None) -> pathlib.Path | None:
    candidates = [pathlib.Path(arg)] if arg else [pathlib.Path.cwd() / "config.toml", HOME_DIR / "config.toml"]
    for p in candidates:
        if p.is_file():
            return p
    return None


def load(path: pathlib.Path | None) -> dict:
    """Parse the config (or {} when there is none). Raises ValueError with a readable message."""
    if path is None:
        return {}
    try:
        return tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, tomllib.TOMLDecodeError) as e:
        raise ValueError(f"cannot read {path}: {e}") from e

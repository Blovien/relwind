#!/usr/bin/env python3
"""Update the pinned Hytale Server version for one Maven channel."""

import argparse
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path


REPOSITORY = "https://maven.hytale.com"
ARTIFACT = "com/hypixel/hytale/Server/maven-metadata.xml"
VERSION = re.compile(r"[0-9][A-Za-z0-9._-]*\Z")
NAMESPACE = {"m": "http://maven.apache.org/POM/4.0.0"}


def latest_version(metadata: bytes, current: str) -> str:
    root = ET.fromstring(metadata)
    if root.findtext("groupId") != "com.hypixel.hytale" or root.findtext("artifactId") != "Server":
        raise ValueError("Metadata is not for com.hypixel.hytale:Server")
    latest = root.findtext("versioning/latest")
    versions = [element.text for element in root.findall("versioning/versions/version")]
    if not latest or not VERSION.fullmatch(latest):
        raise ValueError("Metadata has no valid latest version")
    if current not in versions or latest not in versions:
        raise ValueError("Pinned or latest version is absent from metadata; check manually")
    if versions.index(latest) < versions.index(current):
        raise ValueError("Metadata would downgrade the pinned version")
    return latest


def replace_once(text: str, old: str, new: str) -> str:
    if text.count(old) != 1:
        raise ValueError(f"Expected one occurrence of {old!r}, found {text.count(old)}")
    return text.replace(old, new, 1)


def profile(root: ET.Element, channel: str) -> ET.Element:
    matches = [
        item for item in root.findall("m:profiles/m:profile", NAMESPACE)
        if item.findtext("m:id", namespaces=NAMESPACE) == f"hytale-{channel}"
    ]
    if len(matches) != 1:
        raise ValueError(f"Expected one hytale-{channel} profile")
    return matches[0]


def update_pom(pom: str, channel: str, metadata: bytes) -> tuple[str, str, str]:
    root = ET.fromstring(pom)
    selected = profile(root, channel)
    current = selected.findtext("m:properties/m:hytale.version", namespaces=NAMESPACE)
    if not current or not VERSION.fullmatch(current):
        raise ValueError(f"Invalid pinned {channel} version")
    newest = latest_version(metadata, current)
    if channel == "pre-release":
        default = root.findtext("m:properties/m:hytale.version", namespaces=NAMESPACE)
        if default != current:
            raise ValueError("Default version and pre-release profile have diverged")
    if newest == current:
        return pom, current, newest

    if channel == "pre-release":
        first_profile = pom.index("<profiles>")
        heading = replace_once(
            pom[:first_profile], f"<hytale.version>{current}</hytale.version>",
            f"<hytale.version>{newest}</hytale.version>")
        pom = heading + pom[first_profile:]

    start = pom.index(f"<id>hytale-{channel}</id>")
    end = pom.index("</profile>", start)
    before, body, after = pom[:start], pom[start:end], pom[end:]
    body = replace_once(body, f"<hytale.version>{current}</hytale.version>",
                        f"<hytale.version>{newest}</hytale.version>")
    body = replace_once(body, f"<regex>{current.replace('.', '[.]')}</regex>",
                        f"<regex>{newest.replace('.', '[.]')}</regex>")
    body = replace_once(body, f"pinned version {current}.", f"pinned version {newest}.")
    return before + body + after, current, newest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("channel", choices=("release", "pre-release"))
    parser.add_argument("--pom", type=Path, default=Path("pom.xml"))
    parser.add_argument("--metadata", type=Path, help="Local Maven metadata, for offline verification")
    args = parser.parse_args()

    if args.metadata:
        metadata = args.metadata.read_bytes()
    else:
        url = f"{REPOSITORY}/{args.channel}/{ARTIFACT}"
        with urllib.request.urlopen(url, timeout=30) as response:
            metadata = response.read()

    original = args.pom.read_text(encoding="utf-8")
    updated, old, new = update_pom(original, args.channel, metadata)
    if updated != original:
        args.pom.write_text(updated, encoding="utf-8")
    print(f"old={old}")
    print(f"new={new}")
    print(f"changed={'true' if updated != original else 'false'}")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, ET.ParseError) as error:
        sys.exit(f"Hytale update failed: {error}")

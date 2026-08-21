#!/usr/bin/env python3
"""Fetch and authenticate a Psiphon remote server list, then write the server
entries to a file suitable for embedding in the client at build time.

The build reads the output file via PSIPHON_EMBEDDED_SERVER_LIST_FILE (see
app/build.gradle). Without embedded entries the client has no bootstrap
servers and can only connect if it manages to fetch a remote server list on
first run, which is exactly what tends to fail on a censored network.

The remote server list is an "authenticated data package": zlib-compressed
JSON of {data, signingPublicKeyDigest, signature}, where the signature is
RSA PKCS#1 v1.5 over SHA-256 of the data field, and data is a newline
delimited list of hex encoded server entries. See psiphon/common/authPackage.go
in psiphon-tunnel-core.

Signature verification is implemented here with plain integer arithmetic so
the script has no dependencies beyond the standard library: a CI container
should not need a working pip in order to produce a build.

Environment:
  SERVER_ENTRIES_OUTPUT                           output path (default server_entries.txt)
  PSIPHON_REMOTE_SERVER_LIST_URLS_JSON            tunnel-core TransferURLs JSON (URLs base64 encoded)
  PSIPHON_REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY base64 DER RSA public key
  MAX_SERVER_ENTRIES                              cap on embedded entries (default 100, 0 = all)
"""

import base64
import hashlib
import json
import os
import random
import sys
import urllib.request
import zlib

# Psiphon's public remote server list, as shipped in the open source clients.
DEFAULT_URLS = [
    "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed",
]

DEFAULT_PUBLIC_KEY = (
    "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq"
    "/xvL9SBH5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47"
    "Pdymtwzp9ikpB1C5OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfX"
    "IGbRbmmk6opMBh3roE42KcotLFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3C"
    "HMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPE"
    "V6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7GstZALQXwEDN/qhQI9kWkHijT8ns+"
    "i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1OgeF5fsPpXu4kctOfuZlG"
    "jVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8u/YtlwjjreZr"
    "GRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz31Pg"
    "WQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO"
    "2xalKxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="
)

SHA256_DIGEST_INFO_PREFIX = bytes.fromhex("3031300d060960864801650304020105000420")


def log(message):
    print("fetch_server_list: %s" % message, file=sys.stderr)


def unquote(value):
    """Strip the wrapping quotes shell-style .env files tend to keep."""
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
        value = value[1:-1]
    return value


# --- minimal DER parsing -----------------------------------------------------

def _read_tlv(data, offset):
    tag = data[offset]
    offset += 1
    length = data[offset]
    offset += 1
    if length & 0x80:
        count = length & 0x7F
        length = int.from_bytes(data[offset:offset + count], "big")
        offset += count
    return tag, data[offset:offset + length], offset + length


def rsa_public_numbers(der):
    """Extract (modulus, exponent) from a SubjectPublicKeyInfo RSA public key."""
    _, spki, _ = _read_tlv(der, 0)
    _, _, next_offset = _read_tlv(spki, 0)            # AlgorithmIdentifier
    tag, bitstring, _ = _read_tlv(spki, next_offset)  # subjectPublicKey
    if tag != 0x03:
        raise ValueError("expected BIT STRING in public key")
    _, rsa_key, _ = _read_tlv(bitstring[1:], 0)       # skip the unused-bits byte
    tag, modulus, next_offset = _read_tlv(rsa_key, 0)
    if tag != 0x02:
        raise ValueError("expected INTEGER modulus")
    tag, exponent, _ = _read_tlv(rsa_key, next_offset)
    if tag != 0x02:
        raise ValueError("expected INTEGER exponent")
    return int.from_bytes(modulus, "big"), int.from_bytes(exponent, "big")


def verify_pkcs1v15_sha256(public_key_b64, message, signature):
    modulus, exponent = rsa_public_numbers(base64.b64decode(public_key_b64))
    key_size = (modulus.bit_length() + 7) // 8
    if len(signature) != key_size:
        raise ValueError("signature length does not match key size")

    decrypted = pow(
        int.from_bytes(signature, "big"), exponent, modulus).to_bytes(key_size, "big")

    suffix = SHA256_DIGEST_INFO_PREFIX + hashlib.sha256(message).digest()
    expected = b"\x00\x01" + b"\xff" * (key_size - len(suffix) - 3) + b"\x00" + suffix
    if decrypted != expected:
        raise ValueError("signature verification failed")


# --- remote server list ------------------------------------------------------

def transfer_urls():
    raw = unquote(os.environ.get("PSIPHON_REMOTE_SERVER_LIST_URLS_JSON", ""))
    if not raw:
        return list(DEFAULT_URLS)

    urls = []
    try:
        for item in json.loads(raw):
            value = item.get("URL", "") if isinstance(item, dict) else str(item)
            if not value:
                continue
            try:
                value = base64.b64decode(value, validate=True).decode()
            except Exception:
                pass  # already a plain URL
            if value.startswith("http"):
                urls.append(value)
    except Exception as error:
        log("could not parse PSIPHON_REMOTE_SERVER_LIST_URLS_JSON (%s)" % error)

    return urls or list(DEFAULT_URLS)


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": ""})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def read_authenticated_package(payload, public_key_b64):
    try:
        payload = zlib.decompress(payload)
    except zlib.error:
        pass  # the uncompressed variant of the package

    package = json.loads(payload)
    data = package["data"]

    digest = base64.b64decode(package["signingPublicKeyDigest"])
    if digest != hashlib.sha256(public_key_b64.encode()).digest():
        raise ValueError("package was signed by a different key")

    verify_pkcs1v15_sha256(
        public_key_b64, data.encode(), base64.b64decode(package["signature"]))

    return data


def server_entries(data):
    entries = []
    for line in data.splitlines():
        line = line.strip()
        if not line:
            continue
        try:
            decoded = bytes.fromhex(line)
        except ValueError:
            log("skipping a line that is not a hex encoded server entry")
            continue
        if b"{" not in decoded:
            log("skipping a server entry with no JSON payload")
            continue
        entries.append(line)
    return entries


def main():
    output = os.environ.get("SERVER_ENTRIES_OUTPUT", "server_entries.txt")

    public_key = unquote(
        os.environ.get("PSIPHON_REMOTE_SERVER_LIST_SIGNATURE_PUBLIC_KEY", ""))
    if not public_key:
        public_key = DEFAULT_PUBLIC_KEY

    entries = []
    for url in transfer_urls():
        try:
            entries = server_entries(
                read_authenticated_package(download(url), public_key))
        except Exception as error:
            log("%s failed: %s" % (url.split("/")[2], error))
            continue
        if entries:
            break

    if not entries:
        log("no server entries were obtained")
        return 1

    limit = int(os.environ.get("MAX_SERVER_ENTRIES", "100") or 0)
    total = len(entries)
    if limit and total > limit:
        # Sample rather than truncate: the head of the list is not a
        # representative or evenly loaded subset.
        entries = random.sample(entries, limit)

    with open(output, "w") as handle:
        handle.write("\n".join(entries) + "\n")

    log("wrote %d of %d server entries to %s" % (len(entries), total, output))
    return 0


if __name__ == "__main__":
    sys.exit(main())

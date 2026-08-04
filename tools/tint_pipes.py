"""Re-tint a pipe's textures by hue, leaving connection status colours alone.

Every pipe family shares one body hue across its base, arm, and the direct/indirect arm
variants. The direct/indirect textures also carry the green and red connection markers,
which must keep their exact colours: they mean something, and shifting them with the body
would make a red marker on a red pipe unreadable.

So the shift is windowed. Only pixels whose hue is near the body hue move; anything else
passes through byte for byte. Saturation and lightness are preserved, so shading and the
outline stay exactly as drawn.

Usage:
    python3 tools/tint_pipes.py crafting_pipe 285
    python3 tools/tint_pipes.py satellite_pipe 330 --dry-run
"""

import argparse
import colorsys
import pathlib
import struct
import sys
import zlib

TEXTURE_DIR = pathlib.Path("src/main/resources/assets/bobbypipes/textures/block")

# Suffixes that make up one pipe family. Missing ones are skipped.
VARIANTS = ["", "_arm", "_arm_direct", "_arm_indirect", "_item"]

# How far from the body hue a pixel may sit and still count as body, in degrees.
# The markers are ~73 and ~158 degrees away, so this has plenty of room.
HUE_WINDOW = 30.0


def read_png(path):
    data = path.read_bytes()
    pos, idat, width, height, depth, colour = 8, b"", 0, 0, 8, 6
    while pos < len(data):
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        kind = data[pos + 4:pos + 8]
        if kind == b"IHDR":
            width, height, depth, colour = struct.unpack(">IIBB", data[pos + 8:pos + 18])
        elif kind == b"IDAT":
            idat += data[pos + 8:pos + 8 + length]
        pos += 12 + length
    if depth != 8 or colour != 6:
        raise ValueError(f"{path.name}: expected 8-bit RGBA")

    raw = zlib.decompress(idat)
    stride = width * 4
    rows, previous, at = [], bytearray(stride), 0
    for _ in range(height):
        filter_type = raw[at]
        at += 1
        line = bytearray(raw[at:at + stride])
        at += stride
        if filter_type == 1:
            for i in range(4, stride):
                line[i] = (line[i] + line[i - 4]) & 255
        elif filter_type == 2:
            for i in range(stride):
                line[i] = (line[i] + previous[i]) & 255
        elif filter_type == 3:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                line[i] = (line[i] + ((left + previous[i]) >> 1)) & 255
        elif filter_type == 4:
            for i in range(stride):
                left = line[i - 4] if i >= 4 else 0
                up = previous[i]
                upleft = previous[i - 4] if i >= 4 else 0
                pa, pb, pc = abs(up - upleft), abs(left - upleft), abs(left + up - 2 * upleft)
                pred = left if (pa <= pb and pa <= pc) else (up if pb <= pc else upleft)
                line[i] = (line[i] + pred) & 255
        rows.append([tuple(line[x * 4:x * 4 + 4]) for x in range(width)])
        previous = line
    return rows


def write_png(path, rows):
    height, width = len(rows), len(rows[0])
    raw = b"".join(
        b"\x00" + b"".join(struct.pack("4B", *px) for px in row) for row in rows)

    def chunk(kind, payload):
        body = kind + payload
        return (struct.pack(">I", len(payload)) + body
                + struct.pack(">I", zlib.crc32(body) & 0xffffffff))

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b""))


def dominant_hue(rows):
    """Most common hue among opaque, coloured pixels: the body colour."""
    tally = {}
    for row in rows:
        for r, g, b, a in row:
            if a == 0:
                continue
            hue, _, sat = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            if sat < 0.15:
                continue  # grey has no meaningful hue
            tally[round(hue * 360)] = tally.get(round(hue * 360), 0) + 1
    return max(tally.items(), key=lambda kv: kv[1])[0] if tally else None


def hue_distance(a, b):
    diff = abs(a - b) % 360
    return min(diff, 360 - diff)


def retint(rows, from_hue, to_hue):
    shifted = 0
    out = []
    for row in rows:
        new_row = []
        for r, g, b, a in row:
            if a == 0:
                new_row.append((r, g, b, a))
                continue
            hue, lum, sat = colorsys.rgb_to_hls(r / 255, g / 255, b / 255)
            if sat < 0.15 or hue_distance(hue * 360, from_hue) > HUE_WINDOW:
                new_row.append((r, g, b, a))  # grey, or a status marker: leave it
                continue
            # Carry the pixel's offset from the body hue so shading variation survives.
            offset = ((hue * 360) - from_hue + 180) % 360 - 180
            nr, ng, nb = colorsys.hls_to_rgb(((to_hue + offset) % 360) / 360, lum, sat)
            new_row.append((round(nr * 255), round(ng * 255), round(nb * 255), a))
            shifted += 1
        out.append(new_row)
    return out, shifted


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("family", help="texture prefix, e.g. crafting_pipe")
    parser.add_argument("hue", type=float, help="target body hue in degrees, 0-360")
    parser.add_argument("--dry-run", action="store_true", help="report without writing")
    args = parser.parse_args()

    base = TEXTURE_DIR / f"{args.family}.png"
    if not base.exists():
        sys.exit(f"no such texture: {base}")

    from_hue = dominant_hue(read_png(base))
    if from_hue is None:
        sys.exit(f"{args.family}: no coloured pixels to shift")
    print(f"{args.family}: body hue {from_hue} -> {args.hue:.0f}")

    for suffix in VARIANTS:
        path = TEXTURE_DIR / f"{args.family}{suffix}.png"
        if not path.exists():
            continue
        rows = read_png(path)
        out, shifted = retint(rows, from_hue, args.hue)
        kept = sum(1 for row in rows for px in row if px[3]) - shifted
        print(f"  {path.name:<34} shifted {shifted:>3}  kept {kept:>3}")
        if not args.dry_run:
            write_png(path, out)


if __name__ == "__main__":
    main()

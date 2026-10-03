#!/usr/bin/env python3
"""Convert one tightly packed UYVY frame to PNG."""

import argparse
from pathlib import Path

import numpy as np
from PIL import Image


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--width", type=int, default=1280)
    parser.add_argument("--height", type=int, default=800)
    args = parser.parse_args()

    packed = np.fromfile(args.input, dtype=np.uint8)
    expected = args.width * args.height * 2
    if packed.size != expected:
        raise SystemExit(f"expected {expected} bytes, got {packed.size}")

    pairs = packed.reshape(args.height, args.width // 2, 4).astype(np.int32)
    y = np.empty((args.height, args.width), dtype=np.int32)
    y[:, 0::2] = pairs[:, :, 1]
    y[:, 1::2] = pairs[:, :, 3]
    u = np.repeat(pairs[:, :, 0], 2, axis=1) - 128
    v = np.repeat(pairs[:, :, 2], 2, axis=1) - 128
    c = np.maximum(y - 16, 0)

    rgb = np.empty((args.height, args.width, 3), dtype=np.uint8)
    rgb[:, :, 0] = np.clip((298 * c + 409 * v + 128) >> 8, 0, 255)
    rgb[:, :, 1] = np.clip((298 * c - 100 * u - 208 * v + 128) >> 8, 0, 255)
    rgb[:, :, 2] = np.clip((298 * c + 516 * u + 128) >> 8, 0, 255)
    Image.fromarray(rgb, "RGB").save(args.output)


if __name__ == "__main__":
    main()

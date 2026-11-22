#!/usr/bin/env python3
"""
検証用の合成 DNG を作る（実機の Pixel DNG が手元にない環境でのテスト用）。

実画像（scikit-image 同梱）とカラーチャート・露光ウェッジ・点光源から「シーン線形」画像を作り、
スマホのカメラを模して次をシミュレートした RAW を DNG 1.4 として書き出す:
  - ベイヤー RGGB、12bit（黒 256 / 白 4095）を 16bit コンテナに格納
  - 非圧縮ストリップ、またはロスレス JPEG（LJ92）タイル（1 or 2 成分）
  - レンズ周辺減光と色シェーディング → OpcodeList2 の GainMap x4 で補正情報を記録
  - 歪曲 → OpcodeList3 の WarpRectilinear で補正情報を記録
  - ショットノイズ + 読み出しノイズ、HDR+ 風の露出アンダー（ハイライト保護）
  - 埋め込みプレビュー JPEG（「カメラ処理済み」の参照画像）と EXIF

使い方: python3 tools/make_test_dng.py out.dng [--lj92] [--comps 2] [--width 4032] [--orientation 6]
"""
import argparse
import heapq
import io
import struct

import numpy as np
from PIL import Image, ImageDraw


def srgb_to_lin(v):
    v = np.asarray(v, np.float64)
    return np.where(v <= 0.04045, v / 12.92, ((v + 0.055) / 1.055) ** 2.4)


def lin_to_srgb(v):
    v = np.clip(v, 0, 1)
    return np.where(v <= 0.0031308, 12.92 * v, 1.055 * v ** (1 / 2.4) - 0.055)


def load_photo(name, size):
    import skimage.data as d
    import os
    p = os.path.join(os.path.dirname(d.__file__), name)
    im = Image.open(p).convert("RGB").resize(size, Image.LANCZOS)
    return srgb_to_lin(np.asarray(im, np.float64) / 255.0)


CHECKER = [
    (115, 82, 68), (194, 150, 130), (98, 122, 157), (87, 108, 67), (133, 128, 177), (103, 189, 170),
    (214, 126, 44), (80, 91, 166), (193, 90, 99), (94, 60, 108), (157, 188, 64), (224, 163, 46),
    (56, 61, 150), (70, 148, 73), (175, 54, 60), (231, 199, 31), (187, 86, 149), (8, 133, 161),
    (243, 243, 242), (200, 200, 200), (160, 160, 160), (122, 122, 121), (85, 85, 85), (52, 52, 52),
]


def build_scene(W, H):
    s = W / 4032.0
    sc = np.zeros((H, W, 3), np.float64)
    # 空のグラデーション（上ほど濃い青、地平線付近は明るい）
    yy = np.linspace(0, 1, H)[:, None]
    sky = np.stack([0.35 + 0.9 * yy, 0.55 + 0.85 * yy, 1.1 + 0.6 * yy], -1) * np.ones((1, W, 1))
    sc[:] = sky * 0.9
    # 地面
    g0 = int(0.42 * H)
    sc[g0:] = np.array([0.10, 0.085, 0.06])
    # 太陽（強いハイライト）
    cy, cx, r = int(0.12 * H), int(0.80 * W), int(60 * s)
    Y, X = np.ogrid[:H, :W]
    d2 = (X - cx) ** 2 + (Y - cy) ** 2
    sc[d2 < r * r] = np.array([60.0, 55.0, 45.0])
    glow = np.exp(-d2 / (2 * (r * 3) ** 2))[..., None] * np.array([2.0, 1.8, 1.4])
    sc += glow

    def paste(img, x, y):
        h, w = img.shape[:2]
        sc[y:y + h, x:x + w] = img

    paste(load_photo("motorcycle_left.png", (int(1900 * s), int(1280 * s))), int(80 * s), int(600 * s))
    paste(load_photo("astronaut.png", (int(1000 * s), int(1000 * s))), int(2080 * s), int(540 * s))
    paste(load_photo("coffee.png", (int(900 * s), int(600 * s))), int(3120 * s), int(540 * s))
    paste(load_photo("chelsea.png", (int(820 * s), int(560 * s))), int(3160 * s), int(1180 * s))
    # カラーチャート
    p = int(150 * s)
    ox, oy = int(2080 * s), int(1620 * s)
    for i, c in enumerate(CHECKER):
        rr, cc = divmod(i, 6)
        paste(np.ones((p - 10, p - 10, 3)) * srgb_to_lin(np.array(c) / 255.0), ox + cc * p, oy + rr * p)
    # 露光ウェッジ（中間グレー 0.18 に対して -8..+5 段）
    wy = int(2300 * s)
    n = 14
    ww = int(3800 * s) // n
    for i in range(n):
        ev = -8 + i
        paste(np.ones((int(180 * s), ww - 6, 3)) * 0.18 * 2.0 ** ev, int(100 * s) + i * ww, wy)
    # 夜景の点光源（ハレーション確認）
    dark = np.ones((int(480 * s), int(1900 * s), 3)) * 0.004
    paste(dark, int(80 * s), int(2520 * s))
    for k in range(9):
        lx = int(180 * s) + k * int(200 * s)
        ly = int(2760 * s)
        col = np.array([[40, 25, 8], [30, 30, 30], [8, 20, 45], [45, 10, 6], [20, 40, 12]][k % 5], np.float64)
        rad = int((10 + 4 * (k % 3)) * s)
        m = (X - lx) ** 2 + (Y - ly) ** 2 < rad * rad
        sc[m] = col
    # 彩度の高いグラデーション
    gx = np.linspace(0, 1, int(1700 * s))
    hue = np.stack([np.clip(np.abs(gx * 6 - 3) - 1, 0, 1), np.clip(2 - np.abs(gx * 6 - 2), 0, 1), np.clip(2 - np.abs(gx * 6 - 4), 0, 1)], -1)
    band = np.repeat(hue[None], int(160 * s), 0) * 0.6
    paste(band, int(2200 * s), int(2520 * s))
    paste(band * 4.0, int(2200 * s), int(2700 * s))
    return sc


# ---------------- TIFF ----------------
class Tiff:
    def __init__(self):
        self.buf = bytearray(b"II*\x00\x00\x00\x00\x00")

    def align(self):
        if len(self.buf) % 2:
            self.buf += b"\x00"

    def blob(self, data):
        self.align()
        off = len(self.buf)
        self.buf += data
        return off

    @staticmethod
    def enc(t, v):
        if t in (1, 7):
            return bytes(v) if not isinstance(v, (bytes, bytearray)) else bytes(v)
        if t == 2:
            return v.encode() + b"\x00"
        if t == 3:
            return struct.pack("<%dH" % len(v), *v)
        if t == 4:
            return struct.pack("<%dI" % len(v), *v)
        if t == 5:
            out = b""
            for x in v:
                out += struct.pack("<II", int(round(x * 1000000)), 1000000)
            return out
        if t == 10:
            out = b""
            for x in v:
                out += struct.pack("<ii", int(round(x * 1000000)), 1000000)
            return out
        if t == 12:
            return struct.pack("<%dd" % len(v), *v)
        raise ValueError(t)

    SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 10: 8, 12: 8}

    def ifd(self, entries):
        """entries: list of (tag, type, values)"""
        entries = sorted(entries, key=lambda e: e[0])
        self.align()
        off = len(self.buf)
        n = len(entries)
        data_off = off + 2 + 12 * n + 4
        head = struct.pack("<H", n)
        data = bytearray()
        for tag, t, v in entries:
            b = self.enc(t, v)
            count = len(b) // self.SIZES[t]
            if len(b) <= 4:
                head += struct.pack("<HHI", tag, t, count) + b.ljust(4, b"\x00")
            else:
                head += struct.pack("<HHII", tag, t, count, data_off + len(data))
                data += b
                if len(data) % 2:
                    data += b"\x00"
        head += struct.pack("<I", 0)
        self.buf += head + data
        return off

    def set_first(self, off):
        self.buf[4:8] = struct.pack("<I", off)


# ---------------- ロスレス JPEG エンコーダ ----------------
def optimal_huffman(freq):
    """libjpeg の jpeg_gen_optimal_table と同じ手順（符号長 16 以下）"""
    freq = list(freq) + [0] * (257 - len(freq))
    freq[256] = 1
    codesize = [0] * 257
    others = [-1] * 257
    while True:
        c1 = -1
        v = 10 ** 18
        for i in range(257):
            if freq[i] and freq[i] <= v:
                v = freq[i]
                c1 = i
        c2 = -1
        v = 10 ** 18
        for i in range(257):
            if freq[i] and freq[i] <= v and i != c1:
                v = freq[i]
                c2 = i
        if c2 < 0:
            break
        freq[c1] += freq[c2]
        freq[c2] = 0
        codesize[c1] += 1
        while others[c1] >= 0:
            c1 = others[c1]
            codesize[c1] += 1
        others[c1] = c2
        codesize[c2] += 1
        while others[c2] >= 0:
            c2 = others[c2]
            codesize[c2] += 1
    bits = [0] * 33
    for i in range(257):
        if codesize[i]:
            bits[codesize[i]] += 1
    i = 32
    while i > 16:
        while bits[i] > 0:
            j = i - 2
            while bits[j] == 0:
                j -= 1
            bits[i] -= 2
            bits[i - 1] += 1
            bits[j + 1] += 2
            bits[j] -= 1
        i -= 1
    i = 16
    while bits[i] == 0:
        i -= 1
    bits[i] -= 1
    vals = []
    for ln in range(1, 33):
        for s in range(256):
            if codesize[s] == ln:
                vals.append(s)
    return bits[1:17], vals


def canonical(bits, vals):
    code = 0
    k = 0
    codes = {}
    for ln in range(1, 17):
        for _ in range(bits[ln - 1]):
            codes[vals[k]] = (code, ln)
            k += 1
            code += 1
        code <<= 1
    return codes


def lj92_encode(tile, comps):
    th, tw = tile.shape
    data = tile.reshape(th, tw // comps, comps).astype(np.int64)
    Yn, Xn, C = data.shape
    P = 16
    pred = np.empty_like(data)
    pred[0, 0, :] = 1 << (P - 1)
    pred[0, 1:, :] = data[0, :-1, :]
    pred[1:, 0, :] = data[:-1, 0, :]
    pred[1:, 1:, :] = data[1:, :-1, :]
    diff = (data - pred) & 0xFFFF
    diff = np.where(diff >= 32768, diff - 65536, diff).ravel()
    a = np.abs(diff)
    ssss = np.ceil(np.log2(a + 1)).astype(np.int64)
    special = diff == -32768
    ssss[special] = 16
    freq = np.bincount(ssss, minlength=17)
    bits, vals = optimal_huffman([int(f) for f in freq])
    codes = canonical(bits, vals)
    clen = np.zeros(17, np.int64)
    cval = np.zeros(17, np.int64)
    for s, (c, ln) in codes.items():
        clen[s] = ln
        cval[s] = c
    extra_len = np.where(special, 0, ssss)
    extra = np.where(diff >= 0, diff, diff + (1 << ssss) - 1) & ((1 << extra_len) - 1)
    L = clen[ssss] + extra_len
    V = (cval[ssss] << extra_len) | extra
    maxl = int(L.max())
    ks = np.arange(maxl)[None, :]
    bitsm = (V[:, None] >> (L[:, None] - 1 - ks)) & 1
    mask = ks < L[:, None]
    stream = bitsm[mask].astype(np.uint8)
    pad = (-len(stream)) % 8
    stream = np.concatenate([stream, np.ones(pad, np.uint8)])
    by = np.packbits(stream).tobytes().replace(b"\xff", b"\xff\x00")
    out = bytearray(b"\xff\xd8")
    # SOF3
    sof = struct.pack(">BHHB", P, Yn, Xn, C) + b"".join(struct.pack(">BBB", c + 1, 0x11, 0) for c in range(C))
    out += b"\xff\xc3" + struct.pack(">H", len(sof) + 2) + sof
    dht = bytes([0x00]) + bytes(bits) + bytes(vals)
    out += b"\xff\xc4" + struct.pack(">H", len(dht) + 2) + dht
    sos = bytes([C]) + b"".join(bytes([c + 1, 0x00]) for c in range(C)) + bytes([1, 0, 0])
    out += b"\xff\xda" + struct.pack(">H", len(sos) + 2) + sos
    out += by + b"\xff\xd9"
    return bytes(out)


# ---------------- メイン ----------------
KR = [1.0, -0.035, 0.004, 0.0]  # 樽型（補正で外側へ引き伸ばす: ratio < 1）


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--width", type=int, default=4032)
    ap.add_argument("--lj92", action="store_true")
    ap.add_argument("--comps", type=int, default=2)
    ap.add_argument("--orientation", type=int, default=1)
    ap.add_argument("--no-warp", action="store_true")
    ap.add_argument("--seed", type=int, default=1)
    ap.add_argument("--kr1", type=float, default=None)
    a = ap.parse_args()

    W = a.width
    H = W * 3 // 4
    W -= W % 2
    H -= H % 2
    rng = np.random.default_rng(a.seed)
    scene = build_scene(W, H)

    # カメラの分光感度（sRGB 原色からの混色）とチャンネル感度
    A = np.array([[0.80, 0.17, 0.03], [0.06, 0.86, 0.08], [0.02, 0.18, 0.80]])
    sens = np.array([0.52, 1.0, 0.68])
    expo = 0.25  # HDR+ 風に 2 段アンダー（シーン 4.0 で飽和）
    cam = (scene @ A.T) * sens * expo

    # 歪曲（WarpRectilinear: 補正後座標 -> RAW 座標 = c + d * (kr0 + kr1 r^2 + kr2 r^4)）
    kr = [float(v) for v in KR]
    if a.kr1 is not None:
        kr[1] = a.kr1
    cxr, cyr = 0.5, 0.5
    cx, cy = cxr * W, cyr * H
    m = np.hypot(max(cx, W - cx), max(cy, H - cy))
    if not a.no_warp:
        ys, xs = np.mgrid[0:H, 0:W].astype(np.float64)
        dx = (xs - cx) / m
        dy = (ys - cy) / m
        px, py = dx.copy(), dy.copy()  # 補正後座標（正規化）の推定
        for _ in range(8):
            rr = np.minimum(px * px + py * py, 1.0)
            ratio = kr[0] + rr * (kr[1] + rr * (kr[2] + rr * kr[3]))
            px = dx / ratio
            py = dy / ratio
        sx = cx + px * m
        sy = cy + py * m
        x0 = np.clip(np.floor(sx).astype(int), 0, W - 2)
        y0 = np.clip(np.floor(sy).astype(int), 0, H - 2)
        fx = np.clip(sx - x0, 0, 1)[..., None]
        fy = np.clip(sy - y0, 0, 1)[..., None]
        cam = (cam[y0, x0] * (1 - fx) * (1 - fy) + cam[y0, x0 + 1] * fx * (1 - fy) +
               cam[y0 + 1, x0] * (1 - fx) * fy + cam[y0 + 1, x0 + 1] * fx * fy)

    # 周辺減光（色ごとに少し違う）
    ys, xs = np.mgrid[0:H, 0:W].astype(np.float64)
    hd = np.hypot(W / 2, H / 2)
    r2 = ((xs + 0.5 - W / 2) ** 2 + (ys + 0.5 - H / 2) ** 2) / hd ** 2
    shade_k = np.array([0.55, 0.45, 0.62])
    cam = cam / (1 + shade_k * r2[..., None])

    # モザイク（RGGB）
    pattern = np.array([[0, 1], [1, 2]])
    cidx = pattern[np.arange(H)[:, None] % 2, np.arange(W)[None, :] % 2]
    raw = np.take_along_axis(cam, cidx[..., None], 2)[..., 0]
    # ノイズ
    raw = raw + rng.normal(0, 1, raw.shape) * np.sqrt(np.maximum(raw, 0) * 2e-5 + 0.0007 ** 2)
    BL, WL = 256, 4095
    rawi = np.clip(np.round(raw * (WL - BL) + BL), 0, WL).astype(np.uint16)

    # 参照プレビュー（カメラ処理済みを想定した sRGB 画像、HDR 風の柔らかいトーン）
    pv = scene * 1.0
    pv = pv / (pv + 0.55) * 1.55
    pim = Image.fromarray((lin_to_srgb(pv) * 255 + 0.5).astype(np.uint8)).resize((1814, 1360), Image.LANCZOS)
    pbuf = io.BytesIO()
    pim.save(pbuf, "JPEG", quality=90)
    preview = pbuf.getvalue()

    t = Tiff()
    prev_off = t.blob(preview)

    # RAW データ
    raw_entries = [
        (254, 4, [0]), (256, 4, [W]), (257, 4, [H]), (258, 3, [16]), (262, 3, [32803]), (277, 3, [1]),
        (284, 3, [1]), (33421, 3, [2, 2]), (33422, 1, [0, 1, 1, 2]), (50710, 1, [0, 1, 2]), (50711, 3, [1]),
        (50713, 3, [2, 2]), (50714, 4, [BL] * 4), (50717, 3, [WL]),
        (50719, 4, [8, 8]), (50720, 4, [W - 16, H - 16]),
    ]
    if a.lj92:
        T = 256
        offs, cnts = [], []
        for ty in range(0, H, T):
            for tx in range(0, W, T):
                tile = np.zeros((T, T), np.uint16)
                blk = rawi[ty:ty + T, tx:tx + T]
                tile[:blk.shape[0], :blk.shape[1]] = blk
                enc = lj92_encode(tile, a.comps)
                offs.append(t.blob(enc))
                cnts.append(len(enc))
        raw_entries += [(259, 3, [7]), (322, 3, [T]), (323, 3, [T]), (324, 4, offs), (325, 4, cnts)]
    else:
        data = rawi.astype("<u2").tobytes()
        off = t.blob(data)
        raw_entries += [(259, 3, [1]), (273, 4, [off]), (278, 4, [H]), (279, 4, [len(data)])]

    # OpcodeList2: GainMap x4
    def opcode(oid, params, flags=0):
        return struct.pack(">IIII", oid, 0x01030000, flags, len(params)) + params

    pv_, ph_ = 13, 17
    ops = []
    for (top, left, ch) in [(0, 0, 0), (0, 1, 1), (1, 0, 1), (1, 1, 2)]:
        g = []
        for i in range(pv_):
            for j in range(ph_):
                yy = (i / (pv_ - 1)) * H - 0.5
                xx = (j / (ph_ - 1)) * W - 0.5
                rr = ((xx + 0.5 - W / 2) ** 2 + (yy + 0.5 - H / 2) ** 2) / hd ** 2
                g.append(1 + shade_k[ch] * rr)
        params = struct.pack(">8I", top, left, H, W, 0, 1, 2, 2) + struct.pack(">II", pv_, ph_)
        params += struct.pack(">4d", 1.0 / (pv_ - 1), 1.0 / (ph_ - 1), 0.0, 0.0) + struct.pack(">I", 1)
        params += struct.pack(">%df" % len(g), *g)
        ops.append(opcode(9, params, flags=0))
    ol2 = struct.pack(">I", len(ops)) + b"".join(ops)
    raw_entries.append((51009, 7, ol2))
    if not a.no_warp:
        wp = struct.pack(">I", 1) + struct.pack(">6d", kr[0], kr[1], kr[2], kr[3], 0.0, 0.0) + struct.pack(">2d", cxr, cyr)
        ol3 = struct.pack(">I", 1) + opcode(1, wp, flags=0)
        raw_entries.append((51022, 7, ol3))
    raw_ifd = t.ifd(raw_entries)

    exif_ifd = t.ifd([
        (33434, 5, [1 / 120]), (33437, 5, [1.7]), (34855, 3, [100]), (36864, 7, b"0232"),
        (36867, 2, "2026:11:22 10:00:00"), (37386, 5, [6.9]), (42036, 2, "Synthetic lens"),
    ])

    # 色行列（D65 単一キャリブレーション）
    xyz_to_srgb = np.array([[3.2404542, -1.5371385, -0.4985314], [-0.9692660, 1.8760108, 0.0415560], [0.0556434, -0.2040259, 1.0572252]])
    srgb_to_xyz = np.linalg.inv(xyz_to_srgb)
    cm = np.diag(sens) @ A @ xyz_to_srgb  # XYZ(D65) -> カメラ
    cm = cm / (cm @ srgb_to_xyz @ np.ones(3)).max()
    brad = np.array([[0.8951, 0.2664, -0.1614], [-0.7502, 1.7135, 0.0367], [0.0389, -0.0685, 1.0296]])
    d65 = srgb_to_xyz @ np.ones(3)
    d50 = np.array([0.96422, 1.0, 0.82521])
    cat = np.linalg.inv(brad) @ np.diag((brad @ d50) / (brad @ d65)) @ brad
    fm = cat @ srgb_to_xyz @ np.linalg.inv(A)  # WB 済みカメラ -> XYZ(D50)
    fm = fm * (d50[1] / (fm @ np.ones(3))[1])

    ifd0 = t.ifd([
        (254, 4, [1]), (256, 4, [pim.width]), (257, 4, [pim.height]), (258, 3, [8, 8, 8]), (259, 3, [7]),
        (262, 3, [6]), (271, 2, "Google"), (272, 2, "Pixel Synthetic"), (273, 4, [prev_off]),
        (274, 3, [a.orientation]), (277, 3, [3]), (278, 4, [pim.height]), (279, 4, [len(preview)]),
        (305, 2, "make_test_dng"), (306, 2, "2026:11:22 10:00:00"), (330, 4, [raw_ifd]), (34665, 4, [exif_ifd]),
        (50706, 1, [1, 4, 0, 0]), (50707, 1, [1, 1, 0, 0]), (50708, 2, "Synthetic Pixel"),
        (50721, 10, list(cm.ravel())), (50778, 3, [21]), (50964, 10, list(fm.ravel())),
        (50728, 5, list(sens / sens.max())), (50730, 10, [0.0]),
    ])
    t.set_first(ifd0)
    with open(a.out, "wb") as f:
        f.write(t.buf)
    print("wrote", a.out, len(t.buf) / 1e6, "MB", W, "x", H, "lj92" if a.lj92 else "uncompressed")


if __name__ == "__main__":
    main()

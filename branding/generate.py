#!/usr/bin/env python3
"""Every Kadans logo asset, drawn from one geometry (branding/README.md).

    python3 branding/generate.py        # from the repository root; needs ImageMagick (`convert`) for the PNG/ICO files

The mark is a clock dial whose four hands form a K: two make the stem (12 and 6), two the arms (1 and 5 o'clock),
all from the centre. Coordinates are on a 100 x 100 square centred on (50, 50). The app draws the same mark in
Compose (clients/app/shared/.../ui/brand/KadansMark.kt): change both together.
"""
import math
import os
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PURPLE = "#6750A4"  # the app's purple (Material 3 baseline primary)
WHITE = "#FFFFFF"

# The dial: a ring and twelve hour marks, the four at 12, 3, 6 and 9 longer and bolder.
RING_R, RING_W = 44.0, 3.5
TICK_OUTER, TICK_INNER_MAJOR, TICK_INNER_MINOR = 41.5, 36.0, 38.0
TICK_W_MAJOR, TICK_W_MINOR = 3.0, 2.0
# The hands, as (angle clockwise from 12, length): stem up, upper arm, stem down, lower arm.
HANDS = [(0, 29.0), (30, 25.0), (180, 29.0), (150, 25.0)]
HAND_W = 6.5
# The splash's "Wind": every hand starts at 12 and turns this many times before settling; one second.
WIND_TURNS = [1, 2, 1, 1]
WIND_MS = 1000
WIND_EASING = (0.2, 0.78, 0.25, 1.0)  # cubic-bezier

# The small mark (24 dp and under: the notification icon, the tray): no hour marks, bolder strokes.
SMALL_RING_R, SMALL_RING_W = 42.0, 7.0
SMALL_HANDS = [(0, 27.0), (30, 23.0), (180, 27.0), (150, 23.0)]
SMALL_HAND_W = 9.5


def point(angle, r, cx=50.0, cy=50.0):
    a = math.radians(angle)
    return cx + r * math.sin(a), cy - r * math.cos(a)


def fmt(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def ring_path(r):
    return f"M50,{fmt(50 - r)} A{fmt(r)},{fmt(r)} 0 1,1 50,{fmt(50 + r)} A{fmt(r)},{fmt(r)} 0 1,1 50,{fmt(50 - r)}"


def line_path(angle, r1, r2):
    x1, y1 = point(angle, r1)
    x2, y2 = point(angle, r2)
    return f"M{fmt(x1)},{fmt(y1)} L{fmt(x2)},{fmt(y2)}"


def ticks():
    for i in range(12):
        major = i % 3 == 0
        yield line_path(i * 30, TICK_INNER_MAJOR if major else TICK_INNER_MINOR, TICK_OUTER), TICK_W_MAJOR if major else TICK_W_MINOR


# ---------------------------------------------------------------- SVG

def svg_mark(color, small=False):
    """The mark's elements on the 100 x 100 square."""
    if small:
        parts = [f'<path d="{ring_path(SMALL_RING_R)}" fill="none" stroke="{color}" stroke-width="{SMALL_RING_W}"/>']
        parts += [f'<path d="{line_path(a, 0, l)}" stroke="{color}" stroke-width="{SMALL_HAND_W}" stroke-linecap="round"/>' for a, l in SMALL_HANDS]
        return "".join(parts)
    parts = [f'<path d="{ring_path(RING_R)}" fill="none" stroke="{color}" stroke-width="{RING_W}"/>']
    parts += [f'<path d="{d}" stroke="{color}" stroke-width="{w}" stroke-linecap="round"/>' for d, w in ticks()]
    parts += [f'<path d="{line_path(a, 0, l)}" stroke="{color}" stroke-width="{HAND_W}" stroke-linecap="round"/>' for a, l in HANDS]
    return "".join(parts)


def svg_doc(body, size=100, view=100):
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="0 0 {view} {view}">{body}</svg>\n'


def scaled(body, scale, view=100):
    """The 100-unit mark, scaled about the centre of a `view`-unit square."""
    offset = (view - 100 * scale) / 2
    return f'<g transform="translate({fmt(offset)} {fmt(offset)}) scale({fmt(scale)})">{body}</g>'


def icon_round(small=False):
    """The app icon: a purple disc, the dial in white."""
    return svg_doc(f'<circle cx="50" cy="50" r="50" fill="{PURPLE}"/>' + scaled(svg_mark(WHITE, small), 0.78))


def icon_square():
    """Play Store: a full square (Google applies its own mask), the dial at the adaptive icon's proportion."""
    return svg_doc(f'<rect width="100" height="100" fill="{PURPLE}"/>' + scaled(svg_mark(WHITE), 0.56))


# ---------------------------------------------------------------- Android vector drawables

ANDROID_NS = 'xmlns:android="http://schemas.android.com/apk/res/android"'


def vector_paths(color, small=False, indent="    "):
    out = []
    if small:
        out.append(f'{indent}<path android:pathData="{ring_path(SMALL_RING_R)}" android:strokeColor="{color}" android:strokeWidth="{SMALL_RING_W}"/>')
        for a, l in SMALL_HANDS:
            out.append(f'{indent}<path android:pathData="{line_path(a, 0, l)}" android:strokeColor="{color}" android:strokeWidth="{SMALL_HAND_W}" android:strokeLineCap="round"/>')
        return out
    out.append(f'{indent}<path android:pathData="{ring_path(RING_R)}" android:strokeColor="{color}" android:strokeWidth="{RING_W}"/>')
    for d, w in ticks():
        out.append(f'{indent}<path android:pathData="{d}" android:strokeColor="{color}" android:strokeWidth="{w}" android:strokeLineCap="round"/>')
    for a, l in HANDS:
        out.append(f'{indent}<path android:pathData="{line_path(a, 0, l)}" android:strokeColor="{color}" android:strokeWidth="{HAND_W}" android:strokeLineCap="round"/>')
    return out


def vector(size_dp, view, body_lines, note):
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        f"<!-- {note} Generated by branding/generate.py: do not edit. -->",
        f'<vector {ANDROID_NS}',
        f'    android:width="{size_dp}dp" android:height="{size_dp}dp"',
        f'    android:viewportWidth="{view}" android:viewportHeight="{view}">',
        *body_lines,
        "</vector>",
        "",
    ])


def group(lines, scale, view, indent="    "):
    offset = (view - 100 * scale) / 2
    return ([f'{indent}<group android:translateX="{fmt(offset)}" android:translateY="{fmt(offset)}" android:scaleX="{fmt(scale)}" android:scaleY="{fmt(scale)}">']
            + ["    " + l for l in lines] + [f"{indent}</group>"])


def avd_splash():
    """Android 12+ splash: 288 dp, the mark in the central 192 dp circle; the hands wind into the K."""
    view, scale = 150, 1.0  # the 100-unit mark centred in 150: 2/3 of the icon, as the platform asks
    offset = (view - 100) / 2
    lines = [f'            <group android:translateX="{fmt(offset)}" android:translateY="{fmt(offset)}">']
    lines.append(f'                <path android:pathData="{ring_path(RING_R)}" android:strokeColor="{WHITE}" android:strokeWidth="{RING_W}"/>')
    for d, w in ticks():
        lines.append(f'                <path android:pathData="{d}" android:strokeColor="{WHITE}" android:strokeWidth="{w}" android:strokeLineCap="round"/>')
    for i, (a, l) in enumerate(HANDS):
        lines.append(f'                <group android:name="hand{i}" android:pivotX="50" android:pivotY="50" android:rotation="{a}">')
        lines.append(f'                    <path android:pathData="{line_path(0, 0, l)}" android:strokeColor="{WHITE}" android:strokeWidth="{HAND_W}" android:strokeLineCap="round"/>')
        lines.append("                </group>")
    lines.append("            </group>")
    x1, y1, x2, y2 = WIND_EASING
    targets = []
    for i, (a, _) in enumerate(HANDS):
        targets += [
            f'    <target android:name="hand{i}">',
            '        <aapt:attr name="android:animation">',
            f'            <objectAnimator android:propertyName="rotation" android:valueFrom="0" android:valueTo="{a + 360 * WIND_TURNS[i]}"',
            f'                android:valueType="floatType" android:duration="{WIND_MS}">',
            '                <aapt:attr name="android:interpolator">',
            f'                    <pathInterpolator android:pathData="M0,0 C{x1},{y1} {x2},{y2} 1,1"/>',
            "                </aapt:attr>",
            "            </objectAnimator>",
            "        </aapt:attr>",
            "    </target>",
        ]
    return "\n".join([
        '<?xml version="1.0" encoding="utf-8"?>',
        "<!-- The splash on Android 12 and later: the hands wind into the K (\"Wind\", one second). Generated by",
        "     branding/generate.py: do not edit. -->",
        f'<animated-vector {ANDROID_NS}',
        '    xmlns:aapt="http://schemas.android.com/aapt">',
        '    <aapt:attr name="android:drawable">',
        '        <vector android:width="288dp" android:height="288dp"',
        f'            android:viewportWidth="{view}" android:viewportHeight="{view}">',
        *lines,
        "        </vector>",
        "    </aapt:attr>",
        *targets,
        "</animated-vector>",
        "",
    ])


ADAPTIVE = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generated by branding/generate.py: do not edit. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/kadans_purple"/>
    <foreground android:drawable="@drawable/ic_launcher_foreground"/>
    <monochrome android:drawable="@drawable/ic_launcher_monochrome"/>
</adaptive-icon>
"""

# ---------------------------------------------------------------- files

def write(rel, text):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote", rel)


def png(svg_text, rel, px):
    path = os.path.join(ROOT, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg_text)
        src = f.name
    try:
        # Rendered large, then scaled down: smooth edges at every size.
        subprocess.run(["convert", "-background", "none", "-density", str(max(96, px * 4)), f"MSVG:{src}",
                        "-resize", f"{px}x{px}", f"PNG32:{path}"], check=True)
    finally:
        os.unlink(src)
    print("wrote", rel, f"({px} px)")


def main():
    # Master files
    write("branding/kadans-mark.svg", svg_doc(svg_mark(PURPLE)))
    write("branding/kadans-mark-white.svg", svg_doc(svg_mark(WHITE)))
    write("branding/kadans-mark-small.svg", svg_doc(svg_mark(PURPLE, small=True)))
    write("branding/kadans-icon.svg", icon_round())
    png(icon_square(), "branding/play-store-icon.png", 512)

    # Android launcher: adaptive (8.0+, themed on 13+), and PNGs for 7.x
    app = "clients/app/androidApp/src/main/res"
    adaptive_scale = 0.56  # the dial well inside the 66 dp safe zone of the 108 dp canvas, with the margin system icons keep
    write(f"{app}/drawable/ic_launcher_foreground.xml",
          vector(108, 108, group(vector_paths(WHITE), adaptive_scale, 108), "The launcher icon's dial, on @color/kadans_purple."))
    write(f"{app}/drawable/ic_launcher_monochrome.xml",
          vector(108, 108, group(vector_paths(WHITE), adaptive_scale, 108), "Android 13's themed icon: the system colours it."))
    write(f"{app}/mipmap-anydpi-v26/ic_launcher.xml", ADAPTIVE)
    write(f"{app}/mipmap-anydpi-v26/ic_launcher_round.xml", ADAPTIVE)
    for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        png(icon_round(), f"{app}/mipmap-{density}/ic_launcher.png", px)
        png(icon_round(), f"{app}/mipmap-{density}/ic_launcher_round.png", px)

    # Android splash: animated on 12+, the finished mark on a purple window before that
    write(f"{app}/drawable/avd_splash.xml", avd_splash())
    write(f"{app}/drawable/ic_splash_mark.xml",
          vector(192, 100, vector_paths(WHITE), "The splash before Android 12: the finished mark on purple."))

    # Notification icon (one colour; the system tints it), in the shared module where the notifications are built
    write("clients/app/shared/src/androidMain/res/drawable/ic_stat_kadans.xml",
          vector(24, 100, vector_paths(WHITE, small=True), "Notification icon: the small mark, white; Android tints it."))

    # Desktop: the packaged app's icons, and the picture its Linux notifications carry
    png(icon_round(), "clients/app/desktopApp/icons/kadans.png", 512)
    png(icon_round(), "clients/app/shared/src/jvmMain/resources/kadans-icon.png", 128)
    ico_src = os.path.join(ROOT, "clients/app/desktopApp/icons/kadans.png")
    subprocess.run(["convert", ico_src, "-define", "icon:auto-resize=256,128,64,48,32,16",
                    os.path.join(ROOT, "clients/app/desktopApp/icons/kadans.ico")], check=True)
    print("wrote clients/app/desktopApp/icons/kadans.ico")


if __name__ == "__main__":
    main()

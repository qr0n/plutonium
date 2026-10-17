from PIL import Image, ImageDraw
import math

W, H = 960, 540
cx, cy = W // 2, H // 2

def draw_gradient(img):
    glow_img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow_img)
    for y in range(H):
        t = y / H
        a = int(0x20 * (1.0 - t))
        glow_draw.line([(0, y), (W, y)], fill=(93, 80, 240, a))
    return Image.alpha_composite(img, glow_img)

def draw_atom(img, cx, cy):
    orbits = [(90.0, 38.0, 0.0), (78.0, 55.0, 1.05), (95.0, 32.0, 2.1)]
    scale_factor = 2.5
    segments = 200
    for rx, ry, tilt in orbits:
        rx *= scale_factor
        ry *= scale_factor
        cos_t = math.cos(tilt)
        sin_t = math.sin(tilt)
        points = []
        alphas = []
        for s in range(segments):
            angle = s * 2 * math.pi / segments
            ox = rx * math.cos(angle)
            oy = ry * math.sin(angle)
            fx = ox * cos_t - oy * sin_t
            fy = ox * sin_t + oy * cos_t
            t = s / segments
            a = 0.35 + 0.5 * math.sin(t * math.pi)
            points.append((cx + fx, cy + fy))
            alphas.append(a)
        orbit_img = Image.new("RGBA", img.size, (0, 0, 0, 0))
        orbit_draw = ImageDraw.Draw(orbit_img)
        for s in range(segments):
            s2 = (s + 1) % segments
            a = (alphas[s] + alphas[s2]) / 2
            color = (93, 80, 240, int(a * 255))
            orbit_draw.line([points[s], points[s2]], fill=color, width=2)
        img = Image.alpha_composite(img, orbit_img)

    glow_radii = [55.0, 35.0, 15.0]
    glow_colors = [
        (93, 80, 240, 0.12),
        (126, 107, 255, 0.35),
        (238, 236, 255, 0.9),
    ]
    for r, (cr, cg, cb, ca) in zip(glow_radii, glow_colors):
        r *= scale_factor
        glow_layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        glow_d = ImageDraw.Draw(glow_layer)
        ri = int(r)
        for dist in range(ri, 0, -1):
            t = dist / ri
            alpha = int(ca * 255 * (1.0 - t))
            glow_d.ellipse(
                [cx - dist, cy - dist, cx + dist, cy + dist],
                fill=(cr, cg, cb, alpha),
            )
        img = Image.alpha_composite(img, glow_layer)
    return img

def draw_text(img, cx, text_y):
    draw = ImageDraw.Draw(img)
    pixel = 6
    P = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,0),(2,0),(2,1),(1,1)]
    L = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,4),(2,4)]
    U = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,4),(2,4),(2,0),(2,1),(2,2),(2,3)]
    T = [(0,0),(1,0),(2,0),(1,1),(1,2),(1,3),(1,4)]
    O = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,0),(2,0),(1,4),(2,4),(2,1),(2,2),(2,3)]
    N = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,1),(1,2),(1,3),(2,0),(2,1),(2,2),(2,3),(2,4)]
    I_L = [(0,0),(1,0),(2,0),(1,1),(1,2),(1,3),(1,4),(0,4),(2,4)]
    M = [(0,0),(0,1),(0,2),(0,3),(0,4),(1,1),(2,2),(3,1),(4,0),(4,1),(4,2),(4,3),(4,4)]
    letters = [P, L, U, T, O, N, I_L, U, M]
    letter_widths = [3, 3, 3, 3, 3, 3, 3, 3, 5]
    gap = 2
    total_w = sum((w + gap) * pixel for w in letter_widths) - gap * pixel
    start_x = cx - total_w // 2
    px = start_x
    text_color = (238, 236, 255, 255)
    for li, letter in enumerate(letters):
        for dot in letter:
            dx = px + dot[0] * pixel
            dy = text_y + dot[1] * pixel
            draw.rectangle([dx, dy, dx + pixel - 1, dy + pixel - 1], fill=text_color)
        px += (letter_widths[li] + gap) * pixel
    return img

# --- Image 1: Atom on gradient backdrop ---
atom_img = Image.new("RGBA", (W, H), (9, 11, 22, 255))
atom_img = draw_gradient(atom_img)
atom_img = draw_atom(atom_img, cx, cy)
atom_img.save("splash_atom.png", "PNG")
print("Saved splash_atom.png")

# --- Image 2: Atom only, transparent background ---
atom_clear = Image.new("RGBA", (W, H), (0, 0, 0, 0))
atom_clear = draw_atom(atom_clear, cx, cy)
atom_clear.save("splash_atom_transparent.png", "PNG")
print("Saved splash_atom_transparent.png")

# --- Image 2: Text only, transparent background ---
text_h = 5 * 6  # 5 rows * 6px pixel size
padding = 10
text_w_calc = sum((w + 2) * 6 for w in [3,3,3,3,3,3,3,3,5]) - 2 * 6
tw = text_w_calc + padding * 2
th = text_h + padding * 2
text_img = Image.new("RGBA", (tw, th), (0, 0, 0, 0))
text_img = draw_text(text_img, tw // 2, padding)
text_img.save("splash_text.png", "PNG")
print("Saved splash_text.png")

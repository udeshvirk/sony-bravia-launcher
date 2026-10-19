import sys, os
from PIL import Image, ImageDraw
d = sys.argv[1]; out = sys.argv[2]; ids = sys.argv[3].split(',')
cols = 8; W, H = 240, 135
rows = (len(ids) + cols - 1) // cols
sheet = Image.new('RGB', (cols * W, rows * (H + 18)), 'black')
dr = ImageDraw.Draw(sheet)
for i, n in enumerate(ids):
    try: im = Image.open(os.path.join(d, n + '.jpg')).convert('RGB').resize((W, H))
    except Exception: continue
    x, y = (i % cols) * W, (i // cols) * (H + 18)
    sheet.paste(im, (x, y)); dr.text((x + 4, y + H + 2), n, fill='white')
sheet.save(out)

package whatsappengine

import (
	"bytes"
	"image"
	"image/jpeg"
	_ "image/png"
)

// sentThumbnailEdge is the long edge of the preview a sent photo carries, about what the
// WhatsApp apps attach themselves.
const sentThumbnailEdge = 96

// makeThumbnail reduces a JPEG or PNG to the small inline preview a photo message carries,
// so the recipient sees it before the photo downloads. It returns nil when the image cannot
// be decoded; the photo is then sent without one.
func makeThumbnail(data []byte) []byte {
	src, _, err := image.Decode(bytes.NewReader(data))
	if err != nil {
		return nil
	}
	bounds := src.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if w <= 0 || h <= 0 {
		return nil
	}
	tw, th := w, h
	if w >= h && w > sentThumbnailEdge {
		tw, th = sentThumbnailEdge, max(1, h*sentThumbnailEdge/w)
	} else if h > w && h > sentThumbnailEdge {
		tw, th = max(1, w*sentThumbnailEdge/h), sentThumbnailEdge
	}
	dst := image.NewRGBA(image.Rect(0, 0, tw, th))
	// A box average over each target pixel's source area: no aliasing, no extra dependency.
	for y := 0; y < th; y++ {
		y0, y1 := bounds.Min.Y+y*h/th, bounds.Min.Y+max((y+1)*h/th, y*h/th+1)
		for x := 0; x < tw; x++ {
			x0, x1 := bounds.Min.X+x*w/tw, bounds.Min.X+max((x+1)*w/tw, x*w/tw+1)
			var r, g, b, n uint64
			for sy := y0; sy < y1; sy++ {
				for sx := x0; sx < x1; sx++ {
					cr, cg, cb, _ := src.At(sx, sy).RGBA()
					r, g, b, n = r+uint64(cr), g+uint64(cg), b+uint64(cb), n+1
				}
			}
			i := dst.PixOffset(x, y)
			dst.Pix[i], dst.Pix[i+1], dst.Pix[i+2], dst.Pix[i+3] = uint8(r/n>>8), uint8(g/n>>8), uint8(b/n>>8), 0xff
		}
	}
	var out bytes.Buffer
	if jpeg.Encode(&out, dst, &jpeg.Options{Quality: 60}) != nil || out.Len() > maxThumbnailBytes {
		return nil
	}
	return out.Bytes()
}

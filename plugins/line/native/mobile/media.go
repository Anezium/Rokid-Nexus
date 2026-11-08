package mobile

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"image"
	"image/jpeg"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"

	lineapi "github.com/beeper/line/pkg/line"
	"golang.org/x/crypto/hkdf"
)

const maxMediaBytes = 4 * 1024 * 1024

// Download locators and sealing keys stay native, outside snapshots and page ids.
type mediaSource struct {
	sticker, oid, publicPath, messageID string
	previewOID                          string
	options                             lineapi.OBSDownloadOptions
	keys                                []string
	encrypted                           bool
}

func decodeMedia(row *lineapi.Message, plain string) (string, string, *mediaSource) {
	switch row.ContentType {
	case 1:
		source := &mediaSource{oid: row.ContentMetadata["OID"], messageID: row.ID,
			publicPath: row.ContentMetadata["DOWNLOAD_URL"],
			options:    lineapi.OBSDownloadOptions{OBSPop: row.ContentMetadata["OBS_POP"]}}
		var body map[string]json.RawMessage
		if plain != "" && json.Unmarshal([]byte(plain), &body) != nil {
			return "[Unable to decrypt photo]", "", nil
		}
		if value, declared := body["keyMaterial"]; declared {
			source.encrypted = true
			var key string
			if json.Unmarshal(value, &key) != nil || key == "" {
				return "[Unable to decrypt photo]", "", nil
			}
			source.keys = append(source.keys, key)
		}
		if key, declared := row.ContentMetadata["ENC_KM"]; declared {
			source.encrypted = true
			if key != "" {
				source.keys = append(source.keys, key)
			}
		}
		// An encrypted message or encrypted OBS object must never fall back to clear bytes.
		source.encrypted = source.encrypted || len(row.Chunks) > 0 || source.oid != ""
		if source.encrypted && len(source.keys) == 0 {
			return "[Unable to decrypt photo]", "", nil
		}
		return "[Photo]", "photo", source
	case 7:
		id := row.ContentMetadata["STKID"]
		if id == "" || len(id) > 24 || strings.IndexFunc(id, func(r rune) bool { return r < '0' || r > '9' }) >= 0 {
			return "[Sticker unavailable]", "", nil
		}
		return "[Sticker]", "sticker", &mediaSource{sticker: id}
	case 2:
		return "[Video · open LINE on the phone]", "", nil
	case 3:
		return "[Voice message · open LINE on the phone]", "", nil
	default:
		return "[Media message · open LINE on the phone]", "", nil
	}
}

// Media reads only a message already present in this owner's loaded history.
// Kotlin requests the newest visible candidates off the page callback thread.
func (e *Engine) Media(chatID, messageID string) ([]byte, error) {
	e.op.Lock()
	defer e.op.Unlock()
	if e.client == nil || e.ctx.Err() != nil {
		return nil, errors.New("media unavailable")
	}
	var source *mediaSource
	e.mu.Lock()
	for _, chat := range e.chats {
		if chat.ID != chatID {
			continue
		}
		for _, message := range chat.History {
			if message.ID == messageID {
				source = message.source
				break
			}
		}
	}
	e.mu.Unlock()
	if source == nil {
		return nil, errors.New("media unavailable")
	}
	ctx, cancel := context.WithTimeout(e.ctx, 12*time.Second)
	defer cancel()
	data, err := e.downloadMedia(ctx, source)
	if err != nil {
		return nil, errors.New("media unavailable")
	}
	if !source.encrypted {
		return data, nil
	}
	for _, key := range source.keys {
		if plain, err := decryptPhoto(data, key); err == nil {
			return plain, nil
		}
	}
	return nil, errors.New("photo authentication failed")
}

func (e *Engine) downloadMedia(ctx context.Context, source *mediaSource) ([]byte, error) {
	if source.sticker != "" {
		client := mediaHTTPClient(e.ctx)
		for _, name := range []string{"sticker.png", "sticker_animation.png"} {
			request, err := http.NewRequestWithContext(ctx, http.MethodGet,
				"https://stickershop.line-scdn.net/stickershop/v1/sticker/"+source.sticker+"/android/"+name, nil)
			if err != nil {
				return nil, err
			}
			response, err := client.Do(request)
			if err != nil {
				return nil, err
			}
			data, readErr := io.ReadAll(response.Body)
			response.Body.Close()
			if readErr != nil {
				return nil, readErr
			}
			if response.StatusCode == http.StatusOK {
				return data, nil
			}
		}
		return nil, errors.New("sticker unavailable")
	}
	if source.publicPath != "" {
		if !safePublicMedia(source.publicPath) {
			return nil, errors.New("unsupported media host")
		}
		// Keep absolute OBS URLs on our bounded, owner-cancelled OBS transport.
		// Upstream otherwise creates a separate public HTTP client for absolute URLs.
		resource, _ := url.Parse(source.publicPath)
		return e.client.DownloadOBSPublicResource(ctx, resource.RequestURI())
	}
	if source.oid != "" {
		if source.previewOID != "" {
			if preview, err := e.client.DownloadOBSWithOptions(ctx, source.previewOID, source.messageID, source.options); err == nil {
				return preview, nil
			}
		}
		return e.client.DownloadOBSWithOptions(ctx, source.oid, source.messageID, source.options)
	}
	// Unsealed photos use the plain message endpoint and no talk-meta message id.
	return e.client.DownloadOBSWithSIDOptions(ctx, source.messageID, "", "m", source.options)
}

func safePublicMedia(raw string) bool {
	u, err := url.Parse(raw)
	return err == nil && u.User == nil && u.Fragment == "" &&
		((u.Scheme == "" && u.Host == "" && strings.HasPrefix(u.Path, "/")) ||
			(u.Scheme == "https" && u.Host == "obs.line-apps.com"))
}

type mediaTransport struct{ ctx context.Context }

func (t mediaTransport) RoundTrip(request *http.Request) (*http.Response, error) {
	response, err := (requestTransport{t.ctx}).RoundTrip(request)
	if err != nil {
		return nil, err
	}
	if response.ContentLength > maxMediaBytes {
		response.Body.Close()
		return nil, errors.New("media exceeds size limit")
	}
	response.Body = http.MaxBytesReader(nil, response.Body, maxMediaBytes)
	return response, nil
}

func mediaHTTPClient(ctx context.Context) *http.Client {
	return &http.Client{Timeout: 6 * time.Second, Transport: mediaTransport{ctx},
		CheckRedirect: func(request *http.Request, via []*http.Request) error {
			if len(via) >= 3 || request.URL.Scheme != "https" || request.URL.Host != via[0].URL.Host {
				return errors.New("media redirect rejected")
			}
			return nil
		}}
}

// LINE's FileEncryption format is ciphertext followed by its HMAC-SHA256.
// Unlike the upstream bridge's decoder, authenticate before exposing any pixels.
func decryptPhoto(data []byte, key string) ([]byte, error) {
	material, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(material) != 32 || len(data) <= sha256.Size {
		return nil, errors.New("invalid photo key or ciphertext")
	}
	derived := make([]byte, 76)
	if _, err := io.ReadFull(hkdf.New(sha256.New, material, nil, []byte("FileEncryption")), derived); err != nil {
		return nil, err
	}
	ciphertext, tag := data[:len(data)-sha256.Size], data[len(data)-sha256.Size:]
	mac := hmac.New(sha256.New, derived[32:64])
	mac.Write(ciphertext)
	if !hmac.Equal(mac.Sum(nil), tag) {
		return nil, errors.New("photo authentication failed")
	}
	block, err := aes.NewCipher(derived[:32])
	if err != nil {
		return nil, err
	}
	counter := make([]byte, aes.BlockSize)
	copy(counter, derived[64:])
	plain := make([]byte, len(ciphertext))
	cipher.NewCTR(block, counter).XORKeyStream(plain, ciphertext)
	return plain, nil
}

func encryptFileData(plain []byte) ([]byte, string, error) {
	material := make([]byte, 32)
	if _, err := io.ReadFull(rand.Reader, material); err != nil {
		return nil, "", err
	}
	derived := make([]byte, 76)
	if _, err := io.ReadFull(hkdf.New(sha256.New, material, nil, []byte("FileEncryption")), derived); err != nil {
		return nil, "", err
	}
	block, err := aes.NewCipher(derived[:32])
	if err != nil {
		return nil, "", err
	}
	counter := make([]byte, aes.BlockSize)
	copy(counter, derived[64:])
	ciphertext := make([]byte, len(plain))
	cipher.NewCTR(block, counter).XORKeyStream(ciphertext, plain)
	mac := hmac.New(sha256.New, derived[32:64])
	mac.Write(ciphertext)
	return append(ciphertext, mac.Sum(nil)...), base64.StdEncoding.EncodeToString(material), nil
}

func validImageJPEG(data []byte) bool {
	config, err := jpeg.DecodeConfig(bytes.NewReader(data))
	return err == nil && config.Width > 0 && config.Height > 0 &&
		config.Width <= 16_384 && config.Height <= 16_384 && int64(config.Width)*int64(config.Height) <= 32_000_000
}

// Diagnostic B matches 5241f577: the parent's FileEncryption key and counter
// encrypt the preview too. This is CTR keystream reuse, not an independent nonce.
func encryptThumbnail(plain []byte, key string) ([]byte, error) {
	material, err := base64.StdEncoding.DecodeString(key)
	if err != nil || len(material) != 32 {
		return nil, errors.New("invalid photo key")
	}
	derived := make([]byte, 76)
	if _, err := io.ReadFull(hkdf.New(sha256.New, material, nil, []byte("FileEncryption")), derived); err != nil {
		return nil, err
	}
	block, err := aes.NewCipher(derived[:32])
	if err != nil {
		return nil, err
	}
	counter := make([]byte, aes.BlockSize)
	copy(counter, derived[64:])
	ciphertext := make([]byte, len(plain))
	cipher.NewCTR(block, counter).XORKeyStream(ciphertext, plain)
	mac := hmac.New(sha256.New, derived[32:64])
	mac.Write(ciphertext)
	return append(ciphertext, mac.Sum(nil)...), nil
}

// The standard-library scaler and JPEG quality/size match the engine at 5241f577.
// Only the preview is re-encoded; the original JPEG remains unchanged.
func generateThumbnail(data []byte) ([]byte, int, int, error) {
	if !validImageJPEG(data) {
		return nil, 0, 0, errors.New("invalid JPEG")
	}
	source, err := jpeg.Decode(bytes.NewReader(data))
	if err != nil {
		return nil, 0, 0, err
	}
	bounds := source.Bounds()
	width, height := bounds.Dx(), bounds.Dy()
	longEdge := max(width, height)
	var thumbnail image.Image = source
	if longEdge > 1280 {
		width, height = max(1, width*1280/longEdge), max(1, height*1280/longEdge)
		scaled := image.NewRGBA(image.Rect(0, 0, width, height))
		for y := 0; y < height; y++ {
			for x := 0; x < width; x++ {
				scaled.Set(x, y, source.At(bounds.Min.X+x*bounds.Dx()/width, bounds.Min.Y+y*bounds.Dy()/height))
			}
		}
		thumbnail = scaled
	}
	var encoded bytes.Buffer
	if err := jpeg.Encode(&encoded, thumbnail, &jpeg.Options{Quality: 60}); err != nil {
		return nil, 0, 0, err
	}
	return encoded.Bytes(), width, height, nil
}

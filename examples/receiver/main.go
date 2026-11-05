// Command receiver prints verified Keycloak Snitch v1 webhooks to standard output.
package main

import (
	"bytes"
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"mime"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/containeroo/httpkit/grace"
)

const (
	defaultListenAddr = "127.0.0.1:8081"
	replayWindow      = 5 * time.Minute
	maxBodyBytes      = 64 << 10
	readTimeout       = 5 * time.Second
	writeTimeout      = 5 * time.Second
	idleTimeout       = 30 * time.Second
	unixSecondsBase   = 10
	timestampBits     = 64
)

// freshTimestamp accepts canonical Unix seconds within the five-minute replay window.
func freshTimestamp(timestamp string, now time.Time) bool {
	seconds, err := strconv.ParseInt(timestamp, unixSecondsBase, timestampBits)
	if err != nil || timestamp != strconv.FormatInt(seconds, unixSecondsBase) {
		return false
	}
	windowSeconds := int64(replayWindow / time.Second)
	return seconds >= now.Unix()-windowSeconds && seconds <= now.Unix()+windowSeconds
}

// verified authenticates the raw request body and checks the delivery timestamp.
func verified(secret []byte, timestamp, signature string, body []byte, now time.Time) bool {
	if !freshTimestamp(timestamp, now) {
		return false
	}
	raw, ok := strings.CutPrefix(signature, "v1=")
	digest, err := hex.DecodeString(raw)
	if !ok || err != nil {
		return false
	}
	mac := hmac.New(sha256.New, secret)
	mac.Write([]byte(timestamp + "."))
	mac.Write(body)
	return hmac.Equal(digest, mac.Sum(nil))
}

// handler serves POST /events and writes each verified JSON object on its own line.
func handler(secret []byte, out io.Writer) http.Handler {
	mux := http.NewServeMux()

	mux.HandleFunc("POST /events", func(w http.ResponseWriter, r *http.Request) {
		media, _, err := mime.ParseMediaType(r.Header.Get("Content-Type"))
		if err != nil || media != "application/json" {
			http.Error(w, "expected application/json", http.StatusUnsupportedMediaType)
			return
		}

		body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
		if err != nil {
			var oversized *http.MaxBytesError
			if errors.As(err, &oversized) {
				http.Error(w, "too large", http.StatusRequestEntityTooLarge)
			} else {
				http.Error(w, "invalid body", http.StatusBadRequest)
			}
			return
		}

		if !verified(secret, r.Header.Get("X-Snitch-Timestamp"), r.Header.Get("X-Snitch-Signature"), body, time.Now()) {
			http.Error(w, "signature or timestamp rejected", http.StatusUnauthorized)
			return
		}

		var line bytes.Buffer
		if json.Compact(&line, body) != nil || line.Bytes()[0] != '{' {
			http.Error(w, "expected JSON object", http.StatusBadRequest)
			return
		}

		line.WriteByte('\n')
		if _, err := out.Write(line.Bytes()); err != nil {
			log.Print("stdout write failed")
			http.Error(w, "output unavailable", http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusNoContent)
	})

	return mux
}

// run serves webhooks until shutdown is requested or the listener fails.
func run() error {
	secret := os.Getenv("WEBHOOK_SECRET")
	if strings.TrimSpace(secret) == "" {
		return fmt.Errorf("WEBHOOK_SECRET is required")
	}
	addr := os.Getenv("LISTEN_ADDR")
	if addr == "" {
		addr = defaultListenAddr
	}
	srv := &http.Server{
		Addr:         addr,
		Handler:      handler([]byte(secret), os.Stdout),
		ReadTimeout:  readTimeout,
		WriteTimeout: writeTimeout,
		IdleTimeout:  idleTimeout,
	}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	return grace.RunServer(ctx, srv, nil)
}

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

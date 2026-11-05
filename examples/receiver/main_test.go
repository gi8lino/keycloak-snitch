package main

import (
	"bytes"
	"crypto/hmac"
	"crypto/sha256"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

const (
	testVectorTimestamp = "1700000000"
	testVectorUnixTime  = int64(1700000000)
)

func TestVector(t *testing.T) {
	t.Parallel()
	const sig = "v1=9d713ed406bb7076d4123f0dc2c39d2df5c654ed4b0cd56b52c8b4c940bd63ae"
	signedAt := time.Unix(testVectorUnixTime, 0)
	verify := func(body string, now time.Time) bool {
		return verified([]byte("key"), testVectorTimestamp, sig, []byte(body), now)
	}

	t.Run("accepts signature at replay window boundary", func(t *testing.T) {
		t.Parallel()
		assert.True(t, verify("{}", signedAt.Add(replayWindow)))
	})

	t.Run("rejects expired timestamp", func(t *testing.T) {
		t.Parallel()
		assert.False(t, verify("{}", signedAt.Add(replayWindow+time.Second)))
	})

	t.Run("rejects timestamp too far in the future", func(t *testing.T) {
		t.Parallel()
		assert.False(t, verify("{}", signedAt.Add(-replayWindow-time.Second)))
	})

	t.Run("rejects modified body", func(t *testing.T) {
		t.Parallel()
		assert.False(t, verify("{ }", signedAt))
	})
}

func TestEndpoint(t *testing.T) {
	t.Parallel()

	t.Run("prints verified event as one compact line", func(t *testing.T) {
		t.Parallel()
		status, output := postEvent(`{ "id":"example" }`, "key")
		require.Equal(t, http.StatusNoContent, status)
		assert.Equal(t, "{\"id\":\"example\"}\n", output)
	})

	t.Run("rejects signature from a different key", func(t *testing.T) {
		t.Parallel()
		status, output := postEvent("{}", "wrong")
		require.Equal(t, http.StatusUnauthorized, status)
		assert.Empty(t, output)
	})

	t.Run("rejects signed invalid JSON", func(t *testing.T) {
		t.Parallel()
		status, output := postEvent("bad", "key")
		require.Equal(t, http.StatusBadRequest, status)
		assert.Empty(t, output)
	})

	t.Run("rejects body over 64 KiB", func(t *testing.T) {
		t.Parallel()
		status, output := postEvent(strings.Repeat("x", maxBodyBytes+1), "key")
		require.Equal(t, http.StatusRequestEntityTooLarge, status)
		assert.Empty(t, output)
	})
}

// postEvent sends a signed request and captures the response status and receiver output.
func postEvent(body, signingKey string) (int, string) {
	timestamp := strconv.FormatInt(time.Now().Unix(), unixSecondsBase)
	mac := hmac.New(sha256.New, []byte(signingKey))
	fmt.Fprint(mac, timestamp+"."+body)

	req := httptest.NewRequest(http.MethodPost, "/events", strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Snitch-Timestamp", timestamp)
	req.Header.Set("X-Snitch-Signature", fmt.Sprintf("v1=%x", mac.Sum(nil)))

	var out bytes.Buffer
	res := httptest.NewRecorder()
	handler([]byte("key"), &out).ServeHTTP(res, req)
	return res.Code, out.String()
}

package relay

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/pion/webrtc/v4"
)

// loadOrMakeCert reads the certificate and key from path, or makes a new
// self-signed ECDSA P-256 certificate, valid for 10 years, and writes it
// there. WebRTC checks only the fingerprint, so the validity barely matters.
// A file that can't be read is replaced, which changes the fingerprint: paired
// editors then have to pair again.
func loadOrMakeCert(path string, logf func(string, ...any)) (webrtc.Certificate, error) {
	if b, err := os.ReadFile(path); err == nil {
		if c, err := parseCert(b); err == nil {
			return c, nil
		} else {
			logf("webrtc: %s is unreadable (%v); making a new certificate", path, err)
		}
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	serial, err := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
	if err != nil {
		return webrtc.Certificate{}, err
	}
	tmpl := &x509.Certificate{
		SerialNumber: serial,
		Subject:      pkix.Name{CommonName: "badge-turntable"},
		NotBefore:    time.Now().Add(-24 * time.Hour),
		NotAfter:     time.Now().AddDate(10, 0, 0),
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	keyDER, err := x509.MarshalECPrivateKey(key)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	out := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	out = append(out, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: keyDER})...)
	if dir := filepath.Dir(path); dir != "" {
		_ = os.MkdirAll(dir, 0o700)
	}
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, out, 0o600); err != nil {
		return webrtc.Certificate{}, fmt.Errorf("can't save the certificate: %w", err)
	}
	if err := os.Rename(tmp, path); err != nil {
		return webrtc.Certificate{}, fmt.Errorf("can't save the certificate: %w", err)
	}
	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	logf("webrtc: made a new certificate in %s", path)
	return webrtc.CertificateFromX509(key, cert), nil
}

func parseCert(b []byte) (webrtc.Certificate, error) {
	var certDER, keyDER []byte
	for rest := b; ; {
		var blk *pem.Block
		blk, rest = pem.Decode(rest)
		if blk == nil {
			break
		}
		switch blk.Type {
		case "CERTIFICATE":
			certDER = blk.Bytes
		case "EC PRIVATE KEY":
			keyDER = blk.Bytes
		}
	}
	if certDER == nil || keyDER == nil {
		return webrtc.Certificate{}, fmt.Errorf("no certificate and key")
	}
	key, err := x509.ParseECPrivateKey(keyDER)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	cert, err := x509.ParseCertificate(certDER)
	if err != nil {
		return webrtc.Certificate{}, err
	}
	return webrtc.CertificateFromX509(key, cert), nil
}

// fingerprintOf gives "sha-256 AA:BB:…", as Info and Pair report it.
func fingerprintOf(c webrtc.Certificate) (string, error) {
	fps, err := c.GetFingerprints()
	if err != nil {
		return "", err
	}
	for _, f := range fps {
		if strings.EqualFold(f.Algorithm, "sha-256") {
			return "sha-256 " + strings.ToUpper(f.Value), nil
		}
	}
	return "", fmt.Errorf("no sha-256 fingerprint")
}

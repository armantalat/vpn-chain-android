package main

import (
	"crypto/des"
	"crypto/md5"
	"crypto/rand"
	"crypto/rc4"
	"crypto/sha1"
	"encoding/binary"
	"encoding/hex"
	"strings"
)

// =============================================================================
// Pure-Go MD4 (RFC 1320) - zero external dependencies
// =============================================================================
func md4Sum(data []byte) [16]byte {
	origLenBits := uint64(len(data)) * 8
	msg := make([]byte, len(data), len(data)+72)
	copy(msg, data)
	msg = append(msg, 0x80)
	for len(msg)%64 != 56 {
		msg = append(msg, 0)
	}
	var lenBuf [8]byte
	binary.LittleEndian.PutUint64(lenBuf[:], origLenBits)
	msg = append(msg, lenBuf[:]...)

	var a uint32 = 0x67452301
	var b uint32 = 0xefcdab89
	var c uint32 = 0x98badcfe
	var d uint32 = 0x10325476

	rotl := func(x uint32, n uint) uint32 {
		return (x << n) | (x >> (32 - n))
	}

	var X [16]uint32
	s1 := [4]uint{3, 7, 11, 19}
	s2 := [4]uint{3, 5, 9, 13}
	s3 := [4]uint{3, 9, 11, 15}
	order3 := [16]int{0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15}

	for off := 0; off < len(msg); off += 64 {
		for i := 0; i < 16; i++ {
			X[i] = binary.LittleEndian.Uint32(msg[off+i*4 : off+i*4+4])
		}
		aa, bb, cc, dd := a, b, c, d

		// Round 1
		for i := 0; i < 16; i++ {
			s := s1[i%4]
			switch i % 4 {
			case 0:
				a = rotl(a+((b&c)|((^b)&d))+X[i], s)
			case 1:
				d = rotl(d+((a&b)|((^a)&c))+X[i], s)
			case 2:
				c = rotl(c+((d&a)|((^d)&b))+X[i], s)
			case 3:
				b = rotl(b+((c&d)|((^c)&a))+X[i], s)
			}
		}

		// Round 2
		for i := 0; i < 16; i++ {
			k := (i%4)*4 + (i / 4)
			s := s2[i%4]
			switch i % 4 {
			case 0:
				a = rotl(a+((b&c)|(b&d)|(c&d))+X[k]+0x5a827999, s)
			case 1:
				d = rotl(d+((a&b)|(a&c)|(b&c))+X[k]+0x5a827999, s)
			case 2:
				c = rotl(c+((d&a)|(d&b)|(a&b))+X[k]+0x5a827999, s)
			case 3:
				b = rotl(b+((c&d)|(c&a)|(d&a))+X[k]+0x5a827999, s)
			}
		}

		// Round 3
		for i := 0; i < 16; i++ {
			k := order3[i]
			s := s3[i%4]
			switch i % 4 {
			case 0:
				a = rotl(a+(b^c^d)+X[k]+0x6ed9eba1, s)
			case 1:
				d = rotl(d+(a^b^c)+X[k]+0x6ed9eba1, s)
			case 2:
				c = rotl(c+(d^a^b)+X[k]+0x6ed9eba1, s)
			case 3:
				b = rotl(b+(c^d^a)+X[k]+0x6ed9eba1, s)
			}
		}

		a += aa
		b += bb
		c += cc
		d += dd
	}

	var out [16]byte
	binary.LittleEndian.PutUint32(out[0:4], a)
	binary.LittleEndian.PutUint32(out[4:8], b)
	binary.LittleEndian.PutUint32(out[8:12], c)
	binary.LittleEndian.PutUint32(out[12:16], d)
	return out
}

// =============================================================================
// MS-CHAPv2 (RFC 2759)
// =============================================================================
func utf16LE(s string) []byte {
	runes := []rune(s)
	out := make([]byte, len(runes)*2)
	for i, r := range runes {
		binary.LittleEndian.PutUint16(out[i*2:i*2+2], uint16(r))
	}
	return out
}

func ntPasswordHash(password string) [16]byte {
	return md4Sum(utf16LE(password))
}

func challengeHash(peerChallenge, authChallenge []byte, username string) [8]byte {
	// RFC 2759: strip Windows DOMAIN\ prefix if present? Keep exact username sent in Response.
	h := sha1.New()
	h.Write(peerChallenge[:16])
	h.Write(authChallenge[:16])
	h.Write([]byte(username))
	sum := h.Sum(nil)
	var out [8]byte
	copy(out[:], sum[:8])
	return out
}

func expandDesKey7to8(k7 []byte) []byte {
	out := make([]byte, 8)
	out[0] = k7[0] & 0xFE
	out[1] = ((k7[0] << 7) | (k7[1] >> 1)) & 0xFE
	out[2] = ((k7[1] << 6) | (k7[2] >> 2)) & 0xFE
	out[3] = ((k7[2] << 5) | (k7[3] >> 3)) & 0xFE
	out[4] = ((k7[3] << 4) | (k7[4] >> 4)) & 0xFE
	out[5] = ((k7[4] << 3) | (k7[5] >> 5)) & 0xFE
	out[6] = ((k7[5] << 2) | (k7[6] >> 6)) & 0xFE
	out[7] = (k7[6] << 1) & 0xFE
	return out
}

func desEncryptBlock(challenge8, key7 []byte) []byte {
	blk, err := des.NewCipher(expandDesKey7to8(key7))
	if err != nil {
		return make([]byte, 8)
	}
	dst := make([]byte, 8)
	blk.Encrypt(dst, challenge8)
	return dst
}

func generateNTResponse(authChallenge, peerChallenge []byte, username, password string) (ntResp [24]byte, pwHashHash [16]byte) {
	ch := challengeHash(peerChallenge, authChallenge, username)
	pwHash := ntPasswordHash(password)
	pwHashHash = md4Sum(pwHash[:])

	var zp [21]byte
	copy(zp[:16], pwHash[:])
	copy(ntResp[0:8], desEncryptBlock(ch[:], zp[0:7]))
	copy(ntResp[8:16], desEncryptBlock(ch[:], zp[7:14]))
	copy(ntResp[16:24], desEncryptBlock(ch[:], zp[14:21]))
	return ntResp, pwHashHash
}

func verifyAuthenticatorResponse(pwHashHash [16]byte, ntResp [24]byte, peerChallenge, authChallenge []byte, username, serverMsg string) bool {
	magic1 := []byte("Magic server to client signing constant")
	magic2 := []byte("Pad to make it do more than one iteration")

	h1 := sha1.New()
	h1.Write(pwHashHash[:])
	h1.Write(ntResp[:])
	h1.Write(magic1)
	digest := h1.Sum(nil)

	ch := challengeHash(peerChallenge, authChallenge, username)
	h2 := sha1.New()
	h2.Write(digest)
	h2.Write(ch[:])
	h2.Write(magic2)
	expected := "S=" + strings.ToUpper(hex.EncodeToString(h2.Sum(nil)))
	return strings.Contains(strings.ToUpper(serverMsg), expected)
}

func chapMD5Response(id byte, password string, challenge []byte) []byte {
	h := md5.New()
	h.Write([]byte{id})
	h.Write([]byte(password))
	h.Write(challenge)
	return h.Sum(nil)
}

func randomBytes(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

// =============================================================================
// MPPE-128 Encryption (RFC 3078 + RFC 3079)
// =============================================================================
var (
	mppePad1   = make([]byte, 40) // 40 bytes of 0x00
	mppePad2   = bytesRepeat(0xF2, 40)
	mppeMagic1 = []byte("This is the MPPE Master Key")
	mppeMagic2 = []byte("On the client side, this is the send key; on the server side, it is the receive key.")
	mppeMagic3 = []byte("On the client side, this is the receive key; on the server side, it is the send key.")
)

func bytesRepeat(b byte, count int) []byte {
	out := make([]byte, count)
	for i := range out {
		out[i] = b
	}
	return out
}

func mppeGetMasterKey(pwHashHash [16]byte, ntResp [24]byte) [16]byte {
	h := sha1.New()
	h.Write(pwHashHash[:])
	h.Write(ntResp[:])
	h.Write(mppeMagic1)
	var out [16]byte
	copy(out[:], h.Sum(nil)[:16])
	return out
}

func mppeGetAsymmetricStartKey(masterKey [16]byte, isSend bool) [16]byte {
	h := sha1.New()
	h.Write(masterKey[:])
	h.Write(mppePad1)
	if isSend {
		h.Write(mppeMagic2)
	} else {
		h.Write(mppeMagic3)
	}
	h.Write(mppePad2)
	var out [16]byte
	copy(out[:], h.Sum(nil)[:16])
	return out
}

func mppeGetNewKeyFromSHA(startKey, sessionKey [16]byte) [16]byte {
	h := sha1.New()
	h.Write(startKey[:])
	h.Write(mppePad1)
	h.Write(sessionKey[:])
	h.Write(mppePad2)
	var out [16]byte
	copy(out[:], h.Sum(nil)[:16])
	return out
}

type mppeState struct {
	startKey   [16]byte
	sessionKey [16]byte
	cipher     *rc4.Cipher
	ccount     uint16
	stateless  bool
	hasSent    bool
}

func newMppeState(startKey [16]byte, stateless bool) *mppeState {
	st := &mppeState{
		startKey:   startKey,
		sessionKey: startKey,
		stateless:  stateless,
		ccount:     0,
	}
	// RFC 3078 Section 7.1: Initial session key = GetNewKeyFromSHA(StartKey, StartKey)
	st.rekey(true)
	return st
}

func (m *mppeState) rekey(initial bool) {
	interim := mppeGetNewKeyFromSHA(m.startKey, m.sessionKey)
	if initial {
		m.sessionKey = interim
	} else {
		c, _ := rc4.NewCipher(interim[:])
		var nextKey [16]byte
		c.XORKeyStream(nextKey[:], interim[:])
		m.sessionKey = nextKey
	}
	m.cipher, _ = rc4.NewCipher(m.sessionKey[:])
}

// Encrypt wraps a PPP payload (e.g. [0x00, 0x21, ipv4...]) into MPPE format [hdrHi, hdrLo, ciphertext...]
func (m *mppeState) Encrypt(pppPayload []byte) []byte {
	var flags byte = 0x10 // Bit D (Encrypted)
	if m.stateless {
		flags |= 0x80 // Bit A (Flushed)
	} else if m.hasSent && (m.ccount&0xFF) == 0 {
		m.rekey(false)
		flags |= 0x80
	}
	m.hasSent = true

	out := make([]byte, 2+len(pppPayload))
	out[0] = flags | byte((m.ccount>>8)&0x0F)
	out[1] = byte(m.ccount & 0xFF)
	m.cipher.XORKeyStream(out[2:], pppPayload)

	m.ccount = (m.ccount + 1) & 0x0FFF
	if m.stateless {
		m.rekey(false)
	}
	return out
}

// Decrypt unwraps an MPPE packet [hdrHi, hdrLo, ciphertext...] back to PPP payload
func (m *mppeState) Decrypt(mppePacket []byte) ([]byte, bool) {
	if len(mppePacket) < 4 {
		return nil, false
	}
	flags := mppePacket[0] & 0xF0
	if (flags & 0x10) == 0 {
		// Not encrypted
		return nil, false
	}
	flushed := (flags & 0x80) != 0
	pktCount := (uint16(mppePacket[0]&0x0F) << 8) | uint16(mppePacket[1])

	if m.stateless {
		steps := 0
		for m.ccount != pktCount && steps < 4096 {
			m.rekey(false)
			m.ccount = (m.ccount + 1) & 0x0FFF
			steps++
		}
		if m.ccount != pktCount {
			return nil, false
		}
		plain := make([]byte, len(mppePacket)-2)
		m.cipher.XORKeyStream(plain, mppePacket[2:])
		m.rekey(false)
		m.ccount = (m.ccount + 1) & 0x0FFF
		return plain, true
	}

	// Stateful mode
	if flushed {
		if m.ccount != pktCount {
			steps := 0
			for m.ccount != pktCount && steps < 4096 {
				m.ccount = (m.ccount + 1) & 0x0FFF
				if (m.ccount & 0xFF) == 0 {
					m.rekey(false)
				}
				steps++
			}
		} else if m.hasSent && (m.ccount&0xFF) == 0 {
			m.rekey(false)
		}
	} else if m.ccount != pktCount {
		// Out-of-order packet in stateful mode cannot be decrypted without resync
		return nil, false
	}
	m.hasSent = true

	plain := make([]byte, len(mppePacket)-2)
	m.cipher.XORKeyStream(plain, mppePacket[2:])
	m.ccount = (m.ccount + 1) & 0x0FFF
	return plain, true
}

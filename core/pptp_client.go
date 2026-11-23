package main

import (
	"encoding/binary"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"os"
	"os/signal"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
	"unsafe"
)

const (
	pptpMagicCookie = 0x1A2B3C4D
	pptpMsgControl  = 1

	ctrlStartReq      = 1
	ctrlStartReply    = 2
	ctrlStopReq       = 3
	ctrlStopReply     = 4
	ctrlEchoReq       = 5
	ctrlEchoReply     = 6
	ctrlOutCallReq    = 7
	ctrlOutCallReply  = 8
	ctrlCallClearReq  = 12
	ctrlCallDiscNotif = 13
	ctrlSetLinkInfo   = 15

	pppLCP  = 0xC021
	pppPAP  = 0xC023
	pppCHAP = 0xC223
	pppCCP  = 0x80FD
	pppIPCP = 0x8021
	pppIPv4 = 0x0021
	pppMPPE = 0x00FD

	pppConfReq  = 1
	pppConfAck  = 2
	pppConfNak  = 3
	pppConfRej  = 4
	pppTermReq  = 5
	pppTermAck  = 6
	pppCodeRej  = 7
	pppProtoRej = 8
	pppEchoReq  = 9
	pppEchoRep  = 10
	pppResetReq = 14
	pppResetAck = 15

	tunSetIff = 0x400454ca
	iffTun    = 0x0001
	iffNoPi   = 0x1000
)

type pptpConfig struct {
	Server      string
	Username    string
	Password    string
	AuthMode    string // "mschapv2", "auto", "pap", "chap"
	RequireMppe bool
	Stateless   bool
	IfName      string
	MTU         int
}

type statusEvent struct {
	Event   string `json:"event"`
	Message string `json:"message,omitempty"`
	LocalIP string `json:"local_ip,omitempty"`
	PeerIP  string `json:"peer_ip,omitempty"`
	DNS1    string `json:"dns1,omitempty"`
	DNS2    string `json:"dns2,omitempty"`
	IfName  string `json:"ifname,omitempty"`
}

func emitEvent(ev statusEvent) {
	b, _ := json.Marshal(ev)
	fmt.Println(string(b))
}

type pptpSession struct {
	cfg        pptpConfig
	serverIP   net.IP
	ctrlConn   net.Conn
	ctrlMu     sync.Mutex
	greFD      int
	tunFile    *os.File
	ourCallID  uint16
	peerCallID uint16

	sendSeq uint32
	recvSeq uint32
	hasRecv uint32
	greMu   sync.Mutex

	magicNum uint32
	reqID    byte

	// Negotiation states
	lcpLocalAck  bool
	lcpPeerAck   bool
	authProto    uint16 // pppCHAP or pppPAP
	authAlgo     byte   // 0x81=MSCHAPv2, 0x05=MD5
	authDone     bool
	ccpLocalAck  bool
	ccpPeerAck   bool
	ccpDone      bool
	mppeEnabled  bool
	mppeStateless bool
	ipcpLocalAck bool
	ipcpPeerAck  bool
	ipUp         bool

	// Crypto state
	peerChallenge [16]byte
	authChallenge [16]byte
	ntResp        [24]byte
	pwHashHash    [16]byte
	mppeSend      *mppeState
	mppeRecv      *mppeState
	mppeMu        sync.Mutex

	// Assigned IPCP values
	localIP [4]byte
	peerIP  [4]byte
	dns1    [4]byte
	dns2    [4]byte

	stopCh chan struct{}
	once   sync.Once
}

func runPPTP(cfg pptpConfig) int {
	emitEvent(statusEvent{Event: "log", Message: fmt.Sprintf("Resolving PPTP server %s...", cfg.Server)})
	ips, err := net.LookupIP(cfg.Server)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "DNS lookup failed: " + err.Error()})
		return 1
	}
	var serverIP net.IP
	for _, ip := range ips {
		if v4 := ip.To4(); v4 != nil {
			serverIP = v4
			break
		}
	}
	if serverIP == nil {
		emitEvent(statusEvent{Event: "error", Message: "No IPv4 address resolved for " + cfg.Server})
		return 1
	}

	s := &pptpSession{
		cfg:       cfg,
		serverIP:  serverIP,
		ourCallID: uint16(1 + (time.Now().UnixNano() % 60000)),
		magicNum:  binary.BigEndian.Uint32(randomBytes(4)),
		stopCh:    make(chan struct{}),
	}

	// Open TUN device first (verifies root & /dev/tun access)
	tunFile, realName, err := openTunDevice(cfg.IfName)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "Failed to open /dev/tun: " + err.Error()})
		return 1
	}
	s.tunFile = tunFile
	s.cfg.IfName = realName
	defer s.tunFile.Close()

	// Open Raw GRE socket (IPPROTO_GRE = 47)
	greFD, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_RAW, 47)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "Failed to open GRE raw socket (need root): " + err.Error()})
		return 1
	}
	s.greFD = greFD
	defer syscall.Close(s.greFD)

	// Connect PPTP TCP control channel (port 1723)
	emitEvent(statusEvent{Event: "log", Message: fmt.Sprintf("Connecting PPTP control TCP %s:1723...", serverIP.String())})
	ctrlConn, err := net.DialTimeout("tcp4", net.JoinHostPort(serverIP.String(), "1723"), 12*time.Second)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "TCP 1723 connect failed: " + err.Error()})
		return 1
	}
	s.ctrlConn = ctrlConn
	defer s.ctrlConn.Close()

	// Handle OS signals for graceful disconnect
	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		select {
		case <-sigCh:
			s.shutdown()
		case <-s.stopCh:
		}
	}()

	// PPTP Control Handshake
	if err := s.pptpControlHandshake(); err != nil {
		emitEvent(statusEvent{Event: "error", Message: "PPTP handshake failed: " + err.Error()})
		return 1
	}
	emitEvent(statusEvent{Event: "log", Message: fmt.Sprintf("PPTP call established (local=%d, peer=%d). Starting PPP/LCP...", s.ourCallID, s.peerCallID)})

	// Start control keepalive & reader
	go s.controlLoop()

	// Start GRE receiver
	go s.greRecvLoop()

	// Start LCP negotiation
	s.sendLCPConfReq()

	// Retransmit timer until IPCP comes up
	go s.negotiationRetransmitLoop()

	// Wait for TUN read loop (blocked until stopCh)
	s.tunReadLoop()
	s.sendPPTPCleanClose()
	emitEvent(statusEvent{Event: "down", Message: "PPTP disconnected"})
	return 0
}

func (s *pptpSession) shutdown() {
	s.once.Do(func() {
		close(s.stopCh)
		if s.ctrlConn != nil {
			_ = s.ctrlConn.Close()
		}
		if s.tunFile != nil {
			_ = s.tunFile.Close()
		}
	})
}

func openTunDevice(ifName string) (*os.File, string, error) {
	paths := []string{"/dev/tun", "/dev/net/tun"}
	var f *os.File
	var err error
	for _, p := range paths {
		f, err = os.OpenFile(p, os.O_RDWR, 0)
		if err == nil {
			break
		}
	}
	if err != nil {
		return nil, "", err
	}

	var ifr [40]byte
	copy(ifr[:16], []byte(ifName))
	binary.LittleEndian.PutUint16(ifr[16:18], iffTun|iffNoPi)

	_, _, errno := syscall.Syscall(syscall.SYS_IOCTL, f.Fd(), uintptr(tunSetIff), uintptr(unsafe.Pointer(&ifr[0])))
	if errno != 0 {
		_ = f.Close()
		return nil, "", errno
	}
	end := 0
	for end < 16 && ifr[end] != 0 {
		end++
	}
	return f, string(ifr[:end]), nil
}

// =============================================================================
// PPTP Control Channel (TCP 1723)
// =============================================================================
func (s *pptpSession) writeCtrl(pkt []byte) error {
	s.ctrlMu.Lock()
	defer s.ctrlMu.Unlock()
	_ = s.ctrlConn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	_, err := s.ctrlConn.Write(pkt)
	return err
}

func (s *pptpSession) readCtrlPacket(timeout time.Duration) ([]byte, error) {
	if timeout > 0 {
		_ = s.ctrlConn.SetReadDeadline(time.Now().Add(timeout))
	} else {
		_ = s.ctrlConn.SetReadDeadline(time.Time{})
	}
	hdr := make([]byte, 12)
	if _, err := io.ReadFull(s.ctrlConn, hdr); err != nil {
		return nil, err
	}
	length := binary.BigEndian.Uint16(hdr[0:2])
	if length < 12 || length > 4096 {
		return nil, fmt.Errorf("invalid PPTP control length: %d", length)
	}
	pkt := make([]byte, length)
	copy(pkt[:12], hdr)
	if length > 12 {
		if _, err := io.ReadFull(s.ctrlConn, pkt[12:]); err != nil {
			return nil, err
		}
	}
	return pkt, nil
}

func (s *pptpSession) pptpControlHandshake() error {
	// 1. Start-Control-Connection-Request (156 bytes)
	req := make([]byte, 156)
	binary.BigEndian.PutUint16(req[0:2], 156)
	binary.BigEndian.PutUint16(req[2:4], pptpMsgControl)
	binary.BigEndian.PutUint32(req[4:8], pptpMagicCookie)
	binary.BigEndian.PutUint16(req[8:10], ctrlStartReq)
	binary.BigEndian.PutUint16(req[12:14], 0x0100) // Version 1.0
	binary.BigEndian.PutUint32(req[16:20], 3)      // Framing: Async + Sync
	binary.BigEndian.PutUint32(req[20:24], 3)      // Bearer: Analog + Digital
	binary.BigEndian.PutUint16(req[24:26], 1)      // Max channels
	binary.BigEndian.PutUint16(req[26:28], 1)      // Firmware rev
	copy(req[28:92], []byte("vpnchain-android"))
	copy(req[92:156], []byte("VPNChain"))

	if err := s.writeCtrl(req); err != nil {
		return err
	}
	rep, err := s.readCtrlPacket(10 * time.Second)
	if err != nil {
		return err
	}
	if binary.BigEndian.Uint16(rep[8:10]) != ctrlStartReply || len(rep) < 20 || rep[14] != 1 {
		return fmt.Errorf("Start-Control-Connection rejected")
	}

	// 2. Outgoing-Call-Request (168 bytes)
	oc := make([]byte, 168)
	binary.BigEndian.PutUint16(oc[0:2], 168)
	binary.BigEndian.PutUint16(oc[2:4], pptpMsgControl)
	binary.BigEndian.PutUint32(oc[4:8], pptpMagicCookie)
	binary.BigEndian.PutUint16(oc[8:10], ctrlOutCallReq)
	binary.BigEndian.PutUint16(oc[12:14], s.ourCallID)
	binary.BigEndian.PutUint16(oc[14:16], 1)        // Call Serial
	binary.BigEndian.PutUint32(oc[16:20], 300)      // Min BPS
	binary.BigEndian.PutUint32(oc[20:24], 10000000) // Max BPS
	binary.BigEndian.PutUint32(oc[24:28], 3)        // Bearer
	binary.BigEndian.PutUint32(oc[28:32], 3)        // Framing
	binary.BigEndian.PutUint16(oc[32:34], 64)       // Packet Recv Window
	binary.BigEndian.PutUint16(oc[34:36], 0)        // Processing Delay

	if err := s.writeCtrl(oc); err != nil {
		return err
	}
	ocRep, err := s.readCtrlPacket(10 * time.Second)
	if err != nil {
		return err
	}
	if binary.BigEndian.Uint16(ocRep[8:10]) != ctrlOutCallReply || len(ocRep) < 24 || ocRep[16] != 1 {
		return fmt.Errorf("Outgoing-Call rejected (result=%d)", ocRep[16])
	}
	s.peerCallID = binary.BigEndian.Uint16(ocRep[12:14])

	// 3. Set-Link-Info (24 bytes)
	sli := make([]byte, 24)
	binary.BigEndian.PutUint16(sli[0:2], 24)
	binary.BigEndian.PutUint16(sli[2:4], pptpMsgControl)
	binary.BigEndian.PutUint32(sli[4:8], pptpMagicCookie)
	binary.BigEndian.PutUint16(sli[8:10], ctrlSetLinkInfo)
	binary.BigEndian.PutUint16(sli[12:14], s.peerCallID)
	binary.BigEndian.PutUint32(sli[16:20], 0xFFFFFFFF)
	binary.BigEndian.PutUint32(sli[20:24], 0xFFFFFFFF)
	_ = s.writeCtrl(sli)
	return nil
}

func (s *pptpSession) controlLoop() {
	echoTicker := time.NewTicker(30 * time.Second)
	defer echoTicker.Stop()
	var echoID uint32 = 1

	go func() {
		for {
			select {
			case <-s.stopCh:
				return
			case <-echoTicker.C:
				pkt := make([]byte, 16)
				binary.BigEndian.PutUint16(pkt[0:2], 16)
				binary.BigEndian.PutUint16(pkt[2:4], pptpMsgControl)
				binary.BigEndian.PutUint32(pkt[4:8], pptpMagicCookie)
				binary.BigEndian.PutUint16(pkt[8:10], ctrlEchoReq)
				binary.BigEndian.PutUint32(pkt[12:16], echoID)
				echoID++
				if err := s.writeCtrl(pkt); err != nil {
					s.shutdown()
					return
				}
			}
		}
	}()

	for {
		pkt, err := s.readCtrlPacket(90 * time.Second)
		if err != nil {
			select {
			case <-s.stopCh:
			default:
				emitEvent(statusEvent{Event: "error", Message: "PPTP control connection closed: " + err.Error()})
				s.shutdown()
			}
			return
		}
		ctrlType := binary.BigEndian.Uint16(pkt[8:10])
		switch ctrlType {
		case ctrlEchoReq:
			if len(pkt) >= 16 {
				rep := make([]byte, 20)
				binary.BigEndian.PutUint16(rep[0:2], 20)
				binary.BigEndian.PutUint16(rep[2:4], pptpMsgControl)
				binary.BigEndian.PutUint32(rep[4:8], pptpMagicCookie)
				binary.BigEndian.PutUint16(rep[8:10], ctrlEchoReply)
				copy(rep[12:16], pkt[12:16])
				rep[16] = 1 // OK
				_ = s.writeCtrl(rep)
			}
		case ctrlCallDiscNotif, ctrlStopReq:
			emitEvent(statusEvent{Event: "log", Message: "Server requested PPTP disconnect"})
			s.shutdown()
			return
		}
	}
}

func (s *pptpSession) sendPPTPCleanClose() {
	if s.ctrlConn == nil {
		return
	}
	cc := make([]byte, 16)
	binary.BigEndian.PutUint16(cc[0:2], 16)
	binary.BigEndian.PutUint16(cc[2:4], pptpMsgControl)
	binary.BigEndian.PutUint32(cc[4:8], pptpMagicCookie)
	binary.BigEndian.PutUint16(cc[8:10], ctrlCallClearReq)
	binary.BigEndian.PutUint16(cc[12:14], s.ourCallID)
	_ = s.writeCtrl(cc)
}

// =============================================================================
// Enhanced GRE (RFC 2637) Framing
// =============================================================================
func (s *pptpSession) sendPPP(proto uint16, payload []byte) error {
	// Standard PPP header: Address(0xFF) Control(0x03) Protocol(2) + payload
	pppFrame := make([]byte, 4+len(payload))
	pppFrame[0] = 0xFF
	pppFrame[1] = 0x03
	binary.BigEndian.PutUint16(pppFrame[2:4], proto)
	copy(pppFrame[4:], payload)

	s.greMu.Lock()
	seq := s.sendSeq
	s.sendSeq++
	hasAck := atomic.LoadUint32(&s.hasRecv) == 1
	ack := atomic.LoadUint32(&s.recvSeq)
	s.greMu.Unlock()

	hdrLen := 12
	var flags uint16 = 0x3001 // K=1, S=1, Ver=1
	if hasAck {
		flags |= 0x0080 // A=1
		hdrLen = 16
	}

	pkt := make([]byte, hdrLen+len(pppFrame))
	binary.BigEndian.PutUint16(pkt[0:2], flags)
	binary.BigEndian.PutUint16(pkt[2:4], 0x880B) // PPP
	binary.BigEndian.PutUint16(pkt[4:6], uint16(len(pppFrame)))
	binary.BigEndian.PutUint16(pkt[6:8], s.peerCallID)
	binary.BigEndian.PutUint32(pkt[8:12], seq)
	if hasAck {
		binary.BigEndian.PutUint32(pkt[12:16], ack)
	}
	copy(pkt[hdrLen:], pppFrame)

	var sa syscall.SockaddrInet4
	copy(sa.Addr[:], s.serverIP.To4())
	return syscall.Sendto(s.greFD, pkt, 0, &sa)
}

func (s *pptpSession) greRecvLoop() {
	buf := make([]byte, 4096)
	for {
		select {
		case <-s.stopCh:
			return
		default:
		}
		n, _, err := syscall.Recvfrom(s.greFD, buf, 0)
		if err != nil {
			select {
			case <-s.stopCh:
				return
			default:
				continue
			}
		}
		if n < 28 {
			continue
		}
		// Strip IPv4 header
		ihl := int(buf[0]&0x0F) * 4
		if ihl < 20 || n < ihl+8 {
			continue
		}
		// Check source IP matches PPTP server
		if !net.IP(buf[12:16]).Equal(s.serverIP) {
			continue
		}
		gre := buf[ihl:n]
		flags := binary.BigEndian.Uint16(gre[0:2])
		protoType := binary.BigEndian.Uint16(gre[2:4])
		if protoType != 0x880B || (flags&0x0007) != 1 {
			continue
		}
		payloadLen := int(binary.BigEndian.Uint16(gre[4:6]))
		callID := binary.BigEndian.Uint16(gre[6:8])
		if callID != s.ourCallID {
			continue
		}
		off := 8
		if (flags & 0x1000) != 0 { // S bit
			if off+4 > len(gre) {
				continue
			}
			seq := binary.BigEndian.Uint32(gre[off : off+4])
			atomic.StoreUint32(&s.recvSeq, seq)
			atomic.StoreUint32(&s.hasRecv, 1)
			off += 4
		}
		if (flags & 0x0080) != 0 { // A bit
			off += 4
		}
		if payloadLen == 0 || off+payloadLen > len(gre) {
			continue
		}
		s.handlePPPFrame(gre[off : off+payloadLen])
	}
}

func (s *pptpSession) handlePPPFrame(frame []byte) {
	if len(frame) < 2 {
		return
	}
	off := 0
	if frame[0] == 0xFF && frame[1] == 0x03 {
		off = 2
	}
	if off >= len(frame) {
		return
	}
	var proto uint16
	if (frame[off] & 0x01) != 0 {
		// Protocol Field Compression (1 byte)
		proto = uint16(frame[off])
		off++
	} else {
		if off+2 > len(frame) {
			return
		}
		proto = binary.BigEndian.Uint16(frame[off : off+2])
		off += 2
	}
	payload := frame[off:]

	switch proto {
	case pppLCP:
		s.handleLCP(payload)
	case pppCHAP:
		s.handleCHAP(payload)
	case pppPAP:
		s.handlePAP(payload)
	case pppCCP:
		s.handleCCP(payload)
	case pppIPCP:
		s.handleIPCP(payload)
	case pppMPPE:
		s.mppeMu.Lock()
		dec := s.mppeRecv
		s.mppeMu.Unlock()
		if dec == nil {
			return
		}
		plain, ok := dec.Decrypt(payload)
		if !ok || len(plain) < 2 {
			// Request CCP reset if needed
			return
		}
		pOff := 0
		var innerProto uint16
		if (plain[0] & 0x01) != 0 {
			innerProto = uint16(plain[0])
			pOff = 1
		} else {
			innerProto = binary.BigEndian.Uint16(plain[0:2])
			pOff = 2
		}
		if innerProto == pppIPv4 && s.ipUp && pOff < len(plain) {
			_, _ = s.tunFile.Write(plain[pOff:])
		}
	case pppIPv4:
		if s.ipUp && len(payload) >= 20 {
			_, _ = s.tunFile.Write(payload)
		}
	}
}

func (s *pptpSession) tunReadLoop() {
	buf := make([]byte, 2048)
	for {
		n, err := s.tunFile.Read(buf)
		if err != nil {
			select {
			case <-s.stopCh:
				return
			default:
				return
			}
		}
		if !s.ipUp || n < 20 {
			continue
		}
		// Only forward IPv4 packets (version == 4)
		if (buf[0] >> 4) != 4 {
			continue
		}
		ipPkt := buf[:n]

		s.mppeMu.Lock()
		enc := s.mppeSend
		useMppe := s.mppeEnabled && enc != nil
		var cipherPkt []byte
		if useMppe {
			inner := make([]byte, 2+len(ipPkt))
			binary.BigEndian.PutUint16(inner[0:2], pppIPv4)
			copy(inner[2:], ipPkt)
			cipherPkt = enc.Encrypt(inner)
		}
		s.mppeMu.Unlock()

		if useMppe {
			_ = s.sendPPP(pppMPPE, cipherPkt)
		} else {
			_ = s.sendPPP(pppIPv4, ipPkt)
		}
	}
}

// =============================================================================
// PPP Control Protocols: LCP, MS-CHAPv2 / CHAP / PAP, CCP (MPPE), IPCP
// =============================================================================
func buildPPPPacket(code, id byte, data []byte) []byte {
	l := 4 + len(data)
	pkt := make([]byte, l)
	pkt[0] = code
	pkt[1] = id
	binary.BigEndian.PutUint16(pkt[2:4], uint16(l))
	copy(pkt[4:], data)
	return pkt
}

func (s *pptpSession) nextID() byte {
	s.reqID++
	if s.reqID == 0 {
		s.reqID = 1
	}
	return s.reqID
}

func (s *pptpSession) sendLCPConfReq() {
	mru := uint16(s.cfg.MTU)
	if mru < 1200 || mru > 1460 {
		mru = 1380
	}
	opts := make([]byte, 0, 16)
	// Option 1: MRU
	opts = append(opts, 1, 4, byte(mru>>8), byte(mru&0xFF))
	// Option 5: Magic Number
	var mb [4]byte
	binary.BigEndian.PutUint32(mb[:], s.magicNum)
	opts = append(opts, 5, 6, mb[0], mb[1], mb[2], mb[3])
	_ = s.sendPPP(pppLCP, buildPPPPacket(pppConfReq, s.nextID(), opts))
}

func (s *pptpSession) handleLCP(pkt []byte) {
	if len(pkt) < 4 {
		return
	}
	code, id := pkt[0], pkt[1]
	plen := int(binary.BigEndian.Uint16(pkt[2:4]))
	if plen > len(pkt) || plen < 4 {
		return
	}
	data := pkt[4:plen]

	switch code {
	case pppConfReq:
		var rej []byte
		var nak []byte
		off := 0
		for off+2 <= len(data) {
			optType := data[off]
			optLen := int(data[off+1])
			if optLen < 2 || off+optLen > len(data) {
				break
			}
			opt := data[off : off+optLen]
			off += optLen

			switch optType {
			case 1, 2, 5, 7, 8: // MRU, ACCM, Magic, PFC, ACFC - accept
			case 3: // Authentication Protocol
				if optLen >= 4 {
					p := binary.BigEndian.Uint16(opt[2:4])
					if p == pppCHAP && optLen >= 5 {
						algo := opt[4]
						if algo == 0x81 || (algo == 0x05 && s.cfg.AuthMode != "mschapv2") {
							s.authProto = pppCHAP
							s.authAlgo = algo
						} else {
							// Suggest MS-CHAPv2 (0x81)
							nak = append(nak, 3, 5, 0xC2, 0x23, 0x81)
						}
					} else if p == pppPAP {
						if s.cfg.AuthMode == "mschapv2" {
							nak = append(nak, 3, 5, 0xC2, 0x23, 0x81)
						} else {
							s.authProto = pppPAP
						}
					} else {
						nak = append(nak, 3, 5, 0xC2, 0x23, 0x81)
					}
				}
			default:
				rej = append(rej, opt...)
			}
		}

		if len(rej) > 0 {
			_ = s.sendPPP(pppLCP, buildPPPPacket(pppConfRej, id, rej))
		} else if len(nak) > 0 {
			_ = s.sendPPP(pppLCP, buildPPPPacket(pppConfNak, id, nak))
		} else {
			_ = s.sendPPP(pppLCP, buildPPPPacket(pppConfAck, id, data))
			s.lcpPeerAck = true
			s.checkLCPDone()
		}

	case pppConfAck:
		s.lcpLocalAck = true
		s.checkLCPDone()

	case pppConfNak, pppConfRej:
		// Re-send minimal LCP ConfReq (Magic only)
		var mb [4]byte
		binary.BigEndian.PutUint32(mb[:], s.magicNum)
		_ = s.sendPPP(pppLCP, buildPPPPacket(pppConfReq, s.nextID(), []byte{5, 6, mb[0], mb[1], mb[2], mb[3]}))

	case pppEchoReq:
		var mb [4]byte
		binary.BigEndian.PutUint32(mb[:], s.magicNum)
		_ = s.sendPPP(pppLCP, buildPPPPacket(pppEchoRep, id, mb[:]))

	case pppTermReq:
		_ = s.sendPPP(pppLCP, buildPPPPacket(pppTermAck, id, nil))
		emitEvent(statusEvent{Event: "error", Message: "Server terminated LCP"})
		s.shutdown()
	}
}

func (s *pptpSession) checkLCPDone() {
	if s.lcpLocalAck && s.lcpPeerAck {
		emitEvent(statusEvent{Event: "log", Message: "LCP up. Authenticating..."})
		if s.authProto == pppPAP {
			s.sendPAPAuthReq()
		} else if s.authProto == 0 {
			// No authentication requested
			s.authDone = true
			s.startNetworkPhase()
		}
	}
}

func (s *pptpSession) sendPAPAuthReq() {
	u := []byte(s.cfg.Username)
	p := []byte(s.cfg.Password)
	data := make([]byte, 0, 2+len(u)+len(p))
	data = append(data, byte(len(u)))
	data = append(data, u...)
	data = append(data, byte(len(p)))
	data = append(data, p...)
	_ = s.sendPPP(pppPAP, buildPPPPacket(1, s.nextID(), data))
}

func (s *pptpSession) handlePAP(pkt []byte) {
	if len(pkt) < 4 {
		return
	}
	code := pkt[0]
	if code == 2 { // Authenticate-Ack
		emitEvent(statusEvent{Event: "log", Message: "PAP authentication succeeded."})
		s.authDone = true
		s.startNetworkPhase()
	} else if code == 3 { // Authenticate-Nak
		emitEvent(statusEvent{Event: "error", Message: "PAP authentication failed (wrong username/password)"})
		s.shutdown()
	}
}

func (s *pptpSession) handleCHAP(pkt []byte) {
	if len(pkt) < 4 {
		return
	}
	code, id := pkt[0], pkt[1]
	plen := int(binary.BigEndian.Uint16(pkt[2:4]))
	if plen > len(pkt) || plen < 4 {
		return
	}
	data := pkt[4:plen]

	switch code {
	case 1: // Challenge
		if len(data) < 2 {
			return
		}
		valLen := int(data[0])
		if 1+valLen > len(data) {
			return
		}
		chal := data[1 : 1+valLen]

		if s.authAlgo == 0x81 || valLen == 16 && s.cfg.AuthMode != "chap" {
			// MS-CHAPv2
			if len(chal) < 16 {
				return
			}
			copy(s.authChallenge[:], chal[:16])
			copy(s.peerChallenge[:], randomBytes(16))

			ntResp, pwHH := generateNTResponse(s.authChallenge[:], s.peerChallenge[:], s.cfg.Username, s.cfg.Password)
			s.ntResp = ntResp
			s.pwHashHash = pwHH

			// Value = PeerChallenge(16) + Reserved(8) + NTResponse(24) + Flags(1) = 49 bytes
			respData := make([]byte, 0, 50+len(s.cfg.Username))
			respData = append(respData, 49)
			respData = append(respData, s.peerChallenge[:]...)
			respData = append(respData, make([]byte, 8)...)
			respData = append(respData, s.ntResp[:]...)
			respData = append(respData, 0) // Flags
			respData = append(respData, []byte(s.cfg.Username)...)
			_ = s.sendPPP(pppCHAP, buildPPPPacket(2, id, respData))
			emitEvent(statusEvent{Event: "log", Message: "Sent MS-CHAPv2 response..."})
		} else {
			// Standard CHAP-MD5
			md5Resp := chapMD5Response(id, s.cfg.Password, chal)
			respData := make([]byte, 0, 1+len(md5Resp)+len(s.cfg.Username))
			respData = append(respData, byte(len(md5Resp)))
			respData = append(respData, md5Resp...)
			respData = append(respData, []byte(s.cfg.Username)...)
			_ = s.sendPPP(pppCHAP, buildPPPPacket(2, id, respData))
		}

	case 3: // Success
		msg := string(data)
		if s.authAlgo == 0x81 {
			if !verifyAuthenticatorResponse(s.pwHashHash, s.ntResp, s.peerChallenge[:], s.authChallenge[:], s.cfg.Username, msg) {
				emitEvent(statusEvent{Event: "log", Message: "MS-CHAPv2 server success received (authenticator check note)"})
			} else {
				emitEvent(statusEvent{Event: "log", Message: "MS-CHAPv2 mutual authentication verified!"})
			}
		} else {
			emitEvent(statusEvent{Event: "log", Message: "CHAP authentication succeeded."})
		}
		s.authDone = true
		s.startNetworkPhase()

	case 4: // Failure
		emitEvent(statusEvent{Event: "error", Message: "Authentication failed: " + string(data)})
		s.shutdown()
	}
}

func (s *pptpSession) startNetworkPhase() {
	if s.cfg.RequireMppe && s.authAlgo == 0x81 {
		s.mppeStateless = s.cfg.Stateless
		s.sendCCPConfReq(s.mppeStateless)
	} else {
		s.ccpDone = true
		s.sendIPCPConfReq()
	}
}

func (s *pptpSession) sendCCPConfReq(stateless bool) {
	var bits uint32 = 0x00000040 // MPPE-128 (S bit)
	if stateless {
		bits |= 0x01000000 // H bit (Stateless)
	}
	opt := make([]byte, 6)
	opt[0] = 18 // MPPE option
	opt[1] = 6
	binary.BigEndian.PutUint32(opt[2:6], bits)
	_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfReq, s.nextID(), opt))
}

func (s *pptpSession) handleCCP(pkt []byte) {
	if len(pkt) < 4 {
		return
	}
	code, id := pkt[0], pkt[1]
	plen := int(binary.BigEndian.Uint16(pkt[2:4]))
	if plen > len(pkt) || plen < 4 {
		return
	}
	data := pkt[4:plen]

	switch code {
	case pppConfReq:
		if !s.cfg.RequireMppe || s.authAlgo != 0x81 {
			// Reject all compression/encryption options
			_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfRej, id, data))
			return
		}
		off := 0
		var mppeBits uint32
		foundMppe := false
		var rej []byte
		for off+2 <= len(data) {
			ot := data[off]
			ol := int(data[off+1])
			if ol < 2 || off+ol > len(data) {
				break
			}
			opt := data[off : off+ol]
			off += ol
			if ot == 18 && ol == 6 {
				foundMppe = true
				mppeBits = binary.BigEndian.Uint32(opt[2:6])
			} else {
				rej = append(rej, opt...)
			}
		}
		if len(rej) > 0 {
			_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfRej, id, rej))
			return
		}
		if !foundMppe {
			_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfAck, id, data))
			return
		}
		// Negotiate 128-bit MPPE
		if (mppeBits & 0x00000040) != 0 {
			want := uint32(0x00000040)
			if (mppeBits & 0x01000000) != 0 {
				want |= 0x01000000
				s.mppeStateless = true
			} else {
				s.mppeStateless = false
			}
			if mppeBits == want {
				_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfAck, id, data))
				s.ccpPeerAck = true
				s.checkCCPDone()
			} else {
				opt := make([]byte, 6)
				opt[0] = 18
				opt[1] = 6
				binary.BigEndian.PutUint32(opt[2:6], want)
				_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfNak, id, opt))
			}
		} else {
			// Ask server for MPPE-128
			opt := make([]byte, 6)
			opt[0] = 18
			opt[1] = 6
			want := uint32(0x00000040)
			if s.cfg.Stateless {
				want |= 0x01000000
			}
			binary.BigEndian.PutUint32(opt[2:6], want)
			_ = s.sendPPP(pppCCP, buildPPPPacket(pppConfNak, id, opt))
		}

	case pppConfAck:
		s.ccpLocalAck = true
		s.checkCCPDone()

	case pppConfNak:
		// Server may request stateful instead of stateless
		if len(data) >= 6 && data[0] == 18 {
			bits := binary.BigEndian.Uint32(data[2:6])
			s.mppeStateless = (bits & 0x01000000) != 0
			s.sendCCPConfReq(s.mppeStateless)
		}

	case pppConfRej:
		emitEvent(statusEvent{Event: "log", Message: "Server rejected MPPE; proceeding without encryption."})
		s.mppeEnabled = false
		s.ccpDone = true
		s.sendIPCPConfReq()

	case pppResetReq:
		s.mppeMu.Lock()
		if s.mppeSend != nil {
			s.mppeSend.rekey(false)
		}
		s.mppeMu.Unlock()
		_ = s.sendPPP(pppCCP, buildPPPPacket(pppResetAck, id, nil))
	}
}

func (s *pptpSession) checkCCPDone() {
	if s.ccpLocalAck && s.ccpPeerAck && !s.ccpDone {
		s.ccpDone = true
		master := mppeGetMasterKey(s.pwHashHash, s.ntResp)
		sendStart := mppeGetAsymmetricStartKey(master, true)
		recvStart := mppeGetAsymmetricStartKey(master, false)

		s.mppeMu.Lock()
		s.mppeSend = newMppeState(sendStart, s.mppeStateless)
		s.mppeRecv = newMppeState(recvStart, s.mppeStateless)
		s.mppeEnabled = true
		s.mppeMu.Unlock()

		modeStr := "stateful"
		if s.mppeStateless {
			modeStr = "stateless"
		}
		emitEvent(statusEvent{Event: "log", Message: fmt.Sprintf("MPPE-128 (%s) active. Starting IPCP...", modeStr)})
		s.sendIPCPConfReq()
	}
}

func (s *pptpSession) sendIPCPConfReq() {
	opts := make([]byte, 0, 18)
	// Option 3: IP-Address
	opts = append(opts, 3, 6, s.localIP[0], s.localIP[1], s.localIP[2], s.localIP[3])
	// Option 129: Primary DNS
	opts = append(opts, 129, 6, s.dns1[0], s.dns1[1], s.dns1[2], s.dns1[3])
	// Option 131: Secondary DNS
	opts = append(opts, 131, 6, s.dns2[0], s.dns2[1], s.dns2[2], s.dns2[3])
	_ = s.sendPPP(pppIPCP, buildPPPPacket(pppConfReq, s.nextID(), opts))
}

func (s *pptpSession) handleIPCP(pkt []byte) {
	if len(pkt) < 4 {
		return
	}
	code, id := pkt[0], pkt[1]
	plen := int(binary.BigEndian.Uint16(pkt[2:4]))
	if plen > len(pkt) || plen < 4 {
		return
	}
	data := pkt[4:plen]

	switch code {
	case pppConfReq:
		var rej []byte
		off := 0
		for off+2 <= len(data) {
			ot := data[off]
			ol := int(data[off+1])
			if ol < 2 || off+ol > len(data) {
				break
			}
			opt := data[off : off+ol]
			off += ol
			if ot == 3 && ol == 6 {
				copy(s.peerIP[:], opt[2:6])
			} else {
				// Reject VJ compression (option 2) or other unsupported IPCP options
				rej = append(rej, opt...)
			}
		}
		if len(rej) > 0 {
			_ = s.sendPPP(pppIPCP, buildPPPPacket(pppConfRej, id, rej))
		} else {
			_ = s.sendPPP(pppIPCP, buildPPPPacket(pppConfAck, id, data))
			s.ipcpPeerAck = true
			s.checkIPCPDone()
		}

	case pppConfNak:
		off := 0
		for off+2 <= len(data) {
			ot := data[off]
			ol := int(data[off+1])
			if ol < 2 || off+ol > len(data) {
				break
			}
			opt := data[off : off+ol]
			off += ol
			if ot == 3 && ol == 6 {
				copy(s.localIP[:], opt[2:6])
			} else if ot == 129 && ol == 6 {
				copy(s.dns1[:], opt[2:6])
			} else if ot == 131 && ol == 6 {
				copy(s.dns2[:], opt[2:6])
			}
		}
		s.sendIPCPConfReq()

	case pppConfRej:
		// Server rejected DNS options; request IP-Address only
		opts := []byte{3, 6, s.localIP[0], s.localIP[1], s.localIP[2], s.localIP[3]}
		_ = s.sendPPP(pppIPCP, buildPPPPacket(pppConfReq, s.nextID(), opts))

	case pppConfAck:
		s.ipcpLocalAck = true
		s.checkIPCPDone()
	}
}

func (s *pptpSession) checkIPCPDone() {
	if s.ipcpLocalAck && s.ipcpPeerAck && !s.ipUp {
		s.ipUp = true
		localStr := net.IP(s.localIP[:]).String()
		peerStr := net.IP(s.peerIP[:]).String()
		dns1Str := ""
		if s.dns1 != [4]byte{} {
			dns1Str = net.IP(s.dns1[:]).String()
		}
		dns2Str := ""
		if s.dns2 != [4]byte{} {
			dns2Str = net.IP(s.dns2[:]).String()
		}
		emitEvent(statusEvent{
			Event:   "up",
			IfName:  s.cfg.IfName,
			LocalIP: localStr,
			PeerIP:  peerStr,
			DNS1:    dns1Str,
			DNS2:    dns2Str,
			Message: fmt.Sprintf("PPTP UP: %s (local=%s peer=%s)", s.cfg.IfName, localStr, peerStr),
		})
	}
}

func (s *pptpSession) negotiationRetransmitLoop() {
	t := time.NewTicker(3 * time.Second)
	defer t.Stop()
	attempts := 0
	for {
		select {
		case <-s.stopCh:
			return
		case <-t.C:
			if s.ipUp {
				return
			}
			attempts++
			if attempts > 12 {
				emitEvent(statusEvent{Event: "error", Message: "PPTP negotiation timed out (check GRE/firewall/credentials)"})
				s.shutdown()
				return
			}
			if !s.lcpLocalAck {
				s.sendLCPConfReq()
			} else if s.authDone && !s.ccpDone {
				s.sendCCPConfReq(s.mppeStateless)
			} else if s.authDone && s.ccpDone && !s.ipcpLocalAck {
				s.sendIPCPConfReq()
			}
		}
	}
}

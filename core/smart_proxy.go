package main

import (
	"bufio"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) {
	return b.r.Read(p)
}

// SmartProxy listens as a local SOCKS5 proxy (TCP + UDP Associate) for tun2socks.
// - TCP connections are forwarded to the 2nd VPN's local port (auto-supporting SOCKS5 or HTTP CONNECT).
// - UDP port 53 (DNS) queries are transparently converted to DNS-over-TCP and sent through the 2nd VPN proxy,
//   guaranteeing reliable DNS resolution even when the 2nd VPN proxy is HTTP-only or lacks UDP Associate.
type smartProxyConfig struct {
	ListenAddr   string // e.g. "127.0.0.1:0"
	UpstreamAddr string // e.g. "127.0.0.1:10810"
	UpstreamType string // "auto", "socks5", "http"
	DnsServer    string // e.g. "1.1.1.1:53"
}

func startSmartProxy(cfg smartProxyConfig) (*net.TCPListener, error) {
	addr, err := net.ResolveTCPAddr("tcp4", cfg.ListenAddr)
	if err != nil {
		return nil, err
	}
	ln, err := net.ListenTCP("tcp4", addr)
	if err != nil {
		return nil, err
	}
	go func() {
		for {
			c, err := ln.AcceptTCP()
			if err != nil {
				return
			}
			go handleSocks5Client(c, cfg)
		}
	}()
	return ln, nil
}

func handleSocks5Client(conn *net.TCPConn, cfg smartProxyConfig) {
	defer conn.Close()
	_ = conn.SetNoDelay(true)

	hdr := make([]byte, 2)
	if _, err := io.ReadFull(conn, hdr); err != nil || hdr[0] != 0x05 {
		return
	}
	methods := make([]byte, hdr[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}
	if _, err := conn.Write([]byte{0x05, 0x00}); err != nil {
		return
	}

	req := make([]byte, 4)
	if _, err := io.ReadFull(conn, req); err != nil || req[0] != 0x05 {
		return
	}
	cmd := req[1]
	atyp := req[3]

	host, port, err := readSocks5Addr(conn, atyp)
	if err != nil {
		return
	}

	if cmd == 0x01 { // CONNECT (TCP)
		target := net.JoinHostPort(host, fmt.Sprintf("%d", port))
		upConn, err := dialViaUpstream(cfg, target)
		if err != nil {
			_, _ = conn.Write([]byte{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0})
			return
		}
		defer upConn.Close()
		_, _ = conn.Write([]byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, 0, 0})

		done := make(chan struct{}, 2)
		go func() { _, _ = io.Copy(upConn, conn); done <- struct{}{} }()
		go func() { _, _ = io.Copy(conn, upConn); done <- struct{}{} }()
		<-done
		return
	}

	if cmd == 0x03 { // UDP ASSOCIATE (DNS port 53 -> DNS-over-TCP)
		udpAddr, err := net.ResolveUDPAddr("udp4", "127.0.0.1:0")
		if err != nil {
			return
		}
		udpConn, err := net.ListenUDP("udp4", udpAddr)
		if err != nil {
			return
		}
		defer udpConn.Close()

		laddr := udpConn.LocalAddr().(*net.UDPAddr)
		rep := []byte{0x05, 0x00, 0x00, 0x01, 127, 0, 0, 1, byte(laddr.Port >> 8), byte(laddr.Port & 0xFF)}
		if _, err := conn.Write(rep); err != nil {
			return
		}

		go relaySmartUDP(udpConn, cfg)

		sink := make([]byte, 64)
		for {
			if _, err := conn.Read(sink); err != nil {
				return
			}
		}
	}
}

func readSocks5Addr(r io.Reader, atyp byte) (string, uint16, error) {
	var host string
	switch atyp {
	case 0x01:
		b := make([]byte, 4)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	case 0x03:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(r, lb); err != nil {
			return "", 0, err
		}
		db := make([]byte, lb[0])
		if _, err := io.ReadFull(r, db); err != nil {
			return "", 0, err
		}
		host = string(db)
	case 0x04:
		b := make([]byte, 16)
		if _, err := io.ReadFull(r, b); err != nil {
			return "", 0, err
		}
		host = net.IP(b).String()
	default:
		return "", 0, fmt.Errorf("unsupported atyp %d", atyp)
	}
	pb := make([]byte, 2)
	if _, err := io.ReadFull(r, pb); err != nil {
		return "", 0, err
	}
	return host, binary.BigEndian.Uint16(pb), nil
}

func dialViaUpstream(cfg smartProxyConfig, target string) (net.Conn, error) {
	mode := strings.ToLower(cfg.UpstreamType)
	if mode == "http" {
		return dialHTTPConnect(cfg.UpstreamAddr, target)
	}
	if mode == "socks5" {
		return dialSocks5Connect(cfg.UpstreamAddr, target)
	}
	c, err := dialSocks5Connect(cfg.UpstreamAddr, target)
	if err == nil {
		return c, nil
	}
	return dialHTTPConnect(cfg.UpstreamAddr, target)
}

func dialSocks5Connect(proxyAddr, target string) (net.Conn, error) {
	c, err := net.DialTimeout("tcp4", proxyAddr, 8*time.Second)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))

	if _, err := c.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		_ = c.Close()
		return nil, err
	}
	greet := make([]byte, 2)
	if _, err := io.ReadFull(c, greet); err != nil || greet[0] != 0x05 || greet[1] != 0x00 {
		_ = c.Close()
		return nil, fmt.Errorf("socks5 greeting failed")
	}

	host, portStr, err := net.SplitHostPort(target)
	if err != nil {
		_ = c.Close()
		return nil, err
	}
	var port uint16
	_, _ = fmt.Sscanf(portStr, "%d", &port)

	req := []byte{0x05, 0x01, 0x00}
	if ip := net.ParseIP(host); ip != nil {
		if v4 := ip.To4(); v4 != nil {
			req = append(req, 0x01)
			req = append(req, v4...)
		} else {
			req = append(req, 0x04)
			req = append(req, ip.To16()...)
		}
	} else {
		req = append(req, 0x03, byte(len(host)))
		req = append(req, []byte(host)...)
	}
	req = append(req, byte(port>>8), byte(port&0xFF))

	if _, err := c.Write(req); err != nil {
		_ = c.Close()
		return nil, err
	}
	rep := make([]byte, 4)
	if _, err := io.ReadFull(c, rep); err != nil || rep[1] != 0x00 {
		_ = c.Close()
		return nil, fmt.Errorf("socks5 connect rejected")
	}
	if _, _, err := readSocks5Addr(c, rep[3]); err != nil {
		_ = c.Close()
		return nil, err
	}
	_ = c.SetDeadline(time.Time{})
	return c, nil
}

func dialHTTPConnect(proxyAddr, target string) (net.Conn, error) {
	c, err := net.DialTimeout("tcp4", proxyAddr, 8*time.Second)
	if err != nil {
		return nil, err
	}
	_ = c.SetDeadline(time.Now().Add(10 * time.Second))
	req := fmt.Sprintf("CONNECT %s HTTP/1.1\r\nHost: %s\r\nProxy-Connection: Keep-Alive\r\n\r\n", target, target)
	if _, err := c.Write([]byte(req)); err != nil {
		_ = c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	statusLine, err := br.ReadString('\n')
	if err != nil || !strings.Contains(statusLine, " 200") {
		_ = c.Close()
		return nil, fmt.Errorf("http connect failed: %s", strings.TrimSpace(statusLine))
	}
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			_ = c.Close()
			return nil, err
		}
		if line == "\r\n" || line == "\n" {
			break
		}
	}
	_ = c.SetDeadline(time.Time{})
	return &bufferedConn{Conn: c, r: br}, nil
}

func relaySmartUDP(udpConn *net.UDPConn, cfg smartProxyConfig) {
	buf := make([]byte, 4096)
	for {
		n, clientAddr, err := udpConn.ReadFromUDP(buf)
		if err != nil {
			return
		}
		if n < 10 || buf[0] != 0 || buf[1] != 0 || buf[2] != 0 {
			continue
		}
		pkt := make([]byte, n)
		copy(pkt, buf[:n])

		go func(raw []byte, src *net.UDPAddr) {
			atyp := raw[3]
			off := 4
			switch atyp {
			case 0x01:
				off += 4
			case 0x03:
				if off >= len(raw) {
					return
				}
				off += 1 + int(raw[off])
			case 0x04:
				off += 16
			default:
				return
			}
			if off+2 > len(raw) {
				return
			}
			dstPort := binary.BigEndian.Uint16(raw[off : off+2])
			hdrLen := off + 2
			payload := raw[hdrLen:]

			if dstPort == 53 && len(payload) >= 12 {
				dnsResp, err := queryDnsOverTcpViaProxy(cfg, payload)
				if err != nil || len(dnsResp) == 0 {
					return
				}
				reply := make([]byte, hdrLen+len(dnsResp))
				copy(reply[:hdrLen], raw[:hdrLen])
				copy(reply[hdrLen:], dnsResp)
				_, _ = udpConn.WriteToUDP(reply, src)
			}
		}(pkt, clientAddr)
	}
}

func queryDnsOverTcpViaProxy(cfg smartProxyConfig, query []byte) ([]byte, error) {
	dnsTarget := cfg.DnsServer
	if dnsTarget == "" {
		dnsTarget = "1.1.1.1:53"
	}
	c, err := dialViaUpstream(cfg, dnsTarget)
	if err != nil {
		return nil, err
	}
	defer c.Close()
	_ = c.SetDeadline(time.Now().Add(6 * time.Second))

	frame := make([]byte, 2+len(query))
	binary.BigEndian.PutUint16(frame[0:2], uint16(len(query)))
	copy(frame[2:], query)
	if _, err := c.Write(frame); err != nil {
		return nil, err
	}
	lenBuf := make([]byte, 2)
	if _, err := io.ReadFull(c, lenBuf); err != nil {
		return nil, err
	}
	respLen := binary.BigEndian.Uint16(lenBuf)
	if respLen == 0 || respLen > 4096 {
		return nil, fmt.Errorf("invalid dns resp length")
	}
	resp := make([]byte, respLen)
	if _, err := io.ReadFull(c, resp); err != nil {
		return nil, err
	}
	return resp, nil
}

package main

import (
	"fmt"
	"net"
	"os"
	"os/exec"
	"os/signal"
	"syscall"
)

func runFdBridge(sockPath, tun2socksBin, upstreamAddr, upstreamType, dnsServer string, mtu int) int {
	_ = os.Remove(sockPath)
	unixAddr, err := net.ResolveUnixAddr("unix", sockPath)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "ResolveUnixAddr failed: " + err.Error()})
		return 1
	}
	ln, err := net.ListenUnix("unix", unixAddr)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "ListenUnix failed: " + err.Error()})
		return 1
	}
	defer func() {
		_ = ln.Close()
		_ = os.Remove(sockPath)
	}()

	spCfg := smartProxyConfig{
		ListenAddr:   "127.0.0.1:0",
		UpstreamAddr: upstreamAddr,
		UpstreamType: upstreamType,
		DnsServer:    dnsServer,
	}
	spLn, err := startSmartProxy(spCfg)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "SmartProxy start failed: " + err.Error()})
		return 1
	}
	defer spLn.Close()
	localSocksAddr := spLn.Addr().String()

	emitEvent(statusEvent{Event: "ready", Message: "FD bridge listening on " + sockPath})

	conn, err := ln.AcceptUnix()
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "AcceptUnix failed: " + err.Error()})
		return 1
	}
	defer conn.Close()

	buf := make([]byte, 16)
	oob := make([]byte, 128)
	_, oobn, _, _, err := conn.ReadMsgUnix(buf, oob)
	if err != nil {
		emitEvent(statusEvent{Event: "error", Message: "ReadMsgUnix failed: " + err.Error()})
		return 1
	}
	msgs, err := syscall.ParseSocketControlMessage(oob[:oobn])
	if err != nil || len(msgs) == 0 {
		emitEvent(statusEvent{Event: "error", Message: "No SCM_RIGHTS received"})
		return 1
	}
	fds, err := syscall.ParseUnixRights(&msgs[0])
	if err != nil || len(fds) == 0 {
		emitEvent(statusEvent{Event: "error", Message: "ParseUnixRights returned 0 FDs"})
		return 1
	}

	tunFile := os.NewFile(uintptr(fds[0]), "vpn-tun")
	defer tunFile.Close()

	if mtu < 1200 || mtu > 1500 {
		mtu = 1280
	}

	args := []string{
		"-device", "fd://3",
		"-proxy", "socks5://" + localSocksAddr,
		"-mtu", fmt.Sprintf("%d", mtu),
		"-loglevel", "warning",
	}

	cmd := exec.Command(tun2socksBin, args...)
	cmd.ExtraFiles = []*os.File{tunFile}
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		emitEvent(statusEvent{Event: "error", Message: "Failed to start tun2socks: " + err.Error()})
		return 1
	}

	emitEvent(statusEvent{
		Event:   "tun_up",
		Message: fmt.Sprintf("tun2socks running (local=%s -> upstream=%s [%s])", localSocksAddr, upstreamAddr, upstreamType),
	})

	sigCh := make(chan os.Signal, 2)
	signal.Notify(sigCh, syscall.SIGINT, syscall.SIGTERM)
	go func() {
		<-sigCh
		if cmd.Process != nil {
			_ = cmd.Process.Kill()
		}
	}()

	_ = cmd.Wait()
	emitEvent(statusEvent{Event: "tun_down", Message: "tun2socks exited"})
	return 0
}

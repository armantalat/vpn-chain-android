package main

import (
	"flag"
	"fmt"
	"os"
)

func main() {
	if len(os.Args) < 2 {
		fmt.Fprintln(os.Stderr, "Usage: chaincore <pptp|fd-bridge> [flags]")
		os.Exit(2)
	}

	sub := os.Args[1]
	switch sub {
	case "pptp":
		fs := flag.NewFlagSet("pptp", flag.ExitOnError)
		server := fs.String("server", "", "PPTP server IP or domain")
		user := fs.String("user", "", "Username")
		pass := fs.String("pass", "", "Password")
		auth := fs.String("auth", "mschapv2", "Auth mode: mschapv2, auto, pap, chap")
		mppe := fs.Bool("mppe", true, "Require MPPE-128 encryption")
		stateless := fs.Bool("stateless", true, "Prefer stateless MPPE")
		ifname := fs.String("ifname", "pptp0", "TUN interface name")
		mtu := fs.Int("mtu", 1380, "Interface MTU")
		_ = fs.Parse(os.Args[2:])

		if *server == "" || *user == "" {
			emitEvent(statusEvent{Event: "error", Message: "PPTP requires -server and -user"})
			os.Exit(1)
		}
		os.Exit(runPPTP(pptpConfig{
			Server:      *server,
			Username:    *user,
			Password:    *pass,
			AuthMode:    *auth,
			RequireMppe: *mppe,
			Stateless:   *stateless,
			IfName:      *ifname,
			MTU:         *mtu,
		}))

	case "fd-bridge":
		fs := flag.NewFlagSet("fd-bridge", flag.ExitOnError)
		sock := fs.String("sock", "", "Unix socket path for TUN FD transfer")
		bin := fs.String("tun2socks", "", "Path to libtun2socks.so")
		upstream := fs.String("upstream", "127.0.0.1:10810", "2nd VPN local proxy address")
		upType := fs.String("type", "auto", "Upstream proxy type: auto, socks5, http")
		dns := fs.String("dns", "1.1.1.1:53", "DNS server for DNS-over-TCP")
		mtu := fs.Int("mtu", 1280, "TUN MTU")
		_ = fs.Parse(os.Args[2:])

		if *sock == "" || *bin == "" {
			emitEvent(statusEvent{Event: "error", Message: "fd-bridge requires -sock and -tun2socks"})
			os.Exit(1)
		}
		os.Exit(runFdBridge(*sock, *bin, *upstream, *upType, *dns, *mtu))

	default:
		fmt.Fprintf(os.Stderr, "Unknown subcommand: %s\n", sub)
		os.Exit(2)
	}
}

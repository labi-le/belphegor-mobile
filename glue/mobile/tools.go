//go:build tools

package mobile

// Pin golang.org/x/mobile in go.mod. `gomobile bind` generates a wrapper that
// imports golang.org/x/mobile/bind, so the module must require it. This file is
// never compiled (build tag "tools"); it exists only so `go mod tidy` keeps the
// dependency instead of dropping it as unused.
import _ "golang.org/x/mobile/bind"

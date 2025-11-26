{
  description = "dmx-dita: dev shell with Repomix codebase blaster";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-24.05";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = import nixpkgs { inherit system; };

        repomix-md = pkgs.writeShellApplication {
          name = "repomix-md";
          runtimeInputs = [
            pkgs.nodejs_20
            pkgs.git
            pkgs.jq
            pkgs.coreutils
            pkgs.findutils
            pkgs.gnugrep
            pkgs.gawk
          ];
          text = ''
            set -euo pipefail

            # Usage: repomix-md [output.md]
            OUT="''${1:-repomix-output.md}"

            # Ensure we run at repo root if called from a subdir
            if git rev-parse --show-toplevel >/dev/null 2>&1; then
              cd "$(git rev-parse --show-toplevel)"
            fi

            # Prefer npx if present; otherwise use npm exec
            if command -v npx >/dev/null 2>&1; then
              RUNNER=(npx -y repomix@latest)
            else
              RUNNER=(npm exec -y -- repomix@latest)
            fi

            # If a config exists, let it drive the settings; else pass flags.
            if [ -f repomix.config.json ] || [ -f repomix.config.jsonc ] || [ -f repomix.config.json5 ] \
               || [ -f repomix.config.ts ] || [ -f repomix.config.js ] ; then
              "''${RUNNER[@]}"
            else
              "''${RUNNER[@]}" -o "$OUT" --style markdown
            fi

            # If config wrote a default file name, move it to OUT for convenience.
            if [ -f repomix-output.md ] && [ "$OUT" != "repomix-output.md" ]; then
              mv -f repomix-output.md "$OUT"
            fi

            echo "✅ Repomix wrote: $OUT"
          '';
        };

        repomix-remote = pkgs.writeShellApplication {
          name = "repomix-remote";
          runtimeInputs = [ pkgs.nodejs_20 pkgs.git pkgs.coreutils ];
          text = ''
            set -euo pipefail
            if [ "''$#" -lt 1 ]; then
              echo "Usage: repomix-remote <git-url> [output.md]" >&2
              exit 2
            fi
            URL="''$1"; shift
            OUT="''${1:-repomix-output.md}"

            if command -v npx >/dev/null 2>&1; then
              npx -y repomix@latest --remote "''$URL" -o "''$OUT" --style markdown
            else
              npm exec -y -- repomix@latest --remote "''$URL" -o "''$OUT" --style markdown
            fi
            echo "✅ Remote packed to: ''$OUT"
          '';
        };
      in {
        packages.default = repomix-md;

        apps.repomix-md = {
          type = "app";
          program = "${repomix-md}/bin/repomix-md";
        };
        apps.repomix-remote = {
          type = "app";
          program = "${repomix-remote}/bin/repomix-remote";
        };

        devShells.default = pkgs.mkShell {
          packages = [
            pkgs.nodejs_20
            pkgs.git
            pkgs.jq
            repomix-md
            repomix-remote
          ];
          shellHook = ''
            echo
            echo "Repomix ready:"
            echo "  • repomix-md [output.md]       # pack current repo (Markdown)"
            echo "  • repomix-remote <url> [out]   # pack a remote repo"
            echo
          '';
        };
      });
}

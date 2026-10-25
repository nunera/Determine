{
  description = "Determine: parking sensors for people (firmware, phone engine, apps, simulator)";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/eaad089433ca2bb662274377d33df3d0e51ef28b";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        # The Android SDK and emulator are unfree and need the SDK licence accepted (android-demo only).
        config = { allowUnfree = true; android_sdk.accept_license = true; };
      };
      lib = pkgs.lib;

      # Compose Desktop (Skiko) loads these at runtime; NixOS has no global /usr/lib.
      desktopLibs = with pkgs; [ libGL libx11 libxext libxrender libxi libxtst libxrandr fontconfig freetype alsa-lib ];

      # Build SDK: the platforms and build-tools the app compiles against; no emulator.
      buildSdk = (pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "34" "35" ];
        buildToolsVersions = [ "35.0.0" "34.0.0" ];
      }).androidsdk;
      # Emulator SDK: one Android 34 x86_64 image. A separate SDK because system images are pulled for every
      # platform version listed, and a second image is a 1.6 GB download nobody needs.
      emuSdk = (pkgs.androidenv.composeAndroidPackages {
        platformVersions = [ "34" ];
        includeEmulator = true;
        includeSystemImages = true;
        systemImageTypes = [ "google_apis" ];
        abiVersions = [ "x86_64" ];
      }).androidsdk;
      sdkRoot = "${buildSdk}/libexec/android-sdk";
      emuRoot = "${emuSdk}/libexec/android-sdk";

      # Run from anywhere inside a checkout: Gradle builds in the working tree, not in the read-only store.
      findRepo = ''
        repo=$(git rev-parse --show-toplevel 2>/dev/null || true)
        if [ -z "$repo" ] || [ ! -f "$repo/tools/sim/sim_boards.py" ]; then
          echo "run this from inside a Determine checkout (git clone https://github.com/nunera/Determine)" >&2
          exit 1
        fi
      '';

      desktop = pkgs.writeShellApplication {
        name = "determine-desktop";
        runtimeInputs = with pkgs; [ jdk17 python3 espeak-ng git ];
        text = ''
          ${findRepo}
          export LD_LIBRARY_PATH="${lib.makeLibraryPath desktopLibs}''${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
          export JAVA_HOME="${pkgs.jdk17.home}"
          cd "$repo"
          # --repo: the app finds tools/sim from here when --sim is given.
          exec "$repo/desktop/gradlew" -p "$repo/desktop" --console=plain -q run --args="--repo $repo $*"
        '';
      };

      androidDemo = pkgs.writeShellApplication {
        name = "determine-android-demo";
        runtimeInputs = with pkgs; [ jdk17 python3 git coreutils ];
        text = ''
          ${findRepo}
          usage="determine-android-demo [--sim SCENARIO] [--no-build] [--headless]
  Boots an Android emulator, installs the real app (android/), starts it and, with --sim, runs both
  simulated boards against it (looping SCENARIO; list: python3 tools/sim/sim_boards.py --list).
  --headless runs the emulator without a window (control it with adb). Needs KVM (/dev/kvm).
  Ctrl-C, or closing the emulator window, stops the simulator and the emulator."
          sim=""; build=1; window=()
          while [ $# -gt 0 ]; do
            case "$1" in
              --sim) sim="''${2:?--sim needs a scenario}"; shift 2 ;;
              --no-build) build=0; shift ;;
              --headless) window=(-no-window -no-audio); shift ;;
              -h|--help) echo "$usage"; exit 0 ;;
              *) echo "unknown option $1" >&2; echo "$usage" >&2; exit 2 ;;
            esac
          done
          [ -w /dev/kvm ] || { echo "no writable /dev/kvm: the emulator needs hardware virtualisation" >&2; exit 1; }

          export ANDROID_HOME="${sdkRoot}" ANDROID_SDK_ROOT="${sdkRoot}" JAVA_HOME="${pkgs.jdk17.home}"
          state="''${XDG_STATE_HOME:-$HOME/.local/state}/determine"
          export ANDROID_AVD_HOME="$state/avd" ANDROID_USER_HOME="$state/android"
          mkdir -p "$ANDROID_AVD_HOME" "$ANDROID_USER_HOME"
          adb="${emuRoot}/platform-tools/adb"
          apk="$repo/android/app/build/outputs/apk/debug/app-debug.apk"

          if [ "$build" = 1 ]; then
            if [ -f "$repo/android/local.properties" ] && grep -q '^sdk.dir' "$repo/android/local.properties"; then
              echo "note: android/local.properties sets sdk.dir, which Gradle uses instead of the nix SDK" >&2
            fi
            # NixOS cannot run the aapt2 that AGP downloads; use the SDK's own.
            "$repo/android/gradlew" -p "$repo/android" --console=plain -q assembleDebug \
              -Pandroid.aapt2FromMavenOverride="${sdkRoot}/build-tools/35.0.0/aapt2"
          fi
          [ -f "$apk" ] || { echo "no APK at $apk (drop --no-build)" >&2; exit 1; }

          if [ ! -d "$ANDROID_AVD_HOME/determine.avd" ]; then
            avdmanager=$(find "${emuRoot}/cmdline-tools" -path '*/bin/avdmanager' | head -1)
            echo no | ANDROID_HOME="${emuRoot}" ANDROID_SDK_ROOT="${emuRoot}" "$avdmanager" create avd -n determine \
              -k "system-images;android-34;google_apis;x86_64" --force >/dev/null
            printf 'hw.lcd.width=1080\nhw.lcd.height=2400\nhw.lcd.density=420\nhw.keyboard=yes\n' \
              >> "$ANDROID_AVD_HOME/determine.avd/config.ini"
          fi

          "$adb" start-server >/dev/null
          # The emulator reads ANDROID_HOME before ANDROID_SDK_ROOT: both must name the SDK with the system image.
          ANDROID_HOME="${emuRoot}" ANDROID_SDK_ROOT="${emuRoot}" "${emuRoot}/emulator/emulator" -avd determine -no-snapshot-save -no-boot-anim -gpu swiftshader_indirect "''${window[@]}" \
            >"$state/emulator.log" 2>&1 &
          emu=$!
          simpid=""
          cleanup() {
            [ -n "$simpid" ] && kill "$simpid" 2>/dev/null || true
            "$adb" emu kill >/dev/null 2>&1 || kill "$emu" 2>/dev/null || true
          }
          trap cleanup EXIT INT TERM

          echo "booting emulator (log: $state/emulator.log)…"
          # Fail fast if the emulator exits (bad AVD, no KVM) instead of waiting for a device that never comes.
          until [ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; do
            if ! kill -0 "$emu" 2>/dev/null; then
              echo "emulator exited during boot; last lines of $state/emulator.log:" >&2
              tail -5 "$state/emulator.log" >&2
              exit 1
            fi
            sleep 1
          done
          # The boards reach the app's UDP :47000 through the host's 127.0.0.1:47000.
          "$adb" emu redir add udp:47000:47000 >/dev/null
          "$adb" install -r -g "$apk" >/dev/null
          "$adb" shell am start -n org.determine.android/.ui.MainActivity >/dev/null
          echo "app started. Press Start Determine in the emulator."

          if [ -n "$sim" ]; then
            python3 "$repo/tools/sim/sim_boards.py" --phone 127.0.0.1 --scenario "$sim" --loop &
            simpid=$!
          fi
          wait "$emu"
        '';
      };
    in {
      packages.${system} = { inherit desktop androidDemo; default = desktop; };

      apps.${system} = {
        desktop = { type = "app"; program = "${desktop}/bin/determine-desktop"; };
        android-demo = { type = "app"; program = "${androidDemo}/bin/determine-android-demo"; };
        default = self.apps.${system}.desktop;
      };

      devShells.${system}.default = pkgs.mkShell {
        packages = with pkgs; [ jdk17 python3 espeak-ng platformio git ];
        LD_LIBRARY_PATH = lib.makeLibraryPath desktopLibs;
        shellHook = ''
          echo "Determine dev shell: desktop/gradlew run, phone/gradlew build, python3 tools/sim/e2e.py"
          echo "  nix run .#desktop -- --sim approach_centre --start     (desktop test app + simulated boards)"
          echo "  nix run .#android-demo -- --sim approach_centre        (real APK in an emulator)"
        '';
      };
    };
}

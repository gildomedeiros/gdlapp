# Legacy GoPro Wi-Fi Remote laptop probe

Experimental Python 3 camera emulator for the original Wi-Fi Remote supplied with
the Wi-Fi Combo Kit. Uses only the Python standard library. No drone, VT or LoRa
connection. Actual remote pairing has not yet been tested.

## Run

1. Charge the remote. Put it into camera-search/pairing mode: the original
   research describes holding the top button while pressing the bottom button.
   Look for the camera/arrows icon.
2. Open Windows Wi-Fi settings and look for a `HERO-RC-...` network. Connect the
   laptop to that network (it has no internet). Do not create a laptop hotspot:
   in this generation the remote is the access point and the camera is a client.
   If it asks for a password, or the network never appears, record that detail;
   do not assume a camera-app Wi-Fi password applies.
3. From the VT Tools folder, run:

   ```powershell
   python tools/gopro_wifi_probe.py
   ```

   Bundled Python, if `python` is not available:

   ```powershell
   & 'C:/Users/gildo/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe' 'C:/Users/gildo/gdlapp/VT Tools/tools/gopro_wifi_probe.py'
   ```

4. If Windows Firewall asks, allow Python on the remote's network for this test.
   Do not disable the firewall globally. The listener needs inbound UDP 8484.
5. Press shutter once, wait two seconds, press it again. Also try mode. The
   terminal should report `SHUTTER_START` / `SHUTTER_STOP` if negotiation succeeds.
6. Ctrl+C stops. The adjacent `gopro-capture.jsonl` records raw received bytes,
   replies, sequence IDs, decoded commands and unsupported messages. Reconnect
   normal Wi-Fi afterward.

If Windows associates but has no IPv4 address in the remote's subnet, capture
`ipconfig` output for diagnosis. Do not change adapter addresses blindly: the
research uses 10.71.79.0/24, but this kit's DHCP/association must be verified.
No packets does not prove the remote cannot be used; Wi-Fi negotiation can fail
before Python sees UDP. Firmware, discovery or further state responses may
require a packet capture with a real camera/BacPac for comparison.

Options: `--listen-only` disables replies; `--bind <local-ip>` restricts the
interface; `--log <path>` selects an append-only capture file. Normal mode sends
experimental camera responses and may change what the remote screen displays.
Duplicate packets within five seconds receive the cached response without a
second shutter event. These are camera start/stop commands, not guaranteed raw
physical press/release events.

## Local validation

```powershell
python tools/gopro_wifi_probe.py --self-test
```

Exercises loopback UDP reception/reply, shutter decoding, retransmissions and
invalid/unknown packets. This validates the probe, not compatibility with the
physical remote. The LCD response is blank; settings/status are minimal.

## Protocol evidence

- Original camera-side emulator and negotiation observations:
  https://github.com/theContentMint/GoProRemote
- Packet layout, UDP 8484 and remote access-point behavior:
  https://github.com/cepoon/GoProRemote

This probe independently implements the observed wire format and minimal
responses; neither reference establishes compatibility with this exact remote.

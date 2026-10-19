#!/usr/bin/env bash
set -euo pipefail

publish=$(cd "$1" && pwd)
version=$2
output=$3
root=$(cd "$(dirname "$0")/.." && pwd)
[[ "$version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || { echo 'Invalid version' >&2; exit 1; }
test -x "$publish/CardVault"
test ! -f "$publish/CardVault.dll"
for library in libSkiaSharp.dylib libHarfBuzzSharp.dylib libAvaloniaNative.dylib libe_sqlite3.dylib; do
  test -s "$publish/$library" || { echo "Required native library is missing: $library" >&2; exit 1; }
done
test ! -e "$output" || { echo 'Package already exists; refusing to overwrite it' >&2; exit 1; }
mkdir -p "$(dirname "$output")"
stage=$(mktemp -d)
trap 'rm -rf "$stage"' EXIT
app="$stage/CardVault.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
# Third-party native libraries may include symbols independently of .NET's DebugSymbols setting.
rsync -a --exclude='*.pdb' --exclude='*.dbg' --exclude='*.dwarf' --exclude='*.dSYM' \
  "$publish/" "$app/Contents/MacOS/"
cp "$root/src/CardVault/Assets/cardvault.icns" "$app/Contents/Resources/cardvault.icns"
cp "$root/LICENSE" "$stage/LICENSE.txt"
sed "s/@VERSION@/$version/g" "$root/packaging/macos/Info.plist" > "$app/Contents/Info.plist"
plutil -lint "$app/Contents/Info.plist"
chmod +x "$app/Contents/MacOS/CardVault"
# Ad-hoc signing keeps the bundle internally consistent; this is not Developer ID signing or notarization.
while IFS= read -r -d '' library; do
  codesign --force --sign - "$library"
done < <(find "$app/Contents/MacOS" -type f -name '*.dylib' -print0)
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
ln -s /Applications "$stage/Applications"
hdiutil create -volname "CardVault $version" -srcfolder "$stage" -format UDZO -ov "$output"
hdiutil verify "$output"

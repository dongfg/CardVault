#!/usr/bin/env bash
# Requires Bash and OpenSSL 3+. Also runs in Git Bash on Windows.
set -euo pipefail
umask 077

usage() {
  cat <<'HELP'
Generate a personal root CA and an empty-password Windows code-signing PFX.

Usage:
  bash scripts/create-signing-certificates.sh [options]

Options:
  --ca-name NAME       Root CA display name (default: Personal Code Signing Root CA)
  --publisher NAME     Publisher / signing certificate name (default: Personal Developer)
  --organization NAME Optional organization name; omit for personal use
  --country CC        Optional two-letter country code, e.g. CN
  --output DIRECTORY  New output directory (default: signing-material)
  --ca-days DAYS      Root validity in days (default: 3650)
  --signing-days DAYS  Signing certificate validity in days (default: 1095)
  -h, --help          Show this help

Existing output directories are never overwritten. No certificate store is changed.
HELP
}

ca_name='Personal Code Signing Root CA'
publisher='Personal Developer'
organization=''
country=''
output='signing-material'
ca_days=3650
signing_days=1095
while (($#)); do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --ca-name|--publisher|--organization|--country|--output|--ca-days|--signing-days)
      (($# >= 2)) || { echo "Missing value for $1" >&2; exit 1; }
      case "$1" in
        --ca-name) ca_name=$2 ;; --publisher) publisher=$2 ;;
        --organization) organization=$2 ;; --country) country=$2 ;;
        --output) output=$2 ;; --ca-days) ca_days=$2 ;; --signing-days) signing_days=$2 ;;
      esac
      shift 2 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
done

command -v openssl >/dev/null || { echo 'OpenSSL 3+ was not found' >&2; exit 1; }
[[ $(openssl version) =~ ^OpenSSL\ ([3-9]|[1-9][0-9]+)\. ]] || { echo 'OpenSSL 3+ is required' >&2; exit 1; }
[[ -n "$ca_name" && -n "$publisher" && -n "$output" ]] || { echo 'Names and output path must not be empty' >&2; exit 1; }
[[ -z "$country" || "$country" =~ ^[A-Z]{2}$ ]] || { echo 'Country must be two uppercase letters, e.g. CN' >&2; exit 1; }
[[ "$ca_days" =~ ^[1-9][0-9]{0,4}$ && "$signing_days" =~ ^[1-9][0-9]{0,4}$ ]] || { echo 'Validity must be 1-99999 days' >&2; exit 1; }
((signing_days <= ca_days)) || { echo 'Signing validity must not exceed CA validity' >&2; exit 1; }
for value in "$ca_name" "$publisher" "$organization" "$country"; do
  [[ "$value" != *$'\n'* && "$value" != *$'\r'* ]] || { echo 'Certificate fields must not contain newlines' >&2; exit 1; }
done
[[ ! -e "$output" && ! -L "$output" ]] || { echo 'Output already exists; refusing to replace the CA or keys' >&2; exit 1; }
mkdir -p -- "$(dirname -- "$output")"
mkdir -- "$output"
output=$(cd -- "$output" && pwd)

# Quote DN values for OpenSSL config files, including literal dollar signs.
config_value() {
  local value=$1
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//\$/\\\$}
  printf '"%s"' "$value"
}
write_request_config() {
  local name=$1
  cat <<'CONFIG'
[req]
prompt = no
utf8 = yes
string_mask = utf8only
distinguished_name = identity
[identity]
CONFIG
  printf 'CN = %s\n' "$(config_value "$name")"
  if [[ -n "$organization" ]]; then printf 'O = %s\n' "$(config_value "$organization")"; fi
  if [[ -n "$country" ]]; then printf 'C = %s\n' "$country"; fi
}

write_request_config "$ca_name" > "$output/root-ca.cnf"
cat >> "$output/root-ca.cnf" <<'CONFIG'
[root_ca]
basicConstraints = critical, CA:true, pathlen:0
keyUsage = critical, keyCertSign, cRLSign
extendedKeyUsage = codeSigning
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always
CONFIG

write_request_config "$publisher" > "$output/codesign.cnf"
cat >> "$output/codesign.cnf" <<'CONFIG'
[code_signing]
basicConstraints = critical, CA:false
keyUsage = critical, digitalSignature
extendedKeyUsage = codeSigning
subjectKeyIdentifier = hash
authorityKeyIdentifier = keyid:always, issuer
CONFIG

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out "$output/root-ca.key.pem"
openssl req -new -x509 -sha256 -key "$output/root-ca.key.pem" \
  -config "$output/root-ca.cnf" -extensions root_ca -days "$ca_days" \
  -set_serial "0x$(openssl rand -hex 16)" -out "$output/root-ca.pem"
openssl x509 -in "$output/root-ca.pem" -outform DER -out "$output/root-ca.cer"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:3072 -out "$output/codesign.key.pem"
openssl req -new -sha256 -key "$output/codesign.key.pem" -config "$output/codesign.cnf" -out "$output/codesign.csr.pem"
openssl x509 -req -sha256 -in "$output/codesign.csr.pem" \
  -CA "$output/root-ca.pem" -CAkey "$output/root-ca.key.pem" \
  -set_serial "0x$(openssl rand -hex 16)" -days "$signing_days" \
  -extfile "$output/codesign.cnf" -extensions code_signing -out "$output/codesign.pem"
openssl x509 -in "$output/codesign.pem" -outform DER -out "$output/codesign.cer"
openssl pkcs12 -export -inkey "$output/codesign.key.pem" -in "$output/codesign.pem" \
  -certfile "$output/root-ca.pem" -name 'Personal Code Signing' -passout pass: -out "$output/codesign.pfx"

openssl verify -x509_strict -CAfile "$output/root-ca.pem" "$output/root-ca.pem" "$output/codesign.pem"
openssl pkcs12 -in "$output/codesign.pfx" -passin pass: -noout
{
  printf 'ROOT CA\n'
  openssl x509 -in "$output/root-ca.pem" -noout -subject -issuer -dates -fingerprint -sha256 -nameopt utf8
  printf '\nCODE SIGNING CERTIFICATE\n'
  openssl x509 -in "$output/codesign.pem" -noout -subject -issuer -dates -fingerprint -sha256 -nameopt utf8
} > "$output/certificate-info.txt"

printf '\nCreated certificates in: %s\n' "$output"
printf 'root-ca.cer     Install and trust on your Windows devices.\n'
printf 'codesign.pfx    Signing certificate + private key + root chain; password is empty.\n'
printf 'root-ca.key.pem Keep on your server to issue future signing certificates.\n'
printf 'certificate-info.txt contains identities, validity dates and SHA-256 fingerprints.\n'

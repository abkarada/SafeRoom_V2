#!/bin/bash

# SafeRoom Certificate Regeneration Script
# Bu script mevcut sertifikaları yedekler ve yenilerini oluşturur

set -e

echo "========================================="
echo "SafeRoom Certificate Regeneration"
echo "========================================="
echo ""

CERT_DIR="certs"
BACKUP_DIR="certs/backup-$(date +%Y%m%d-%H%M%S)"

# Eski sertifikaları yedekle
if [ -f "$CERT_DIR/server.key" ] || [ -f "$CERT_DIR/server.crt" ]; then
    echo "📦 Eski sertifikalar yedekleniyor..."
    mkdir -p "$BACKUP_DIR"
    [ -f "$CERT_DIR/server.key" ] && mv "$CERT_DIR/server.key" "$BACKUP_DIR/"
    [ -f "$CERT_DIR/server.crt" ] && mv "$CERT_DIR/server.crt" "$BACKUP_DIR/"
    [ -f "$CERT_DIR/server.csr" ] && mv "$CERT_DIR/server.csr" "$BACKUP_DIR/"
    echo "✓ Yedekleme tamamlandı: $BACKUP_DIR"
    echo ""
fi

cd "$CERT_DIR"

echo "🔑 1. Private key oluşturuluyor..."
openssl genrsa -out server.key 4096
chmod 600 server.key
echo "✓ Private key oluşturuldu (4096-bit)"
echo ""

echo "📝 2. Certificate Signing Request (CSR) oluşturuluyor..."
openssl req -new -key server.key -out server.csr -config san.cnf
echo "✓ CSR oluşturuldu"
echo ""

echo "🔐 3. Self-signed certificate oluşturuluyor..."
openssl x509 -req -days 365 -in server.csr -signkey server.key -out server.crt -extensions v3_req -extfile san.cnf
chmod 644 server.crt
echo "✓ Certificate oluşturuldu (1 yıl geçerli)"
echo ""

cd ..

echo "========================================="
echo "✓ Sertifikalar başarıyla oluşturuldu!"
echo "========================================="
echo ""
echo "Dosyalar:"
ls -lh certs/*.{key,crt,csr} 2>/dev/null || true
echo ""
echo "📋 Sertifika detayları:"
openssl x509 -in certs/server.crt -noout -text | grep -A2 "Subject:"
openssl x509 -in certs/server.crt -noout -text | grep -A5 "Subject Alternative Name"
echo ""
echo "⚠️  ÖNEMLİ:"
echo "  1. server.key'i GİT'e GÖNDERME! (.gitignore'da)"
echo "  2. server.key'i server'a SSH ile yükle:"
echo "     scp certs/server.key user@35.198.64.68:/path/to/certs/"
echo "  3. server.crt'yi GitHub'a ekleyebilirsin (public)"

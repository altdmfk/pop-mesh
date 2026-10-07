$ErrorActionPreference = "Stop"

$PASSWORD = "changeit"
$OUT_DIR = "src/main/resources/certs"
New-Item -ItemType Directory -Force -Path $OUT_DIR | Out-Null

# 1. Generate Root CA
openssl req -x509 -newkey rsa:4096 -days 3650 -nodes -keyout "$OUT_DIR/ca.key" -out "$OUT_DIR/ca.crt" -subj "/CN=PoP-Root-CA"

# 2. Generate Gateway Server Certificate
openssl req -newkey rsa:2048 -nodes -keyout "$OUT_DIR/server.key" -out "$OUT_DIR/server.csr" -subj "/CN=localhost"

@"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, nonRepudiation, keyEncipherment, dataEncipherment
subjectAltName = @alt_names
[alt_names]
DNS.1 = localhost
IP.1 = 127.0.0.1
"@ | Out-File -FilePath "$OUT_DIR/server_ext.cnf" -Encoding ascii

openssl x509 -req -in "$OUT_DIR/server.csr" -CA "$OUT_DIR/ca.crt" -CAkey "$OUT_DIR/ca.key" -CAcreateserial -out "$OUT_DIR/server.crt" -days 825 -extfile "$OUT_DIR/server_ext.cnf"

# 3. Generate Client Certificate with SPIFFE URI
openssl req -newkey rsa:2048 -nodes -keyout "$OUT_DIR/client.key" -out "$OUT_DIR/client.csr" -subj "/CN=k6-client"

@"
authorityKeyIdentifier=keyid,issuer
basicConstraints=CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
subjectAltName = URI:spiffe://cluster.local/ns/default/sa/k6-client
"@ | Out-File -FilePath "$OUT_DIR/client_ext.cnf" -Encoding ascii

openssl x509 -req -in "$OUT_DIR/client.csr" -CA "$OUT_DIR/ca.crt" -CAkey "$OUT_DIR/ca.key" -CAcreateserial -out "$OUT_DIR/client.crt" -days 825 -extfile "$OUT_DIR/client_ext.cnf"

# 4. Packaging into PKCS12
# Gateway Keystore
openssl pkcs12 -export -in "$OUT_DIR/server.crt" -inkey "$OUT_DIR/server.key" -certfile "$OUT_DIR/ca.crt" -out "$OUT_DIR/gateway-keystore.p12" -name gateway -password "pass:$PASSWORD"

# Gateway Truststore
if (Test-Path "$OUT_DIR/gateway-truststore.p12") {
    Remove-Item "$OUT_DIR/gateway-truststore.p12" -Force
}
keytool -import -trustcacerts -alias root-ca -file "$OUT_DIR/ca.crt" -keystore "$OUT_DIR/gateway-truststore.p12" -storepass $PASSWORD -noprompt

# Client Keystore
openssl pkcs12 -export -in "$OUT_DIR/client.crt" -inkey "$OUT_DIR/client.key" -certfile "$OUT_DIR/ca.crt" -out "$OUT_DIR/client-keystore.p12" -name client -password "pass:$PASSWORD"

Write-Host "Certificates generated successfully in $OUT_DIR"

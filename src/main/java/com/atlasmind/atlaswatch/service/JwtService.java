package com.atlasmind.atlaswatch.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class JwtService {
    @Value("${security.jwt.expirationTime}")
    private long expirationTime;

    @Value("${security.jwt.publicKey:}")
    private String publicKeyValue;

    @Value("${security.jwt.privateKey:}")
    private String privateKeyValue;

    @Value("${security.jwt.publicKeyPath:}")
    private String publicKeyPath;

    @Value("${security.jwt.privateKeyPath:}")
    private String privateKeyPath;

    public Long getExpirationTime() {
        return expirationTime;
    }
    // UserDetails is like a profile card of the user that is logged in containing info about the user.
    // JWT has - Header, Payload, Signature ( ((hash) header + payload) + (signed) private key ).
    public String generateToken(UserDetails userDetails)throws NoSuchAlgorithmException, InvalidKeySpecException {
        return generateToken(new HashMap<>(), userDetails);
    }

    // Object because it allows multiple types in the payload
    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return Jwts
                .builder()
                .claims(extraClaims)
                .subject(userDetails.getUsername())
                .issuedAt((new Date(System.currentTimeMillis())))
                .expiration(new Date(System.currentTimeMillis() + expirationTime))
                .signWith((getPrivateKey()))
                .compact();
    }
    // Parser does the job of splitting the token and verifying the signature and then returning the claims.
    private Claims extractAllClaims(String jwtToken) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return Jwts
                .parser() // initializes the parser.
                .verifyWith(getPublicKey()) // This key is used to verify the signature of the JWT Token.
                .build() // Build the parser
                .parseSignedClaims(jwtToken) // Verify the signature of the Jwt Token.
                .getPayload(); // Get the payload of the returned Jws<Claims> (JWT Token) Object.
    }

    // Need to define what T is, need to specify that T is a generic type (String, Date, etc.) which is why <T> is declared!!
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) throws NoSuchAlgorithmException, InvalidKeySpecException {
        final Claims claim = extractAllClaims(token);
        return claimsResolver.apply(claim);
    }

    public String extractUsername(String jwtToken) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return extractClaim(jwtToken, Claims::getSubject); //need to specify the type of object it needs to be called on, which is why Claims is necessary!!
    }

    private Date extractExpiration(String token) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return extractClaim(token, Claims::getExpiration);
    }

    public boolean isTokenValid(String token, UserDetails userDetails) throws NoSuchAlgorithmException, InvalidKeySpecException {
        final String userEmail = extractUsername(token);
        return (userEmail.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }

    private boolean isTokenExpired(String token) throws NoSuchAlgorithmException, InvalidKeySpecException {
        return extractExpiration(token).before(new Date());
    }

    public PublicKey getPublicKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        String publicKey = resolveKeyMaterial(publicKeyValue, "PUBLIC_KEY");
        byte[] keyBytes = Base64.getDecoder().decode(publicKey);
        X509EncodedKeySpec spec = new X509EncodedKeySpec(keyBytes); // encoding of a public key
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePublic(spec);
    }

    private PrivateKey getPrivateKey() throws NoSuchAlgorithmException, InvalidKeySpecException {
        String privateKey = resolveKeyMaterial(privateKeyValue, "PRIVATE_KEY");
        byte[] keyBytes = Base64.getDecoder().decode(privateKey);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory kf = KeyFactory.getInstance("RSA");
        return kf.generatePrivate(spec);
    }

    private String resolveKeyMaterial(String rawKey, String keyName) {
        String normalizedKey = normalizeKey(rawKey);
        if (StringUtils.hasText(normalizedKey)) {
            return normalizedKey;
        }

        String fileBasedKey = readKeyFromConfiguredPath(keyName);
        String normalizedFileKey = normalizeKey(fileBasedKey);
        if (StringUtils.hasText(normalizedFileKey)) {
            return normalizedFileKey;
        }

        String dotenvKey = readMultilineKeyFromDotenv(keyName);
        String normalizedDotenvKey = normalizeKey(dotenvKey);
        if (StringUtils.hasText(normalizedDotenvKey)) {
            return normalizedDotenvKey;
        }

        throw new IllegalStateException(
                "Missing or invalid JWT key configuration: " + keyName +
                        ". Provide a key value, point to a PEM file, or keep a quoted multiline PEM block in .env."
        );
    }

    private String normalizeKey(String rawKey, String keyName) {
        if (!StringUtils.hasText(rawKey)) {
            throw new IllegalStateException("Missing JWT key configuration: " + keyName);
        }

        String normalizedKey = normalizeKey(rawKey);
        if (!StringUtils.hasText(normalizedKey)) {
            throw new IllegalStateException("Missing JWT key configuration: " + keyName);
        }

        return normalizedKey;
    }

    private String normalizeKey(String rawKey) {
        if (!StringUtils.hasText(rawKey)) {
            return "";
        }

        String normalizedKey = rawKey.trim();
        if (normalizedKey.startsWith("\"") && normalizedKey.endsWith("\"") && normalizedKey.length() >= 2) {
            normalizedKey = normalizedKey.substring(1, normalizedKey.length() - 1);
        }

        return normalizedKey
                .replace("\\n", "\n")
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
    }

    private String readMultilineKeyFromDotenv(String keyName) {
        Path dotenvPath = Paths.get(".env");
        if (!Files.exists(dotenvPath)) {
            return "";
        }

        try {
            String fileContents = Files.readString(dotenvPath);
            String keyPrefix = keyName + "=\"";
            int start = fileContents.indexOf(keyPrefix);
            if (start < 0) {
                return "";
            }

            int valueStart = start + keyPrefix.length();
            int valueEnd = fileContents.indexOf("\"", valueStart);
            if (valueEnd < 0) {
                return "";
            }

            return fileContents.substring(valueStart, valueEnd);
        } catch (IOException ex) {
            return "";
        }
    }

    private String readKeyFromConfiguredPath(String keyName) {
        String configuredPath = "PUBLIC_KEY".equals(keyName) ? publicKeyPath : privateKeyPath;

        String keyFromConfiguredPath = readKeyFile(configuredPath);
        if (StringUtils.hasText(keyFromConfiguredPath)) {
            return keyFromConfiguredPath;
        }

        String defaultPath = "PUBLIC_KEY".equals(keyName) ? "jwt-public.pem" : "jwt-private.pem";
        return readKeyFile(defaultPath);
    }

    private String readKeyFile(String keyPath) {
        if (!StringUtils.hasText(keyPath)) {
            return "";
        }

        try {
            Path path = Paths.get(keyPath.trim());
            if (!Files.exists(path)) {
                return "";
            }

            return Files.readString(path);
        } catch (IOException ex) {
            return "";
        }
    }
}

